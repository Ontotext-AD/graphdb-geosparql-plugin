package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.sdk.PluginException;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.search.ConstantScoreScorer;
import org.apache.lucene.search.ConstantScoreWeight;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.locationtech.spatial4j.context.SpatialContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * @author Tsvetan Dimitrov <tsvetan.dimitrov@ontotext.com>
 * @since 14 Sep 2015.
 */
public class LuceneGeoIndexerTest {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneGeoIndexerTest.class);
    @Rule
    public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    private LuceneGeoIndexer luceneGeoIndexer;

    private IndexGeometry sampleGeometry;

    private IndexSearcher indexSearcher;

    private IndexReader indexReader;

    private SpatialContext testSpatialContext;

    private SpatialStrategy testSpatialStrategy;

    private Map<Long, Long> expected = new HashMap<>();

    @Before
    public void init() throws Exception {
        initializeTestSpatialStrategy();
		sampleGeometry = TestIndexGeometries.fromWkt("POINT(1 2)");
    }

    private void initializeExampleIndex() throws Exception {
		initIndexer();
		List<IndexGeometry> exampleGeometries = new ArrayList<>();
		readExampleGeometries(exampleGeometries);
		indexGeometries(exampleGeometries);

        FSDirectory dir = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(tmpFolder.getRoot().toPath()));
        indexReader = DirectoryReader.open(dir);
        indexSearcher = new IndexSearcher(indexReader);
    }

    private void initializeTestSpatialStrategy() {
        testSpatialContext = SpatialContext.GEO;
        testSpatialStrategy = new RecursivePrefixTreeStrategy(
                new QuadPrefixTree(testSpatialContext, 11), LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
    }

    private void indexGeometries(List<IndexGeometry> exampleGeometries) throws Exception {
        for (long i = 1, c = 1; i < exampleGeometries.size(); i++) {
            luceneGeoIndexer.indexGeometryList(i, (subject) -> "Subject " + subject, exampleGeometries);
            for (int k = 0; k < exampleGeometries.size(); k++) {
                expected.put(c , i);
                c++;
            }
        }
        luceneGeoIndexer.commit();
		luceneGeoIndexer.complete();
    }

    private void initIndexer() throws Exception {
        luceneGeoIndexer = createIndexer(tmpFolder.getRoot());
        luceneGeoIndexer.begin();
    }

    private LuceneGeoIndexer createIndexer(File dataDir) throws Exception {
        final GeoSparqlPlugin parent = new GeoSparqlPlugin();
        parent.setConfig(new GeoSparqlConfig());
        parent.setLogger(LOG);
        parent.setDataDir(dataDir);

        LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent);
        indexer.initialize();
        return indexer;
    }

    private CountingLuceneGeoIndexer createCountingIndexer(File dataDir) throws Exception {
        final GeoSparqlPlugin parent = new GeoSparqlPlugin();
        parent.setConfig(new GeoSparqlConfig());
        parent.setLogger(LOG);
        parent.setDataDir(dataDir);

        CountingLuceneGeoIndexer indexer = new CountingLuceneGeoIndexer(parent);
        indexer.initialize();
        return indexer;
    }


    private void readExampleGeometries(List<IndexGeometry> exampleGeometries) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                LuceneGeoIndexerTest.class.getResourceAsStream("/example_data.wkt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                exampleGeometries.add(TestIndexGeometries.fromSource(SourceGeometryLiteral.fromWkt(line)));
            }
        }
    }

    @After
    public void deleteIndex() throws IOException {
        if (indexReader != null) {
            indexReader.close();
        }
    }

    @Test
    public void testDocumentIds() throws Exception {
		initializeExampleIndex();
        TopDocs docs = indexSearcher.search(new MatchAllDocsQuery(), 100);

        assertEquals(docs.scoreDocs.length, 56);

        for (int i = 1; i <= docs.scoreDocs.length; i++) {
            assertEquals((long) expected.get((long) i), getDocId(docs.scoreDocs[i - 1]));
        }
    }

    @Test
    public void testDocumentsStoreSchemaV2EffectiveSourceCrsMetadata() throws Exception {
		initializeExampleIndex();
        TopDocs docs = indexSearcher.search(new MatchAllDocsQuery(), 1);
        Document doc = indexReader.document(docs.scoreDocs[0].doc);

        assertEquals(LuceneGeoDocumentSchema.SCHEMA_VERSION,
                doc.getField(LuceneGeoDocumentSchema.FIELD_SCHEMA_VERSION).numericValue().intValue());
        assertNotNull(doc.get(LuceneGeoDocumentSchema.FIELD_SOURCE_LEXICAL_FORM));
        assertEquals(GeoConstants.GEO_WKT_LITERAL.stringValue(),
                doc.get(LuceneGeoDocumentSchema.FIELD_SOURCE_DATATYPE));
        assertEquals(IndexGeometry.INDEX_CRS, doc.get(LuceneGeoDocumentSchema.FIELD_SOURCE_CRS));
        assertEquals(IndexGeometry.INDEX_CRS, doc.get(LuceneGeoDocumentSchema.FIELD_INDEX_CRS));
        assertEquals(LuceneGeoDocumentSchema.HAS_ENVELOPE_VALUE,
                doc.get(LuceneGeoDocumentSchema.FIELD_HAS_ENVELOPE));
        assertEquals(1, doc.getField(LuceneGeoDocumentSchema.FIELD_SOURCE_GEOMETRY)
                .binaryValue().bytes[doc.getField(LuceneGeoDocumentSchema.FIELD_SOURCE_GEOMETRY)
                .binaryValue().offset]);
        assertNotNull(doc.getField(LuceneGeoDocumentSchema.FIELD_SOURCE_COORDINATE_DIMENSION).numericValue());
        assertNotNull(doc.getField(LuceneGeoDocumentSchema.FIELD_SOURCE_SPATIAL_DIMENSION).numericValue());
        assertNotNull(doc.getField(LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION).numericValue());
        assertTrue(hasRequiredEntityIdDocValues(indexReader));
        for (LeafReaderContext leaf : indexReader.leaves()) {
            FieldInfo prefixField = leaf.reader().getFieldInfos().fieldInfo(
                    LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
            assertNotNull(prefixField);
            assertTrue(prefixField.getIndexOptions() != IndexOptions.NONE);
            assertEquals(DocValuesType.NUMERIC, leaf.reader().getFieldInfos().fieldInfo(
                    LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION).getDocValuesType());
            assertNull(leaf.reader().getFieldInfos().fieldInfo("geoData2"));
            assertNull(leaf.reader().getFieldInfos().fieldInfo("geoIndexBuildMode"));
        }
        assertCurrentSchemaCommitData(indexReader);
    }

    @Test
    public void testFullScanCandidateLookupScansAllDocuments() throws Exception {
		initializeExampleIndex();
        CloseableIterator<SourceGeometryLiteral> iterator =
                luceneGeoIndexer.getSourceGeometryLiteralsFor(0);

        int count = 0;
        try {
            while (iterator.hasNext()) {
                assertNotNull(iterator.next());
                count++;
            }
        } finally {
            iterator.close();
        }

        assertEquals(56, count);
    }

    @Test
    public void repeatedSourceWritesOneDocumentAndReturnsOneCandidateSource() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        Path entityCandidateDataDir = tmpFolder.getRoot().toPath().resolve("entity-candidates");
        Files.createDirectories(entityCandidateDataDir);

        LuceneGeoIndexer entityCandidateIndexer = createIndexer(entityCandidateDataDir.toFile());
        entityCandidateIndexer.begin();
        entityCandidateIndexer.indexGeometryList(1L, subject -> "Subject " + subject, List.of(geometry, geometry));
        entityCandidateIndexer.commit();
		entityCandidateIndexer.complete();

		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(entityCandidateDataDir));
			 IndexReader reader = DirectoryReader.open(directory)) {
			assertEquals(1, reader.numDocs());
		}

		CloseableIterator<CandidateEntity> iterator =
				entityCandidateIndexer.getEnvelopeIntersections(geometry);
		try {
			assertTrue(iterator.hasNext());
			CandidateEntity candidate = iterator.next();
			assertEquals(1L, candidate.entityId());
			assertEquals(List.of(geometry.sourceGeometryLiteral()), candidate.matchingSourceGeometryLiterals());
			assertFalse(iterator.hasNext());
		} finally {
			iterator.close();
		}
    }

    @Test
	public void collectionEnvelopeGeneratesCandidatesAndEmptySentinelRemainsFullScanVisible() throws Exception {
		IndexGeometry collectionEnvelope = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION(POINT(0 0),POINT(10 10))"));
		IndexGeometry emptySentinel = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION EMPTY"));
        Path dataDir = tmpFolder.getRoot().toPath().resolve("collection-candidates");
        Files.createDirectories(dataDir);

        LuceneGeoIndexer indexer = createIndexer(dataDir.toFile());
        indexer.begin();
		indexer.indexGeometryList(1L, subject -> "Subject " + subject, List.of(collectionEnvelope));
        indexer.indexGeometryList(2L, subject -> "Subject " + subject, List.of(emptySentinel));
        indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeIntersections(collectionEnvelope)));
        assertArrayEquals(new long[]{1L, 2L}, collectEntityIds(indexer.getAllEntities()));

        try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
             IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
			assertEquals(1, searcher.count(new PrefixQuery(new Term(
					LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX, ""))));
            assertNull(reader.leaves().get(0).reader().getFieldInfos().fieldInfo("geoData2"));
            assertNull(reader.leaves().get(0).reader().getFieldInfos().fieldInfo("geoIndexBuildMode"));
			assertEquals(2, reader.numDocs());
        }

        CloseableIterator<SourceGeometryLiteral> iterator =
                indexer.getSourceGeometryLiteralsFor(2L);
        try {
            assertTrue(iterator.hasNext());
            SourceGeometryLiteral sentinel = iterator.next();
            assertEquals("GEOMETRYCOLLECTION EMPTY", sentinel.lexicalForm());
            assertFalse(iterator.hasNext());
        } finally {
            iterator.close();
        }
    }


    @Test
	public void fullScanReturnsCompleteSourceFromCollectionEnvelopeDocument() throws Exception {
		IndexGeometry collectionEnvelope = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION(POINT(0 0),LINESTRING(0 0,1 1))"));
        Path dataDir = tmpFolder.getRoot().toPath().resolve("collection-source-reuse-entity-ids");
        Files.createDirectories(dataDir);

        LuceneGeoIndexer indexer = createIndexer(dataDir.toFile());
        indexer.begin();
		indexer.indexGeometryList(1L, subject -> "Subject " + subject, List.of(collectionEnvelope));
        indexer.commit();
		indexer.complete();

        CloseableIterator<CandidateEntity> entities = indexer.getAllEntities();
        try {
            assertTrue(entities.hasNext());
            CandidateEntity candidate = entities.next();
            assertEquals(1L, candidate.entityId());
            assertEquals(1, candidate.matchingSourceGeometryLiterals().size());
			assertEquals(collectionEnvelope.sourceGeometryLiteral(),
					candidate.matchingSourceGeometryLiterals().get(0));
            assertFalse(entities.hasNext());
        } finally {
            entities.close();
        }
    }

    @Test
	public void entityGeometryLookupReturnsOneCompleteCollectionSource() throws Exception {
		IndexGeometry collectionEnvelope = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION(POINT(0 0),LINESTRING(0 0,1 1))"));
        Path dataDir = tmpFolder.getRoot().toPath().resolve("collection-source-reuse-geometry-lookup");
        Files.createDirectories(dataDir);

        LuceneGeoIndexer indexer = createIndexer(dataDir.toFile());
        indexer.begin();
		indexer.indexGeometryList(1L, subject -> "Subject " + subject, List.of(collectionEnvelope));
        indexer.commit();
		indexer.complete();

		CloseableIterator<SourceGeometryLiteral> sources =
				indexer.getSourceGeometryLiteralsFor(1L);
		try {
			assertTrue(sources.hasNext());
			assertEquals(collectionEnvelope.sourceGeometryLiteral(), sources.next());
			assertFalse(sources.hasNext());
		} finally {
			sources.close();
		}
    }

    @Test
    public void entityTraversalRetainsDistinctSourceGeometryLiterals() throws Exception {
        IndexGeometry first = TestIndexGeometries.fromWkt("POINT(0 0)");
        IndexGeometry second = TestIndexGeometries.fromWkt("POINT(1 1)");
        Path dataDir = tmpFolder.getRoot().toPath().resolve("distinct-entity-sources");
        Files.createDirectories(dataDir);

        LuceneGeoIndexer indexer = createIndexer(dataDir.toFile());
        indexer.begin();
        indexer.indexGeometryList(1L, subject -> "Subject " + subject, List.of(first, second));
        indexer.commit();
		indexer.complete();

        CloseableIterator<SourceGeometryLiteral> geometries =
                indexer.getSourceGeometryLiteralsFor(1L);
        try {
            assertTrue(geometries.hasNext());
            SourceGeometryLiteral firstSource = geometries.next();
            assertTrue(geometries.hasNext());
            SourceGeometryLiteral secondSource = geometries.next();
            assertNotSame(firstSource, secondSource);
            assertEquals("POINT(0 0)", firstSource.lexicalForm());
            assertEquals("POINT(1 1)", secondSource.lexicalForm());
            assertFalse(geometries.hasNext());
        } finally {
            geometries.close();
        }
    }

    @Test
    public void sourceGeometryIteratorDiscardsSourceReuseAtEntityBoundary() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, geometry));
            writer.addDocument(currentSchemaDocument(2L, geometry));
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneSourceGeometryLiteralIterator iterator =
                    sourceGeometryLiteralIterator(new IndexSearcher(reader),
                    new MatchAllDocsQuery());
            try {
                SourceGeometryLiteral first = iterator.next();
                SourceGeometryLiteral second = iterator.next();
                assertNotSame(first, second);
                assertFalse(iterator.hasNext());
            } finally {
                iterator.close();
            }
        }
    }

	@Test
	public void sourceGeometryReadPreservesUnsupportedCrsDeploymentFailure() {
		JenaGeoSparqlException unsupportedCrs = new JenaGeoSparqlException(
				"Unsupported CRS. Configure Apache SIS CRS data.", null, true);

		RuntimeException classified = LuceneSourceGeometryLiteralIterator.classifyReadFailure(unsupportedCrs);

		assertSame(unsupportedCrs, classified);
		assertTrue(((JenaGeoSparqlException) classified).isUnsupportedCrs());
		assertTrue(classified.getMessage().contains("Apache SIS"));
	}

	@Test
	public void sourceGeometryReadTreatsOtherJenaFailuresAsSchemaMismatch() {
		JenaGeoSparqlException invalidStoredGeometry = new JenaGeoSparqlException(
				"Invalid stored source geometry");

		PluginException classified = (PluginException) LuceneSourceGeometryLiteralIterator.classifyReadFailure(
				invalidStoredGeometry);

		assertForceReindexMessage(classified);
		assertSame(invalidStoredGeometry, classified.getCause());
	}

	@Test
	public void sourceGeometryReadTreatsExplicitCorruptionAndTruncationAsSchemaMismatch() {
		CorruptIndexException corruptIndex = new CorruptIndexException("Corrupt index", "test");
		EOFException truncatedData = new EOFException("Truncated binary data");

		PluginException corruptClassification = (PluginException)
				LuceneSourceGeometryLiteralIterator.classifyReadFailure(corruptIndex);
		PluginException truncatedClassification = (PluginException)
				LuceneSourceGeometryLiteralIterator.classifyReadFailure(truncatedData);

		assertForceReindexMessage(corruptClassification);
		assertSame(corruptIndex, corruptClassification.getCause());
		assertForceReindexMessage(truncatedClassification);
		assertSame(truncatedData, truncatedClassification.getCause());
	}

	@Test
	public void sourceGeometryReadReportsOrdinaryIoFailureWithoutReindexGuidance() {
		IOException readFailure = new IOException("Filesystem read failed");

		PluginException classified =
				(PluginException) LuceneSourceGeometryLiteralIterator.classifyReadFailure(readFailure);

		assertEquals("Unable to read Lucene source geometry document.", classified.getMessage());
		assertFalse(classified.getMessage().contains("force-reindex"));
		assertFalse(classified.getMessage().contains("schema v2"));
		assertSame(readFailure, classified.getCause());
	}

	@Test
	public void sourceGeometryReadPreservesUnrelatedRuntimeFailure() {
		AlreadyClosedException closedReader = new AlreadyClosedException("Reader is closed");

		RuntimeException classified = LuceneSourceGeometryLiteralIterator.classifyReadFailure(closedReader);

		assertSame(closedReader, classified);
	}

    @Test
	public void fullScanUsesOneCandidateReader() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        Path entityCandidateDataDir = tmpFolder.getRoot().toPath().resolve("candidate-entity-geometries");
        Files.createDirectories(entityCandidateDataDir);

        CountingLuceneGeoIndexer entityCandidateIndexer = createCountingIndexer(entityCandidateDataDir.toFile());
        entityCandidateIndexer.begin();
        for (long subject = 1; subject <= 3; subject++) {
            entityCandidateIndexer.indexGeometryList(subject, id -> "Subject " + id, List.of(geometry));
        }
        entityCandidateIndexer.commit();
		entityCandidateIndexer.complete();

		CloseableIterator<CandidateEntity> iterator = entityCandidateIndexer.getAllEntities();

        try {
            for (int expectedEntityId = 1; expectedEntityId <= 3; expectedEntityId++) {
                assertTrue(iterator.hasNext());
                CandidateEntity candidate = iterator.next();
                assertEquals(expectedEntityId, candidate.entityId());
                assertEquals(1, candidate.matchingSourceGeometryLiterals().size());
            }
            assertEquals(1, entityCandidateIndexer.openReaderCount());
        } finally {
            iterator.close();
        }
    }

    @Test
    public void candidateEntityGeometryLookupDoesNotRunEntitySearchPerCandidate() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, geometry));
            writer.addDocument(currentSchemaDocument(2L, geometry));
            writer.addDocument(currentSchemaDocument(3L, geometry));
            writer.commit();

            try (IndexReader reader = DirectoryReader.open(directory)) {
                CountingIndexSearcher searcher = new CountingIndexSearcher(reader);
                CloseableIterator<CandidateEntity> iterator = new LuceneCandidateEntityIterator(searcher,
                        new MatchAllDocsQuery());

                try {
                    for (int expectedEntityId = 1; expectedEntityId <= 3; expectedEntityId++) {
                        assertTrue(iterator.hasNext());
                        CandidateEntity candidate = iterator.next();
                        assertEquals(expectedEntityId, candidate.entityId());
                        assertEquals(1, candidate.matchingSourceGeometryLiterals().size());
                    }
                    assertEquals(1, searcher.searchCount());
                } finally {
                    iterator.close();
                }
            }
        }
    }

    @Test
    public void candidateEntityGeometryLookupGroupsInterleavedDocumentsByEntity() throws Exception {
        IndexGeometry firstEntityGeometry1 = TestIndexGeometries.fromSource(
                SourceGeometryLiteral.fromWkt("POINT(0 0)"));
        IndexGeometry secondEntityGeometry = TestIndexGeometries.fromSource(
                SourceGeometryLiteral.fromWkt("POINT(1 1)"));
        IndexGeometry firstEntityGeometry2 = TestIndexGeometries.fromSource(
                SourceGeometryLiteral.fromWkt("POINT(2 2)"));

        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, firstEntityGeometry1));
            writer.addDocument(currentSchemaDocument(2L, secondEntityGeometry));
            writer.addDocument(currentSchemaDocument(1L, firstEntityGeometry2));
            writer.commit();

            try (IndexReader reader = DirectoryReader.open(directory)) {
                CloseableIterator<CandidateEntity> iterator = new LuceneCandidateEntityIterator(new IndexSearcher(reader),
                        new MatchAllDocsQuery());

                try {
                    assertTrue(iterator.hasNext());
                    CandidateEntity first = iterator.next();
                    assertEquals(1L, first.entityId());
                    assertEquals(2, first.matchingSourceGeometryLiterals().size());

                    assertTrue(iterator.hasNext());
                    CandidateEntity second = iterator.next();
                    assertEquals(2L, second.entityId());
                    assertEquals(1, second.matchingSourceGeometryLiterals().size());
                } finally {
                    iterator.close();
                }
            }
        }
    }

    @Test
    public void matchingEntityIdsPagesPastLuceneTotalHitsLowerBound() throws Exception {
        assertFullScanReturnsEntityIdCount(2500);
    }

    @Test
    public void matchingEntityIdsHandlesExactPageMultiple() throws Exception {
        assertFullScanReturnsEntityIdCount(2000);
    }

    @Test
    public void fullScanCandidateLookupPagesPastLuceneTotalHitsLowerBound() throws Exception {
        assertFullScanReturnsDocumentCount(2500);
    }

    @Test
    public void fullScanCandidateLookupHandlesExactPageMultiple() throws Exception {
        assertFullScanReturnsDocumentCount(2000);
    }

    @Test
    public void sourceGeometryIteratorReturnsValuesWithoutPriorHasNextAndThrowsWhenExhausted()
            throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, geometry));
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneSourceGeometryLiteralIterator iterator =
                    sourceGeometryLiteralIterator(new IndexSearcher(reader),
                    new MatchAllDocsQuery());
            try {
                assertTrue(iterator.hasNext());
                assertTrue(iterator.hasNext());
                assertEquals("POINT(0 0)", iterator.next().lexicalForm());
                assertFalse(iterator.hasNext());
                assertThrows(NoSuchElementException.class, iterator::next);
            } finally {
                iterator.close();
                iterator.close();
            }
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void candidateEntityIteratorGroupsValuesAcrossPageBoundary() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (int i = 0; i < 1001; i++) {
                writer.addDocument(currentSchemaDocument(1L, geometry));
            }
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneCandidateEntityIterator iterator = new LuceneCandidateEntityIterator(new IndexSearcher(reader),
                    new MatchAllDocsQuery());
            try {
                CandidateEntity candidate = iterator.next();
                assertEquals(1L, candidate.entityId());
                assertEquals(1, candidate.matchingSourceGeometryLiterals().size());
                assertEquals("POINT(0 0)", candidate.matchingSourceGeometryLiterals().get(0).lexicalForm());
                assertFalse(iterator.hasNext());
                assertThrows(NoSuchElementException.class, iterator::next);
            } finally {
                iterator.close();
                iterator.close();
            }
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorReadsOnlyLightweightMetadata() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt(
                "POLYGON((0 0,0 2,2 2,2 0,0 0))");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(7L, geometry));
            writer.commit();

            DirectoryReader delegate = DirectoryReader.open(directory);
            IndexReader reader = new NoStoredFieldsDirectoryReader(delegate);
            assertThrows(AssertionError.class, () -> reader.document(0));
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(
                            new IndexSearcher(reader), new MatchAllDocsQuery());
            try {
                EnvelopeDisjointCandidate candidate = iterator.next();
                assertEquals(7L, candidate.entityId());
                assertEquals(2, candidate.sourceTopologicalDimension());
                assertFalse(iterator.hasNext());
                assertEquals(0, reader.getRefCount());
                assertThrows(NoSuchElementException.class, iterator::next);
            } finally {
                iterator.close();
                iterator.close();
            }
            assertEquals(0, reader.getRefCount());
            assertEquals(0, delegate.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorStreamsAcrossLeavesWithoutSearchPages() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        IndexWriterConfig config = new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE);
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, config)) {
            for (long entityId = 1; entityId <= 3; entityId++) {
                writer.addDocument(currentSchemaDocument(entityId, geometry));
                writer.commit();
            }

            IndexReader reader = DirectoryReader.open(directory);
            assertEquals(3, reader.leaves().size());
            CountingIndexSearcher searcher = new CountingIndexSearcher(reader);
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(searcher, new MatchAllDocsQuery());
            Set<Long> entityIds = new HashSet<>();
            while (iterator.hasNext()) {
                entityIds.add(iterator.next().entityId());
            }

            assertEquals(Set.of(1L, 2L, 3L), entityIds);
            assertEquals(0, searcher.searchCount());
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorExcludesDeletedDocuments() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, geometry));
            writer.addDocument(currentSchemaDocument(2L, geometry));
            writer.addDocument(currentSchemaDocument(3L, geometry));
            writer.commit();
            writer.deleteDocuments(LuceneGeoDocumentSchema.entityIdQuery(2L));
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(
                            new IndexSearcher(reader), new MatchAllDocsQuery());
            Set<Long> entityIds = new HashSet<>();
            while (iterator.hasNext()) {
                entityIds.add(iterator.next().entityId());
            }

            assertEquals(Set.of(1L, 3L), entityIds);
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorAppliesTwoPhaseMatches() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (long entityId = 1; entityId <= 4; entityId++) {
                writer.addDocument(currentSchemaDocument(entityId, geometry));
            }
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(
                            new IndexSearcher(reader), new EvenEntityIdTwoPhaseQuery());
            Set<Long> entityIds = new HashSet<>();
            while (iterator.hasNext()) {
                entityIds.add(iterator.next().entityId());
            }

            assertEquals(Set.of(2L, 4L), entityIds);
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorClosesReaderOnEarlyClose() throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocument(currentSchemaDocument(1L, geometry));
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(
                            new IndexSearcher(reader), new MatchAllDocsQuery());
            assertTrue(iterator.hasNext());

            iterator.close();
            iterator.close();

            assertFalse(iterator.hasNext());
            assertEquals(0, reader.getRefCount());
        }
    }

    @Test
    public void envelopeDisjointCandidateIteratorRejectsMissingNumericDocValues() throws Exception {
        assertMissingEnvelopeDisjointDocValues(
                true, false, LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION);
        assertMissingEnvelopeDisjointDocValues(
                false, true, LuceneGeoDocumentSchema.FIELD_ID);
    }

    private void assertMissingEnvelopeDisjointDocValues(
            boolean includeEntityId, boolean includeSourceTopologicalDimension, String missingField)
            throws Exception {
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document document = new Document();
            if (includeEntityId) {
                document.add(new NumericDocValuesField(LuceneGeoDocumentSchema.FIELD_ID, 7L));
            }
            if (includeSourceTopologicalDimension) {
                document.add(new NumericDocValuesField(
                        LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION, 2));
            }
            writer.addDocument(document);
            writer.commit();

            IndexReader reader = DirectoryReader.open(directory);
            LuceneEnvelopeDisjointCandidateIterator iterator =
                    new LuceneEnvelopeDisjointCandidateIterator(
                            new IndexSearcher(reader), new MatchAllDocsQuery());

            PluginException failure = assertThrows(PluginException.class, iterator::hasNext);

            assertTrue(failure.getMessage().contains(missingField));
            assertTrue(failure.getMessage().contains("full GeoSPARQL reindex"));
            assertEquals(0, reader.getRefCount());
        }
    }

    private void assertFullScanReturnsDocumentCount(int documentCount) throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (long subject = 1; subject <= documentCount; subject++) {
                writer.addDocument(currentSchemaDocument(subject, geometry));
            }
            writer.commit();

            try (IndexReader reader = DirectoryReader.open(directory)) {
                CloseableIterator<SourceGeometryLiteral> iterator =
                        sourceGeometryLiteralIterator(new IndexSearcher(reader),
                        new MatchAllDocsQuery());

                int count = 0;
                try {
                    while (iterator.hasNext()) {
                        assertNotNull(iterator.next());
                        count++;
                    }
                } finally {
                    iterator.close();
                }

                assertEquals(documentCount, count);
            }
        }
    }

    private void assertFullScanReturnsEntityIdCount(int documentCount) throws Exception {
        IndexGeometry geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        try (Directory directory = new ByteBuffersDirectory();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            for (long subject = 1; subject <= documentCount; subject++) {
                writer.addDocument(currentSchemaDocument(subject, geometry));
            }
            writer.commit();

            try (IndexReader reader = DirectoryReader.open(directory)) {
                CloseableIterator<CandidateEntity> iterator = new LuceneCandidateEntityIterator(new IndexSearcher(reader),
                        new MatchAllDocsQuery());

                int count = 0;
                try {
                    while (iterator.hasNext()) {
                        assertEquals(count + 1, iterator.next().entityId());
                        count++;
                    }
                } finally {
                    iterator.close();
                }

                assertEquals(documentCount, count);
            }
        }
    }

    private long[] collectEntityIds(CloseableIterator<CandidateEntity> iterator) throws IOException {
        try {
            long[] ids = new long[16];
            int size = 0;
            while (iterator.hasNext()) {
                if (size == ids.length) {
                    long[] expanded = new long[ids.length * 2];
                    System.arraycopy(ids, 0, expanded, 0, ids.length);
                    ids = expanded;
                }
                ids[size++] = iterator.next().entityId();
            }

            long[] result = new long[size];
            System.arraycopy(ids, 0, result, 0, size);
            return result;
        } finally {
            iterator.close();
        }
    }

    private static final class CountingLuceneGeoIndexer extends LuceneGeoIndexer {
        private int openReaderCount;

        private CountingLuceneGeoIndexer(GeoSparqlPlugin parent) {
            super(parent);
        }

        @Override
        IndexReader openReader() throws IOException {
            openReaderCount++;
            return super.openReader();
        }

        private int openReaderCount() {
            return openReaderCount;
        }
    }

    private static final class EvenEntityIdTwoPhaseQuery extends Query {
        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
            return new ConstantScoreWeight(this, boost) {
                @Override
                public Scorer scorer(LeafReaderContext context) throws IOException {
                    NumericDocValues entityIds = DocValues.getNumeric(
                            context.reader(), LuceneGeoDocumentSchema.FIELD_ID);
                    TwoPhaseIterator twoPhase = new TwoPhaseIterator(
                            DocIdSetIterator.all(context.reader().maxDoc())) {
                        @Override
                        public boolean matches() throws IOException {
                            int documentId = approximation().docID();
                            return entityIds.advanceExact(documentId)
                                    && entityIds.longValue() % 2 == 0;
                        }

                        @Override
                        public float matchCost() {
                            return 1.0f;
                        }
                    };
                    return new ConstantScoreScorer(this, score(), scoreMode, twoPhase);
                }

                @Override
                public boolean isCacheable(LeafReaderContext context) {
                    return false;
                }
            };
        }

        @Override
        public String toString(String field) {
            return "evenEntityIdTwoPhase";
        }

        @Override
        public boolean equals(Object other) {
            return sameClassAs(other);
        }

        @Override
        public int hashCode() {
            return classHash();
        }
    }

    private static final class CountingIndexSearcher extends IndexSearcher {
        private int searchCount;

        private CountingIndexSearcher(IndexReader reader) {
            super(reader);
        }

        @Override
        public TopDocs search(Query query, int n) throws IOException {
            searchCount++;
            return super.search(query, n);
        }

        @Override
        public TopFieldDocs search(Query query, int n, Sort sort) throws IOException {
            searchCount++;
            return super.search(query, n, sort);
        }

        private int searchCount() {
            return searchCount;
        }
    }

    private static final class NoStoredFieldsDirectoryReader extends FilterDirectoryReader {
        private static final SubReaderWrapper NO_STORED_FIELDS = new SubReaderWrapper() {
            @Override
            public LeafReader wrap(LeafReader reader) {
                return new NoStoredFieldsLeafReader(reader);
            }
        };

        private NoStoredFieldsDirectoryReader(DirectoryReader reader) throws IOException {
            super(reader, NO_STORED_FIELDS);
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader reader) throws IOException {
            return new NoStoredFieldsDirectoryReader(reader);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }

    private static final class NoStoredFieldsLeafReader extends FilterLeafReader {
        private NoStoredFieldsLeafReader(LeafReader reader) {
            super(reader);
        }

        @Override
        public void document(int docId, StoredFieldVisitor visitor) {
            throw new AssertionError("Envelope-disjoint candidates must not load stored fields.");
        }

        @Override
        public CacheHelper getCoreCacheHelper() {
            return in.getCoreCacheHelper();
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }


    private Document currentSchemaDocument(long entityId, IndexGeometry geometry) {
        return LuceneGeoDocumentSchema.toDocument(entityId, geometry,
                testSpatialStrategy, testSpatialContext);
    }

    private LuceneSourceGeometryLiteralIterator sourceGeometryLiteralIterator(
            IndexSearcher searcher, Query query)
            throws IOException {
        return new LuceneSourceGeometryLiteralIterator(searcher, query);
    }

    private void assertForceReindexMessage(PluginException exception) {
        assertTrue(exception.getMessage().contains("required schema v2 layout"));
        assertTrue(exception.getMessage().contains("force-reindex"));
    }

    private void assertCurrentSchemaCommitData(IndexReader reader) throws IOException {
        assertEquals(LuceneGeoDocumentSchema.COMMIT_SCHEMA_VERSION_VALUE,
                ((DirectoryReader) reader).getIndexCommit().getUserData()
                        .get(LuceneGeoDocumentSchema.COMMIT_SCHEMA_VERSION_KEY));
        assertEquals(LuceneGeoDocumentSchema.COMMIT_SCHEMA_LAYOUT_VALUE,
                ((DirectoryReader) reader).getIndexCommit().getUserData()
                        .get(LuceneGeoDocumentSchema.COMMIT_SCHEMA_LAYOUT_KEY));
    }

    private boolean hasRequiredEntityIdDocValues(IndexReader reader) {
        for (LeafReaderContext context : reader.leaves()) {
            if (context.reader().numDocs() == 0) {
                continue;
            }
            FieldInfo entityIdField = context.reader().getFieldInfos().fieldInfo(
                    LuceneGeoDocumentSchema.FIELD_ID);
            if (entityIdField == null || entityIdField.getDocValuesType() != DocValuesType.NUMERIC) {
                return false;
            }
        }
        return true;
    }


    private long getDocId(ScoreDoc docs) throws IOException {
        return indexReader.document(docs.doc).getField(LuceneGeoDocumentSchema.FIELD_ID).numericValue().longValue();
    }
}
