package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.store.FSDirectory;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.spatial4j.context.SpatialContext;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativePrefixCoverLifecycleTest {

	private static final String TRIANGLE = "POLYGON((0 0,8 0,0 8,0 0))";
	private static final String CAPABILITY_KEY = "geosparql.nativeCrs84PrefixCover";
	private static final String CAPABILITY_VALUE = "convex-hull";
	private static final String FINGERPRINT = "test-crs-environment";

	@Rule
	public TemporaryLocalFolder folder = new TemporaryLocalFolder();

	@Test
	public void incrementalWritesDoNotEnableRefinementOnAnUnmarkedIndex() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = indexer(dataDir);
		assertEquals(Set.of(1L), candidates(indexer));
		indexer.begin();
		writeTriangle(indexer);
		indexer.commit();
		indexer.complete();

		assertFalse(commitData(dataDir).containsKey(CAPABILITY_KEY));
		assertEquals(Set.of(1L), candidates(indexer));
		assertEquals(Set.of(1L), candidates(indexer(dataDir)));
	}

	@Test
	public void forceReindexEnablesRefinementAndPersistsCapability() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = indexer(dataDir);
		try (CloseableIterator<CandidateEntity> previousCommit = intersections(indexer)) {
			indexer.begin();
			indexer.freshIndex();
			writeTriangle(indexer);
			assertEquals(Set.of(1L), candidates(indexer));
			indexer.commit();
			indexer.complete();

			assertEquals(CAPABILITY_VALUE, commitData(dataDir).get(CAPABILITY_KEY));
			assertEquals(Set.of(1L), collect(previousCommit));
			assertEquals(Set.of(), candidates(indexer));
			assertEquals(Set.of(), candidates(indexer(dataDir)));
		}
	}

	@Test
	public void abortedRebuildDoesNotPublishCapability() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = indexer(dataDir);
		indexer.begin();
		indexer.freshIndex();
		writeTriangle(indexer);
		indexer.rollback();

		assertFalse(commitData(dataDir).containsKey(CAPABILITY_KEY));
		assertEquals(Set.of(1L), candidates(indexer));
		assertEquals(Set.of(1L), candidates(indexer(dataDir)));
	}

	@Test
	public void postCommitAbortRestoresLookupPolicyAndMetadata() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = indexer(dataDir);
		indexer.begin();
		indexer.freshIndex();
		writeTriangle(indexer);
		indexer.commit();
		assertEquals(CAPABILITY_VALUE, commitData(dataDir).get(CAPABILITY_KEY));
		indexer.rollback();

		assertFalse(commitData(dataDir).containsKey(CAPABILITY_KEY));
		assertEquals(Set.of(1L), candidates(indexer));
		assertEquals(Set.of(1L), candidates(indexer(dataDir)));
	}

	@Test
	public void failedRebuildCommitDoesNotPublishCapability() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent(dataDir), () -> FINGERPRINT) {
			@Override
			void closeIndexWriter() throws IOException {
				throw new IOException("Simulated commit failure");
			}
		};
		indexer.initialize();
		indexer.begin();
		indexer.freshIndex();
		writeTriangle(indexer);
		assertThrows(IOException.class, indexer::commit);
		indexer.rollback();

		assertFalse(commitData(dataDir).containsKey(CAPABILITY_KEY));
		assertEquals(Set.of(1L), candidates(indexer(dataDir)));
	}

	@Test
	public void discardedRebuildDoesNotEnableLaterIncrementalWrites() throws Exception {
		Path dataDir = envelopeIndex();
		LuceneGeoIndexer indexer = indexer(dataDir);
		indexer.begin();
		indexer.freshIndex();
		writeTriangle(indexer);
		indexer.discardUncommittedChanges();
		indexer.rollback();
		indexer.begin();
		writeTriangle(indexer);
		indexer.commit();
		indexer.complete();

		assertFalse(commitData(dataDir).containsKey(CAPABILITY_KEY));
		assertEquals(Set.of(1L), candidates(indexer));
	}

	@Test
	public void freshIndexAndIncrementalUpdatesRetainCapability() throws Exception {
		Path dataDir = folder.newFolder("fresh-index").toPath();
		LuceneGeoIndexer indexer = indexer(dataDir);
		for (int i = 0; i < 2; i++) {
			indexer.begin();
			writeTriangle(indexer);
			indexer.commit();
			indexer.complete();
			assertEquals(CAPABILITY_VALUE, commitData(dataDir).get(CAPABILITY_KEY));
			assertEquals(Set.of(), candidates(indexer(dataDir)));
		}
	}

	@Test
	public void unknownCapabilityUsesNumericLookup() throws Exception {
		Path dataDir = folder.newFolder("unknown-capability").toPath();
		LuceneGeoIndexer indexer = indexer(dataDir);
		indexer.begin();
		writeTriangle(indexer);
		indexer.commit();
		indexer.complete();
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
			 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
			Map<String, String> metadata = new java.util.LinkedHashMap<>(commitData(dataDir));
			metadata.put(CAPABILITY_KEY, "unknown");
			writer.setLiveCommitData(metadata.entrySet());
		}
		assertEquals(Set.of(1L), candidates(indexer));
	}

	private Path envelopeIndex() throws Exception {
		Path dataDir = folder.newFolder().toPath();
		SpatialContext context = SpatialContext.GEO;
		RecursivePrefixTreeStrategy strategy = new RecursivePrefixTreeStrategy(
				new QuadPrefixTree(context, 11), LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
		Document document = LuceneGeoDocumentSchema.toDocument(1L,
				TestIndexGeometries.fromWkt(TRIANGLE), strategy, context);
		document.removeFields(LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
		for (var field : strategy.createIndexableFields(context.makeRectangle(0, 8, 0, 8))) {
			document.add(field);
		}
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
			 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
			writer.addDocument(document);
			writer.setLiveCommitData(Map.of(
					LuceneGeoDocumentSchema.COMMIT_SCHEMA_VERSION_KEY, LuceneGeoDocumentSchema.COMMIT_SCHEMA_VERSION_VALUE,
					LuceneGeoDocumentSchema.COMMIT_SCHEMA_LAYOUT_KEY, LuceneGeoDocumentSchema.COMMIT_SCHEMA_LAYOUT_VALUE,
					LuceneGeoDocumentSchema.COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY, FINGERPRINT).entrySet());
		}
		return dataDir;
	}

	private LuceneGeoIndexer indexer(Path dataDir) throws Exception {
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent(dataDir), () -> FINGERPRINT);
		indexer.initialize();
		return indexer;
	}

	private GeoSparqlPlugin parent(Path dataDir) {
		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setPrefixTree(GeoSparqlConfig.PrefixTree.QUAD);
		config.setPrecision(11);
		config.updateCurrentSettings();
		parent.setConfig(config);
		parent.setDataDir(dataDir.toFile());
		parent.setLogger(LoggerFactory.getLogger(NativePrefixCoverLifecycleTest.class));
		return parent;
	}

	private void writeTriangle(LuceneGeoIndexer indexer) {
		indexer.indexGeometryList(1L, Object::toString, List.of(TestIndexGeometries.fromWkt(TRIANGLE)));
	}

	private CloseableIterator<CandidateEntity> intersections(LuceneGeoIndexer indexer) {
		return indexer.getEnvelopeIntersections(TestIndexGeometries.fromWkt("POINT(7 7)"));
	}

	private Set<Long> candidates(LuceneGeoIndexer indexer) throws Exception {
		try (CloseableIterator<CandidateEntity> iterator = intersections(indexer)) {
			return collect(iterator);
		}
	}

	private Set<Long> collect(CloseableIterator<CandidateEntity> iterator) {
		Set<Long> ids = new LinkedHashSet<>();
		while (iterator.hasNext()) {
			ids.add(iterator.next().entityId());
		}
		return ids;
	}

	private Map<String, String> commitData(Path dataDir) throws IOException {
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
			 DirectoryReader reader = DirectoryReader.open(directory)) {
			return reader.getIndexCommit().getUserData();
		}
	}
}
