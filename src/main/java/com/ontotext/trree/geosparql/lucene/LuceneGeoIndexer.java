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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Lucene-backed GeoSPARQL candidate indexer.
 *
 * <p>The Lucene index stores, for each source, its envelope in its own CRS, a CRS84 index envelope with its prefix
 * cells for the pairs that are looked up in CRS84, and native-CRS source geometry WKB and literal metadata for
 * later CRS-aware exact evaluation. A large source also
 * stores boxes that together contain it in its own CRS, which refine lookup for a bound placed in that CRS; a
 * source without them is looked up by its envelope alone. Each source geometry literal has one Lucene
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
 *
 * <p>A GraphDB transaction publishes its Lucene commit before GraphDB completes the RDF transaction. Queries read the
 * latest Lucene commit, so until GraphDB reports the outcome they can observe index changes whose RDF statements are
 * not yet visible. An abort restores the pre-transaction commit. The unresolved-transaction error is reserved for
 * outcomes that remain unresolved: the durable pending-transaction marker is present at startup, a rollback fails, or
 * the plugin configuration cannot be restored after an abort.
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
	private boolean recoveryRequiredAtTransactionStart;
	private GeoSparqlTransactionMarker pendingTransactionMarker;
	// Query threads read these gates while the GraphDB transaction thread updates them.
	private volatile boolean recoveryRequired;
	private volatile boolean schemaMismatchDetected;
	private volatile boolean crsEnvironmentMismatchDetected;
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
			removePendingTransactionMarker();
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
		try {
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
				removePendingTransactionMarker();
			}
			initSettings();
		} catch (Exception | Error e) {
			requireRecoveryAfterFailedRollback(e);
			throw e;
		} finally {
			clearTransactionState();
		}
	}

	/**
	 * Leaves the index unreadable in this process and after restart. The failed rollback may have left the published
	 * commit in place or removed the marker before failing, so the marker is written again. The retained
	 * pre-transaction snapshot is released because recovery rebuilds the index. The caller then clears the transaction
	 * state, so a later completed transaction cannot accept the failed outcome.
	 */
	private void requireRecoveryAfterFailedRollback(Throwable failure) {
		recoveryRequired = true;
		try {
			pendingTransactionMarker.create();
		} catch (Exception e) {
			failure.addSuppressed(e);
		}
		try {
			releasePreTransactionCommit();
		} catch (Exception e) {
			failure.addSuppressed(e);
		}
	}

	void removePendingTransactionMarker() throws IOException {
		pendingTransactionMarker.remove();
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
			addEntityDocument(LuceneGeoDocumentSchema.toDocument(
					subject, geometry, strategy, ctx));
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
	public CloseableIterator<CandidateEntity> getEnvelopeIntersections(IndexGeometry boundSourceIndexGeometry,
			boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(new MatchNoDocsQuery("Empty source has no envelope intersections."));
		}
		return getCandidateEntities(reader -> {
			EvaluationCrsLookup lookup = EvaluationCrsLookup.of(reader, boundSourceIndexGeometry, candidateIsSubject);
			return lookup.orElsewhere(lookup.intersecting(),
					() -> indexEnvelopeIntersectionsQuery(boundSourceIndexGeometry, candidateIsSubject));
		});
	}

	/**
	 * Selects sources of another CRS than the bound's by CRS84 index envelopes.
	 *
	 * <p>Jena evaluates a CRS84 subject in CRS84, against each object transformed there. The transformed vertices
	 * of an object lie inside its index envelope, which is widened by Jena's cleanup, and Jena joins them with
	 * straight lines that stay inside it. Numeric ranges therefore select every object of a CRS84 subject.
	 *
	 * <p>Any other such pair is evaluated in a CRS where a transformed edge can leave the CRS84 index envelope of
	 * its source, so sources whose index envelopes are apart can be related. The prefix cells of the two envelopes
	 * keep those pairs. Envelopes that overlap share a cell, which leaves the envelopes that meet the bound's
	 * despite their longitudes.
	 */
	private Query indexEnvelopeIntersectionsQuery(IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		Envelope boundEnvelope = boundSourceIndexGeometry.indexEnvelope();
		if (!candidateIsSubject && isCrs84(boundSourceIndexGeometry)) {
			return LuceneGeoDocumentSchema.envelopeIntersectsQuery(boundEnvelope);
		}
		Query prefixCellIntersections = prefixCellIntersectionsQuery(boundEnvelope);
		Query meetingDespiteLongitude = LuceneGeoDocumentSchema.envelopeMeetsDespiteLongitudeQuery(boundEnvelope);
		if (meetingDespiteLongitude == null) {
			return prefixCellIntersections;
		}
		return new BooleanQuery.Builder()
				.add(prefixCellIntersections, BooleanClause.Occur.SHOULD)
				.add(meetingDespiteLongitude, BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1).build();
	}

	private static boolean isCrs84(IndexGeometry indexGeometry) {
		return IndexGeometry.INDEX_CRS.equals(indexGeometry.sourceGeometryLiteral().effectiveCrsUri());
	}

	@Override
	public CloseableIterator<CandidateEntity> getEnvelopeEqualityCandidates(IndexGeometry boundSourceIndexGeometry,
			boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(new MatchNoDocsQuery("Empty source has no envelope intersections."));
		}
		return getCandidateEntities(reader -> {
			EvaluationCrsLookup lookup = EvaluationCrsLookup.of(reader, boundSourceIndexGeometry, candidateIsSubject);
			// A CRS84 index envelope is not exact for a source of another CRS, so equality there needs envelopes
			// that meet.
			return lookup.orElsewhere(lookup.withEqualEnvelope(),
					() -> indexEnvelopeIntersectionsQuery(boundSourceIndexGeometry, candidateIsSubject));
		});
	}

	@Override
	public CloseableIterator<CandidateEntity> getDisjointTransformCleanupCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(
					new MatchNoDocsQuery("Empty source has no disjoint transform-cleanup candidates."));
		}
		return getCandidateEntities(reader -> {
			EvaluationCrsLookup lookup = EvaluationCrsLookup.of(reader, boundSourceIndexGeometry, candidateIsSubject);
			// A source the bound is placed for is classified by exact envelopes and needs no cleanup evaluation.
			return lookup.elsewhere(
					disjointTransformCleanupCandidatesQuery(boundSourceIndexGeometry, candidateIsSubject));
		});
	}

	@Override
	public CloseableIterator<CandidateEntity> getEnvelopeDisjointUncertainCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getCandidateEntitiesForQuery(
					new MatchNoDocsQuery("Empty source has no uncertain disjoint candidates."));
		}
		return getCandidateEntities(reader -> {
			EvaluationCrsLookup lookup = EvaluationCrsLookup.of(reader, boundSourceIndexGeometry, candidateIsSubject);
			// A source the cleanup phase evaluates is left to that phase, so no source is evaluated twice.
			Query uncertain = lookup.withoutSourcesInsideTheBound(
					lookup.orElsewhere(lookup.possiblyDisjoint(), () -> new BooleanQuery.Builder()
							.add(indexEnvelopeDisjointUncertainQuery(boundSourceIndexGeometry), BooleanClause.Occur.FILTER)
							.add(disjointTransformCleanupCandidatesQuery(boundSourceIndexGeometry, candidateIsSubject),
									BooleanClause.Occur.MUST_NOT)
							.build()),
					new IndexSearcher(reader));
			if (!LuceneGeoDocumentSchema.hasAreaCollections(reader)) {
				return uncertain;
			}
			// The envelope-disjoint partition leaves area collections with separated envelopes to this evaluation.
			return new BooleanQuery.Builder()
					.add(uncertain, BooleanClause.Occur.SHOULD)
					.add(new BooleanQuery.Builder()
							.add(envelopeSeparatedQuery(lookup, boundSourceIndexGeometry, candidateIsSubject),
									BooleanClause.Occur.FILTER)
							.add(LuceneGeoDocumentSchema.areaCollectionQuery(), BooleanClause.Occur.FILTER)
							.build(), BooleanClause.Occur.SHOULD)
					.setMinimumNumberShouldMatch(1)
					.build();
		});
	}

	/**
	 * Selects uncertain disjoint candidates of another CRS than the bound's by CRS84 index envelopes.
	 *
	 * <p>A transformed source is treated as lying inside its SIS-derived index envelope under the
	 * conservative-envelope engineering assumption documented by ConservativeCrs84EnvelopeProjector. On that
	 * premise, when a CRS84 bound fills its own envelope, a candidate envelope wholly inside that rectangle proves
	 * the source pair cannot be disjoint. The inclusive ranges also remove boundary-contained sources: touching
	 * the closed bound still makes sfDisjoint, ehDisjoint, and rcc8dc false. A candidate whose cleanup target is
	 * not CRS84 is retained separately because the CRS84 containment proof does not model that displacement.
	 */
	private Query indexEnvelopeDisjointUncertainQuery(IndexGeometry boundSourceIndexGeometry) {
		Query envelopeIntersections = envelopeIntersectionsQuery(boundSourceIndexGeometry);
		if (!boundSourceIndexGeometry.isEnvelopeCoveringRectangle()) {
			return envelopeIntersections;
		}
		return new BooleanQuery.Builder()
				.add(envelopeIntersections, BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.envelopeNotWithinQuery(
						boundSourceIndexGeometry.indexEnvelope()), BooleanClause.Occur.FILTER)
				.build();
	}

	@Override
	public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		assertReadableCurrentSchema();
		if (boundSourceIndexGeometry == null || !boundSourceIndexGeometry.isSpatialCandidate()) {
			return getEnvelopeDisjointCandidates(reader ->
					new MatchNoDocsQuery("Empty source has no envelope-disjoint partition."));
		}
		return getEnvelopeDisjointCandidates(reader -> {
			EvaluationCrsLookup lookup = EvaluationCrsLookup.of(reader, boundSourceIndexGeometry, candidateIsSubject);
			Query separated = envelopeSeparatedQuery(lookup, boundSourceIndexGeometry, candidateIsSubject);
			if (!LuceneGeoDocumentSchema.hasAreaCollections(reader)) {
				return separated;
			}
			/*
			 * Separated envelopes do not prove RCC8 disconnected for an area collection; see
			 * IndexGeometry#isAreaCollection. Area collections are left to exact evaluation for every disjoint
			 * relation: they are rare, and exact evaluation decides the other relations as their envelopes would.
			 */
			return new BooleanQuery.Builder()
					.add(separated, BooleanClause.Occur.FILTER)
					.add(LuceneGeoDocumentSchema.areaCollectionQuery(), BooleanClause.Occur.MUST_NOT)
					.build();
		});
	}

	/**
	 * Selects the sources whose envelopes prove them disjoint from the bound.
	 *
	 * <p>For a source of another CRS than the bound's, strict separation of CRS84 index envelopes proves
	 * disjointness on the conservative-envelope engineering assumption for SIS-transformed envelopes. Candidates
	 * requiring mixed-CRS cleanup remain outside this proof and are exact-evaluated in their traversal phase.
	 */
	private Query envelopeSeparatedQuery(EvaluationCrsLookup lookup, IndexGeometry boundSourceIndexGeometry,
			boolean candidateIsSubject) throws IOException {
		return lookup.orElsewhere(lookup.apart(), () -> new BooleanQuery.Builder()
				.add(envelopeDisjointQuery(boundSourceIndexGeometry), BooleanClause.Occur.FILTER)
				.add(disjointTransformCleanupCandidatesQuery(boundSourceIndexGeometry, candidateIsSubject),
						BooleanClause.Occur.MUST_NOT)
				.build());
	}

	private Query envelopeDisjointQuery(IndexGeometry boundSourceIndexGeometry) {
		return new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.hasEnvelopeQuery(true), BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.envelopeDisjointQuery(boundSourceIndexGeometry.indexEnvelope()),
						BooleanClause.Occur.FILTER)
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
			if (e instanceof PluginException pluginException) {
				throw pluginException;
			}
			throw new PluginException("Unable to execute Lucene query.", e);
		}

	}

	private Query envelopeIntersectionsQuery(IndexGeometry indexGeometry) {
		return LuceneGeoDocumentSchema.envelopeIntersectsQuery(indexGeometry.indexEnvelope());
	}

	/** Matches sources with one of the strategy's prefix cells among the cells of the bound's index envelope. */
	private Query prefixCellIntersectionsQuery(Envelope envelope) {
		return strategy.makeQuery(new SpatialArgs(SpatialOperation.Intersects, ctx.getShapeFactory().rect(
				envelope.getMinX(), envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY())));
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
		return getCandidateEntities(reader -> query);
	}

	private CloseableIterator<CandidateEntity> getCandidateEntities(ReaderCandidateQuery candidateQuery) {
		IndexReader indexReader = null;
		try {
			indexReader = openReader();
			Query query = candidateQuery.forReader(indexReader);
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);
			return new LuceneCandidateEntityIterator(indexSearcher, query);
		} catch (Exception e) {
			if (indexReader != null) {
				try {
					indexReader.close();
				} catch (IOException cleanupFailure) {
					e.addSuppressed(cleanupFailure);
				}
			}
			if (e instanceof PluginException pluginException) {
				throw pluginException;
			}
			throw new PluginException("Unable to execute Lucene query.", e);
		}
	}

	/** Builds a candidate query from the reader that executes it, so the query fits the sources of that commit. */
	@FunctionalInterface
	private interface ReaderCandidateQuery {
		Query forReader(IndexReader reader) throws IOException;
	}

	private CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
			ReaderCandidateQuery candidateQuery) {
		IndexReader indexReader = null;
		try {
			indexReader = openReader();
			Query query = candidateQuery.forReader(indexReader);
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);
			return new LuceneEnvelopeDisjointCandidateIterator(indexSearcher, query);
		} catch (Exception e) {
			if (indexReader != null) {
				try {
					indexReader.close();
				} catch (IOException cleanupFailure) {
					e.addSuppressed(cleanupFailure);
				}
			}
			if (e instanceof PluginException pluginException) {
				throw pluginException;
			}
			throw new PluginException("Unable to execute envelope-disjoint candidate query.", e);
		}
	}

	/**
	 * Opens the index for a query. A query passes the compatibility gate before it opens the index, and a rollback
	 * can restore an incompatible commit in between, so the commit the query reads is checked as well.
	 */
	IndexReader openReader() throws IOException {
		DirectoryReader reader = DirectoryReader.open(directory);
		String incompatibility;
		try {
			incompatibility = incompatibleCommitMessage(reader);
		} catch (IOException | RuntimeException e) {
			reader.close();
			throw e;
		}
		if (incompatibility != null) {
			reader.close();
			throw new PluginException(incompatibility);
		}
		return reader;
	}

	/**
	 * Returns the error that reading the reader's commit raises, or null when this plugin can read it. An empty
	 * index is readable whatever its metadata, and the CRS environment matters only for the current layout.
	 */
	private String incompatibleCommitMessage(DirectoryReader reader) throws IOException {
		if (reader.numDocs() == 0) {
			return null;
		}
		Map<String, String> commitData = reader.getIndexCommit().getUserData();
		if (!LuceneGeoDocumentSchema.hasCurrentSchemaCommitData(commitData)) {
			return SCHEMA_MISMATCH_MESSAGE;
		}
		if (!currentCrsEnvironmentFingerprint.equals(
				commitData.get(LuceneGeoDocumentSchema.COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY))) {
			return CRS_ENVIRONMENT_MISMATCH_MESSAGE;
		}
		return null;
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
			throw new PluginException("The GeoSPARQL Lucene index has an unresolved GraphDB transaction outcome. "
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
				return SCHEMA_MISMATCH_MESSAGE.equals(incompatibleCommitMessage(reader));
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
				return CRS_ENVIRONMENT_MISMATCH_MESSAGE.equals(incompatibleCommitMessage(reader));
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
