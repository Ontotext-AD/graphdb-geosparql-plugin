package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.StoredFieldVisitor;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class LuceneEnvelopeIntersectionTest {
    @Rule
    public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    @Test
    public void separatedEnvelopesInOnePrefixCellDoNotLoadStoredFields() throws Exception {
        for (GeoSparqlConfig.PrefixTree tree : GeoSparqlConfig.PrefixTree.values()) {
            for (int precision : new int[]{1, 11}) {
                StoredReadCountingIndexer indexer = createIndexer(tree.name() + precision, tree, precision);
                index(indexer, List.of(
                        geometry("POLYGON((10.009 10.009,10.011 10.009,10.011 10.011,10.009 10.011,10.009 10.009))"),
                        geometry("POLYGON((10.012 10.012,10.013 10.012,10.013 10.013,10.012 10.013,10.012 10.012))")));

                List<Long> candidates = collect(indexer.getEnvelopeIntersections(geometry("POINT(10.01 10.01)")));
                assertEquals("Envelope-separated sources must not load stored fields", 1, indexer.storedReads.get());
                assertEquals(List.of(1L), candidates);
            }
        }
    }

    @Test
    public void closedEnvelopeIntersectionsRetainBoundarySources() throws Exception {
        StoredReadCountingIndexer indexer = createIndexer("boundaries", GeoSparqlConfig.PrefixTree.QUAD, 11);
        index(indexer, List.of(geometry("POINT(-1 -1)"), geometry("POINT(-1 1)"),
                geometry("POINT(1 -1)"), geometry("POINT(1 1)"), geometry("POINT(0 0)"),
                geometry("POINT(1.00001 1)")));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), collect(indexer.getEnvelopeIntersections(
                geometry("POLYGON((-1 -1,-1 1,1 1,1 -1,-1 -1))"))));
        assertEquals(5, indexer.storedReads.get());
    }

    @Test
    public void signedZeroCoordinatesRemainIntersectionCandidates() throws Exception {
        StoredReadCountingIndexer indexer = createIndexer("zero", GeoSparqlConfig.PrefixTree.QUAD, 11);
        List<IndexGeometry> zeros = List.of(geometry("POINT(0.0 0.0)"), geometry("POINT(-0.0 0.0)"),
                geometry("POINT(0.0 -0.0)"), geometry("POINT(-0.0 -0.0)"));
        index(indexer, zeros);

        for (IndexGeometry bound : zeros) {
            indexer.storedReads.set(0);
            assertEquals(List.of(1L, 2L, 3L, 4L), collect(indexer.getEnvelopeIntersections(bound)));
            assertEquals(4, indexer.storedReads.get());
        }
    }

    private StoredReadCountingIndexer createIndexer(String name, GeoSparqlConfig.PrefixTree tree, int precision)
            throws Exception {
        GeoSparqlPlugin parent = new GeoSparqlPlugin();
        GeoSparqlConfig config = new GeoSparqlConfig();
        config.setPrefixTree(tree);
        config.setPrecision(precision);
        config.updateCurrentSettings();
        parent.setConfig(config);
        parent.setLogger(LoggerFactory.getLogger(LuceneEnvelopeIntersectionTest.class));
        parent.setDataDir(Files.createDirectories(tmpFolder.getRoot().toPath().resolve(name)).toFile());
        StoredReadCountingIndexer indexer = new StoredReadCountingIndexer(parent);
        indexer.initialize();
        return indexer;
    }

    private static void index(StoredReadCountingIndexer indexer, List<IndexGeometry> geometries) throws Exception {
        indexer.begin();
        for (int i = 0; i < geometries.size(); i++) {
            indexer.indexGeometryList(i + 1L, id -> "geometry-" + id, List.of(geometries.get(i)));
        }
        indexer.commit();
        indexer.complete();
        indexer.storedReads.set(0);
    }

    private static List<Long> collect(CloseableIterator<CandidateEntity> iterator) throws Exception {
        try (iterator) {
            List<Long> ids = new ArrayList<>();
            while (iterator.hasNext()) {
                ids.add(iterator.next().entityId());
            }
            return ids;
        }
    }

    private static IndexGeometry geometry(String wkt) {
        return TestIndexGeometries.fromWkt(wkt);
    }

    private static final class StoredReadCountingIndexer extends LuceneGeoIndexer {
        private final AtomicInteger storedReads = new AtomicInteger();

        private StoredReadCountingIndexer(GeoSparqlPlugin parent) {
            super(parent);
        }

        @Override
        IndexReader openReader() throws IOException {
            return new StoredReadCountingReader((DirectoryReader) super.openReader(), storedReads);
        }
    }

    private static final class StoredReadCountingReader extends FilterDirectoryReader {
        private final AtomicInteger storedReads;

        private StoredReadCountingReader(DirectoryReader reader, AtomicInteger storedReads) throws IOException {
            super(reader, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader leaf) {
                    return new FilterLeafReader(leaf) {
                        @Override
                        public void document(int docId, StoredFieldVisitor visitor) throws IOException {
                            storedReads.incrementAndGet();
                            super.document(docId, visitor);
                        }

                        @Override
                        public CacheHelper getCoreCacheHelper() {
                            return in.getCoreCacheHelper();
                        }

                        @Override
                        public CacheHelper getReaderCacheHelper() {
                            return in.getReaderCacheHelper();
                        }
                    };
                }
            });
            this.storedReads = storedReads;
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader reader) throws IOException {
            return new StoredReadCountingReader(reader, storedReads);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }
}
