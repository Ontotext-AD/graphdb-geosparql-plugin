package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlIndexer;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlTransactionMarker;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Rectangle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lucene-backed GeoSPARQL candidate indexer.
 *
 * <p>The Lucene index stores one CRS84 source envelope for coarse candidate lookup, plus native-CRS source geometry
 * WKB and literal metadata for later CRS-aware exact evaluation. Each source geometry literal has one Lucene
 * document. Candidate queries group matching source documents by entity, while direct entity reads stream source
 * geometry literal snapshots. Returned iterators own their Lucene readers and are closed by their callers.
 *
 * <p>During startup, the indexer performs index-level schema and CRS transformation environment checks. A non-empty
 * index must have the current schema metadata and the fingerprint of the transformation inputs used for candidate
 * envelopes. Missing or mismatched commit metadata causes query and update paths to fail with a force-reindex
 * message. These checks read commit metadata rather than stored documents, so startup remains independent of the
 * number of indexed geometries. The fingerprint is required conservatively for every non-empty index.
 *
 * <p>Fresh indexes and empty indexes can accept v2 writes. Successful v2 writes schedule the compatibility metadata
 * to be written on commit. Document-level schema validation remains defensive: a marked index with malformed or
 * mixed documents fails during document decoding rather than silently using invalid source geometry literal data.
 *
 * <p>During force reindex, writes are allowed after {@link #freshIndex()}, but
 * reads remain gated by the previously committed compatibility state until commit succeeds. Rollback or failed
 * commit restores that previous state, so an abandoned reindex cannot make this indexer trust an incompatible index.
 */
public class LuceneGeoIndexer implements GeoSparqlIndexer {
	private GeoSparqlPlugin parent;

	private SpatialContext ctx;

    private SpatialStrategy strategy;
    private Path indexDir;

    private Directory directory;

	private IndexWriter indexWriter;
	private SnapshotDeletionPolicy snapshotDeletionPolicy;
	private IndexCommit preTransactionCommit;
	private boolean transactionActive;
	private boolean provisionalCommitPublished;
	private boolean schemaMismatchAtTransactionStart;
	private boolean crsEnvironmentMismatchAtTransactionStart;
	private boolean recoveryRequired;
	private boolean recoveryRequiredAtTransactionStart;
	private GeoSparqlTransactionMarker pendingTransactionMarker;
	private boolean schemaMismatchDetected;
	private boolean crsEnvironmentMismatchDetected;
	private boolean compatibilityMetadataPending;
	private boolean schemaRebuildInProgress;
	private final Supplier<String> crsEnvironmentFingerprintSupplier;
	private String currentCrsEnvironmentFingerprint;

	static final String SCHEMA_MISMATCH_MESSAGE = LuceneGeoDocumentSchema.SCHEMA_MISMATCH_MESSAGE;
	static final String CRS_ENVIRONMENT_MISMATCH_MESSAGE =
			"The GeoSPARQL Lucene index was built with a different CRS transformation environment. "
			+ "Apache SIS, EPSG definitions, or datum transformation grids have changed. "
			+ "Queries are unavailable until a full GeoSPARQL force-reindex completes.";

	public LuceneGeoIndexer(GeoSparqlPlugin parent) {
		this(parent, CrsEnvironmentFingerprint::current);
	}

	LuceneGeoIndexer(GeoSparqlPlugin parent, Supplier<String> crsEnvironmentFingerprintSupplier) {
		this.parent = parent;
		this.crsEnvironmentFingerprintSupplier = crsEnvironmentFingerprintSupplier;
	}


	@Override
	public void initialize() throws Exception {
		this.ctx = SpatialContext.GEO;

        this.indexDir = GeoSparqlConfig.resolveIndexPath(parent.getDataDir().toPath());

		this.directory = FSDirectory.open(indexDir);
		this.pendingTransactionMarker = new GeoSparqlTransactionMarker(parent.getDataDir().toPath());
		this.snapshotDeletionPolicy = new SnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());

		initSettings();
		currentCrsEnvironmentFingerprint = crsEnvironmentFingerprintSupplier.get();
		schemaMismatchDetected = detectSchemaMismatch();
		crsEnvironmentMismatchDetected = detectCrsEnvironmentMismatch();
		recoveryRequired = pendingTransactionMarker.exists();
	}

	@Override
	public void initSettings() {
		SpatialPrefixTree grid;
		GeoSparqlConfig.PrefixTree prefixTree = parent.getConfig().getCurrentPrefixTree();
		int precision = parent.getConfig().getCurrentPrecision();
		if (prefixTree == GeoSparqlConfig.PrefixTree.QUAD) {
			grid = new QuadPrefixTree(ctx, precision);
		} else if (prefixTree == GeoSparqlConfig.PrefixTree.GEOHASH) {
			grid = new GeohashPrefixTree(ctx, precision);
		} else {
			throw new PluginException("Unexpected prefix tree type: " + prefixTree);
		}

		this.strategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
	}

	@Override
	public void begin() throws Exception {
		if (transactionActive) {
			throw new IllegalStateException("A GeoSPARQL index transaction is already active.");
		}
		Files.createDirectories(indexDir);
		boolean existingCommit = DirectoryReader.indexExists(directory);
		indexWriter = new IndexWriter(directory, newIndexWriterConfig());
		try {
			preTransactionCommit = existingCommit ? snapshotExistingCommit() : null;
			transactionActive = true;
			provisionalCommitPublished = false;
			schemaMismatchAtTransactionStart = schemaMismatchDetected;
			crsEnvironmentMismatchAtTransactionStart = crsEnvironmentMismatchDetected;
			recoveryRequiredAtTransactionStart = recoveryRequired;
		} catch (Exception e) {
			try {
				releasePreTransactionCommit();
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			try {
				indexWriter.rollback();
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			indexWriter = null;
			throw e;
		}
	}

	IndexCommit snapshotExistingCommit() throws IOException {
		return snapshotDeletionPolicy.snapshot();
	}

	private IndexWriterConfig newIndexWriterConfig() {
		IndexWriterConfig config = new IndexWriterConfig();
		// Turn off compound file format.
		// Building the compound file format takes time during indexing (7-33%)
		config.setUseCompoundFile(false);
		// Set maxBufferedDocs large enough to prevent the writer from flushing based on document count.
		config.setMaxBufferedDocs(parent.getConfig().getMaxBufferedDocs());
		//More RAM before flushing means Lucene writes larger segments to begin with which means less merging later.
		config.setRAMBufferSizeMB(parent.getConfig().getRamBufferSizeMb());
		config.setIndexDeletionPolicy(snapshotDeletionPolicy);
		return config;
	}

	@Override
	public boolean isTransactionActive() {
		return transactionActive;
	}

	@Override
	public void commit() throws Exception {
		boolean rebuildWasInProgress = schemaRebuildInProgress;
		try {
			if (recoveryRequiredAtTransactionStart && !rebuildWasInProgress) {
				assertReadableCurrentSchema();
			}
			writeCompatibilityMetadataIfNeeded();
			pendingTransactionMarker.create();
			recoveryRequired = true;
			closeIndexWriter(); // also commits
			provisionalCommitPublished = true;
			if (rebuildWasInProgress) {
				schemaMismatchDetected = false;
				crsEnvironmentMismatchDetected = false;
			}
		} finally {
			if (!provisionalCommitPublished) {
				schemaMismatchDetected = schemaMismatchAtTransactionStart;
				crsEnvironmentMismatchDetected = crsEnvironmentMismatchAtTransactionStart;
			}
			compatibilityMetadataPending = false;
			schemaRebuildInProgress = false;
		}
	}

	void closeIndexWriter() throws IOException {
		indexWriter.close();
	}

	@Override
	public void discardUncommittedChanges() throws Exception {
		if (!transactionActive || indexWriter == null || !indexWriter.isOpen()) {
			return;
		}
		indexWriter.rollback();
		indexWriter = null;
		provisionalCommitPublished = false;
		schemaMismatchDetected = schemaMismatchAtTransactionStart;
		crsEnvironmentMismatchDetected = crsEnvironmentMismatchAtTransactionStart;
		compatibilityMetadataPending = false;
		schemaRebuildInProgress = false;
	}

	@Override
	public void complete() throws Exception {
		if (!transactionActive) {
			return;
		}
		IOException cleanupFailure = null;
		try {
			releasePreTransactionCommit();
			deleteObsoleteCommits();
		} catch (IOException e) {
			cleanupFailure = e;
		}
		try {
			pendingTransactionMarker.remove();
		} catch (IOException e) {
			if (cleanupFailure == null) {
				cleanupFailure = e;
			} else {
				cleanupFailure.addSuppressed(e);
			}
		}
		recoveryRequired = false;
		clearTransactionState();
		if (cleanupFailure != null) {
			throw cleanupFailure;
		}
	}

	@Override
	public void rollback() throws Exception {
		rollback(false);
	}

	@Override
	public void rollback(boolean requireRecovery) throws Exception {
		if (!transactionActive) {
			return;
		}
		boolean pendingCommit = pendingTransactionMarker.exists();
		if (indexWriter != null && indexWriter.isOpen()) {
			indexWriter.rollback();
		}
		boolean commitWasPublished = provisionalCommitPublished
				|| pendingCommit && hasCommitAfterPreTransactionCommit();
		if (commitWasPublished) {
			restorePreTransactionCommit();
		}
		releasePreTransactionCommit();
		deleteObsoleteCommits();
		schemaMismatchDetected = schemaMismatchAtTransactionStart;
		crsEnvironmentMismatchDetected = crsEnvironmentMismatchAtTransactionStart;
		recoveryRequired = recoveryRequiredAtTransactionStart || requireRecovery;
		if (requireRecovery && !pendingTransactionMarker.exists()) {
			pendingTransactionMarker.create();
		}
		if (!recoveryRequired) {
			pendingTransactionMarker.remove();
		}
		initSettings();
		clearTransactionState();
	}

	@Override
	public void freshIndex() throws Exception {
		indexWriter.deleteAll();
		schemaRebuildInProgress = true;
		compatibilityMetadataPending = true;
	}

	@Override
	public void indexGeometryList(long subject, Function<Long, String> subjectMapper, List<IndexGeometry> geometries) {
		//logger.info("Indexing literal for {}; {}", parent.getEntities().get(subject), geometries.size());
		assertWritableCurrentSchema();
		try {
			List<org.apache.lucene.document.Document> documents = LuceneGeoDocumentSchema.toDocuments(
					subject, geometries, strategy, ctx);
			deleteEntityDocuments(subject);
			if (!documents.isEmpty()) {
				addEntityDocuments(documents);
				compatibilityMetadataPending = true;
			}
		} catch (Exception e) {
			throw luceneMutationFailure(subject, subjectMapper, e);
		}
	}

	@Override
	public void appendGeometry(long subject, Function<Long, String> subjectMapper, IndexGeometry geometry) {
		assertWritableCurrentSchema();
		try {
			addEntityDocument(LuceneGeoDocumentSchema.toDocument(subject, geometry, strategy, ctx));
			compatibilityMetadataPending = true;
		} catch (Exception e) {
			throw luceneMutationFailure(subject, subjectMapper, e);
		}
	}

	void deleteEntityDocuments(long subject) throws IOException {
		indexWriter.deleteDocuments(LuceneGeoDocumentSchema.entityIdQuery(subject));
	}

	void addEntityDocuments(List<org.apache.lucene.document.Document> documents) throws IOException {
		indexWriter.addDocuments(documents);
	}

	void addEntityDocument(org.apache.lucene.document.Document document) throws IOException {
		indexWriter.addDocument(document);
	}

	@Override
	public CloseableIterator<CandidateEntity> getEnvelopeIntersections(IndexGeometry boundSourceIndexGeometry) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(new MatchNoDocsQuery("Empty source has no envelope intersections."));
		}
		Query envelopeIntersections = envelopeIntersectionsQuery(boundSourceIndexGeometry);
		return getCandidateEntitiesForQuery(envelopeIntersections);
	}

	@Override
	public CloseableIterator<CandidateEntity> getDisjointTransformCleanupCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(
					new MatchNoDocsQuery("Empty source has no disjoint transform-cleanup candidates."));
		}
		return getCandidateEntitiesForQuery(
				disjointTransformCleanupCandidatesQuery(boundSourceIndexGeometry, candidateIsSubject));
	}

	@Override
	public CloseableIterator<CandidateEntity> getEnvelopeDisjointUncertainCandidates(
			IndexGeometry boundSourceIndexGeometry) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(
					new MatchNoDocsQuery("Empty source has no uncertain disjoint candidates."));
		}
		Query envelopeIntersections = envelopeIntersectionsQuery(boundSourceIndexGeometry);
		if (!boundSourceIndexGeometry.isEnvelopeCoveringRectangle()) {
			return getCandidateEntitiesForQuery(envelopeIntersections);
		}
		/*
		 * A native CRS84 source lies inside its source-derived index envelope. A transformed source is treated as lying
		 * inside its SIS-derived index envelope under the conservative-envelope engineering assumption documented by
		 * ConservativeCrs84EnvelopeProjector. On that premise, when the native CRS84 bound source fills its own
		 * envelope, a candidate envelope wholly inside that rectangle proves the source pair cannot be disjoint. The
		 * inclusive ranges also remove boundary-contained sources: touching the closed bound still makes sfDisjoint,
		 * ehDisjoint, and rcc8dc false. A mixed-CRS candidate subject whose cleanup target is non-CRS84 is retained
		 * separately because the CRS84 containment proof does not model that displacement.
		 */
		Query ordinaryUncertain = new BooleanQuery.Builder()
				.add(envelopeIntersectionsQuery(boundSourceIndexGeometry), BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.envelopeWithinQuery(
						boundSourceIndexGeometry.indexEnvelope()), BooleanClause.Occur.MUST_NOT)
				.build();
		return getCandidateEntitiesForQuery(ordinaryUncertain);
	}

	@Override
	public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
			IndexGeometry boundSourceIndexGeometry) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getEnvelopeDisjointCandidatesForQuery(
					new MatchNoDocsQuery("Empty source has no envelope-disjoint partition."));
		}
		return getEnvelopeDisjointCandidatesForQuery(
				envelopeDisjointQuery(envelopeIntersectionsQuery(boundSourceIndexGeometry)));
	}

	@Override
	public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getEnvelopeDisjointCandidatesForQuery(
					new MatchNoDocsQuery("Empty source has no envelope-disjoint partition."));
		}
		Query envelopeIntersections = disjointCleanupSafeEnvelopeIntersectionsQuery(
				boundSourceIndexGeometry, candidateIsSubject);
		/*
		 * Given direct containment for native CRS84 envelopes and the documented conservative-envelope engineering
		 * assumption for SIS-transformed envelopes, prefix-tree Intersects is conservative for source geometries:
		 * approximation may retain false positives but does not omit an envelope that intersects the bound. Its
		 * complement therefore contains only envelope-separated source documents, which relation traversal can classify
		 * without source payloads.
		 */
		return getEnvelopeDisjointCandidatesForQuery(envelopeDisjointQuery(envelopeIntersections));
	}

	private Query envelopeDisjointQuery(Query envelopeIntersections) {
		return new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.hasEnvelopeQuery(true), BooleanClause.Occur.FILTER)
				.add(envelopeIntersections, BooleanClause.Occur.MUST_NOT)
				.build();
	}

	@Override
	public CloseableIterator<CandidateEntity> getNonSpatialCandidates() {
		assertReadableCurrentSchema();
		return getCandidateEntitiesForQuery(LuceneGeoDocumentSchema.hasEnvelopeQuery(false));
	}

	@Override
	public CloseableIterator<CandidateEntity> getAllEntities() {
		assertReadableCurrentSchema();
		return getCandidateEntitiesForQuery(LuceneGeoDocumentSchema.allDocumentsQuery());
	}

	@Override
	public CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsFor(long subject) {
		assertReadableCurrentSchema();
		final Query query;
		if (subject > 0) {
			query = LuceneGeoDocumentSchema.entityIdQuery(subject);
		} else {
			query = LuceneGeoDocumentSchema.allDocumentsQuery();
		}

		return getSourceGeometryLiteralsForQuery(query);
	}

	private CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsForQuery(Query query) {
		IndexReader indexReader = null;
		try {
			indexReader = openReader();
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);
			return new LuceneSourceGeometryLiteralIterator(indexSearcher, query);
		} catch (Exception e) {
			if (indexReader != null) {
				try {
					indexReader.close();
				} catch (IOException x) {
					// ignore
				}
			}
			throw new PluginException("Unable to execute Lucene query.", e);
		}

	}

	private Rectangle envelopeShape(IndexGeometry indexGeometry) {
		Envelope envelope = indexGeometry.indexEnvelope();
		return ctx.getShapeFactory().rect(
				envelope.getMinX(), envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY());
	}

	private Query envelopeIntersectionsQuery(IndexGeometry indexGeometry) {
		return strategy.makeQuery(
				new SpatialArgs(SpatialOperation.Intersects, envelopeShape(indexGeometry)));
	}

	private Query disjointCleanupSafeEnvelopeIntersectionsQuery(
			IndexGeometry indexGeometry, boolean candidateIsSubject) {
		return new BooleanQuery.Builder()
				.add(envelopeIntersectionsQuery(indexGeometry), BooleanClause.Occur.SHOULD)
				.add(disjointTransformCleanupCandidatesQuery(indexGeometry, candidateIsSubject),
						BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	private Query disjointTransformCleanupCandidatesQuery(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		String boundSourceCrs = boundSourceIndexGeometry.sourceGeometryLiteral().effectiveCrsUri();
		if (!candidateIsSubject && IndexGeometry.INDEX_CRS.equals(boundSourceCrs)) {
			return new MatchNoDocsQuery("CRS84 cleanup does not prevent disjoint envelope classification.");
		}
		BooleanQuery.Builder query = new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.hasEnvelopeQuery(true), BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.sourceCrsQuery(boundSourceCrs), BooleanClause.Occur.MUST_NOT);
		if (candidateIsSubject) {
			query.add(LuceneGeoDocumentSchema.sourceCrsQuery(IndexGeometry.INDEX_CRS),
					BooleanClause.Occur.MUST_NOT);
		}
		return query.build();
	}

	private CloseableIterator<CandidateEntity> getCandidateEntitiesForQuery(Query query) {
		try {
			IndexReader indexReader = openReader();
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);
			return new LuceneCandidateEntityIterator(indexSearcher, query);
		} catch (IOException e) {
			throw new PluginException("Unable to execute Lucene query.", e);
		}
	}

	private CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidatesForQuery(Query query) {
		try {
			IndexReader indexReader = openReader();
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);
			return new LuceneEnvelopeDisjointCandidateIterator(indexSearcher, query);
		} catch (IOException e) {
			throw new PluginException("Unable to execute envelope-disjoint candidate query.", e);
		}
	}

	IndexReader openReader() throws IOException {
		return DirectoryReader.open(directory);
	}

	private PluginException luceneMutationFailure(long subject, Function<Long, String> subjectMapper, Exception e) {
		if (e instanceof PluginException) {
			return (PluginException) e;
		}
		return new PluginException("Unable to persist GeoSPARQL Lucene documents for subject "
				+ subjectMapper.apply(subject), e);
	}

	private void assertReadableCurrentSchema() {
		if (recoveryRequired) {
			throw new PluginException("The GeoSPARQL Lucene index has a pending GraphDB transaction. "
					+ "Queries are unavailable until a full force-reindex completes.");
		}
		if (schemaMismatchDetected) {
			throw new PluginException(SCHEMA_MISMATCH_MESSAGE);
		}
		if (crsEnvironmentMismatchDetected) {
			throw new PluginException(CRS_ENVIRONMENT_MISMATCH_MESSAGE);
		}
	}

	private void assertWritableCurrentSchema() {
		if (schemaRebuildInProgress) {
			return;
		}
		assertReadableCurrentSchema();
	}

	private boolean detectSchemaMismatch() {
		try {
			if (!DirectoryReader.indexExists(directory)) {
				return false;
			}
			try (DirectoryReader reader = DirectoryReader.open(directory)) {
				if (reader.numDocs() == 0) {
					return false;
				}
				return !LuceneGeoDocumentSchema.hasCurrentSchemaCommitData(reader.getIndexCommit().getUserData());
			}
		} catch (IndexNotFoundException e) {
			return false;
		} catch (IOException e) {
			throw new PluginException("Unable to inspect GeoSPARQL Lucene index schema.", e);
		}
	}

	private boolean detectCrsEnvironmentMismatch() {
		try {
			if (!DirectoryReader.indexExists(directory)) {
				return false;
			}
			try (DirectoryReader reader = DirectoryReader.open(directory)) {
				if (reader.numDocs() == 0
						|| !LuceneGeoDocumentSchema.hasCurrentSchemaCommitData(reader.getIndexCommit().getUserData())) {
					return false;
				}
				return !currentCrsEnvironmentFingerprint.equals(reader.getIndexCommit().getUserData()
						.get(LuceneGeoDocumentSchema.COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY));
			}
		} catch (IndexNotFoundException e) {
			return false;
		} catch (IOException e) {
			throw new PluginException("Unable to inspect the GeoSPARQL Lucene index CRS environment.", e);
		}
	}

	private void writeCompatibilityMetadataIfNeeded() throws IOException {
		if (!compatibilityMetadataPending) {
			return;
		}
		indexWriter.setLiveCommitData(LuceneGeoDocumentSchema.currentCompatibilityCommitData(
				indexWriter.getLiveCommitData(), currentCrsEnvironmentFingerprint));
	}

	protected void restorePreTransactionCommit() throws IOException {
		IndexWriterConfig restoreConfig = newIndexWriterConfig();
		if (preTransactionCommit == null) {
			restoreConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
		} else {
			restoreConfig.setIndexCommit(preTransactionCommit);
		}
		try (IndexWriter restoreWriter = new IndexWriter(directory, restoreConfig)) {
			if (preTransactionCommit != null) {
				restoreWriter.setLiveCommitData(preTransactionCommit.getUserData().entrySet());
			}
			restoreWriter.commit();
		}
	}

	private boolean hasCommitAfterPreTransactionCommit() throws IOException {
		if (!DirectoryReader.indexExists(directory)) {
			return false;
		}
		if (preTransactionCommit == null) {
			return true;
		}
		try (DirectoryReader reader = DirectoryReader.open(directory)) {
			return reader.getIndexCommit().getGeneration() != preTransactionCommit.getGeneration();
		}
	}

	private void releasePreTransactionCommit() throws IOException {
		if (preTransactionCommit != null) {
			snapshotDeletionPolicy.release(preTransactionCommit);
			preTransactionCommit = null;
		}
	}

	private void deleteObsoleteCommits() throws IOException {
		IndexWriter cleanupWriter = new IndexWriter(directory, newIndexWriterConfig());
		try {
			cleanupWriter.deleteUnusedFiles();
		} finally {
			cleanupWriter.rollback();
		}
	}

	private void clearTransactionState() {
		transactionActive = false;
		provisionalCommitPublished = false;
		indexWriter = null;
		compatibilityMetadataPending = false;
		schemaRebuildInProgress = false;
		recoveryRequiredAtTransactionStart = false;
	}
}
