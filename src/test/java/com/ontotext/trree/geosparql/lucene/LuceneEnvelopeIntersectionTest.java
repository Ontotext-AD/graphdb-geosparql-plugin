package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.StoredFieldVisitor;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

                List<Long> candidates = collect(indexer.getEnvelopeIntersections(geometry("POINT(10.01 10.01)"), false));
                assertEquals("Envelope-separated sources must not load stored fields", 1, indexer.storedReads.get());
                assertEquals(List.of(1L), candidates);
            }
        }
    }

    /**
     * A subject in another CRS than CRS84 is evaluated against each object transformed into its CRS, which can
     * relate sources whose index envelopes are apart, so objects stay candidates wherever the prefix cells of the
     * two envelopes meet. All three envelopes lie in one cell of the default grid. A CRS84 subject is evaluated in
     * CRS84 and selects objects of another CRS by index envelopes that meet its own.
     */
    @Test
    public void transformedSubjectsKeepObjectsInThePrefixCellsOfTheirIndexEnvelopes() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("prefix-cells", GeoSparqlConfig.PrefixTree.QUAD, 11);
        IndexGeometry transformedBesideThePoint = transformed(new Envelope(10.012, 10.013, 10.012, 10.013));
        index(indexer, List.of(
                geometry("POLYGON((10.009 10.009,10.011 10.009,10.011 10.011,10.009 10.011,10.009 10.009))"),
                geometry("POLYGON((10.012 10.012,10.013 10.012,10.013 10.013,10.012 10.013,10.012 10.012))"),
                transformedBesideThePoint));

        assertEquals(List.of(1L, 2L, 3L), collect(indexer.getEnvelopeIntersections(transformedBesideThePoint, false)));
        assertEquals(List.of(1L), collect(indexer.getEnvelopeIntersections(geometry("POINT(10.01 10.01)"), false)));
    }

    /**
     * A transformed bound is looked up by prefix cells. Index envelopes that overlap or only touch share a cell in
     * either tree at any precision, so each such source stays a candidate. The fixed envelopes touch the first bound
     * along grid lines of both trees: at an edge, at a corner and at a single point.
     */
    @Test
    public void transformedSourcesWithOverlappingEnvelopesRemainIntersectionCandidates() throws Exception {
        Random random = new Random(40);
        List<Envelope> envelopes = new ArrayList<>(List.of(
                new Envelope(11.25, 12, 1, 2),
                new Envelope(-3, 0, 3, 4),
                new Envelope(11.25, 13, 11.25, 13),
                new Envelope(11.25, 11.25, 11.25, 11.25),
                new Envelope(0, 0, -5, 0),
                new Envelope(11.2500001, 12, 1, 2)));
        for (int i = 0; i < 200; i++) {
            double minX = -20 + random.nextDouble() * 60;
            double minY = -20 + random.nextDouble() * 60;
            envelopes.add(new Envelope(minX, minX + random.nextDouble() * 10, minY, minY + random.nextDouble() * 10));
        }
        List<Envelope> bounds = new ArrayList<>(List.of(new Envelope(0, 11.25, 0, 11.25)));
        for (int i = 0; i < 20; i++) {
            Envelope source = envelopes.get(6 + i);
            bounds.add(new Envelope(source.getMaxX(), source.getMaxX() + random.nextDouble() * 5,
                    source.getMinY(), source.getMaxY()));
        }
        for (GeoSparqlConfig.PrefixTree tree : GeoSparqlConfig.PrefixTree.values()) {
            for (int precision : new int[]{1, 6, 11, 20}) {
                LuceneGeoIndexer indexer = createIndexer("overlap-" + tree.name() + precision, tree, precision);
                List<IndexGeometry> sources = new ArrayList<>();
                for (Envelope envelope : envelopes) {
                    sources.add(transformed(envelope));
                }
                index(indexer, sources);
                for (Envelope bound : bounds) {
                    List<Long> overlapping = new ArrayList<>();
                    for (int i = 0; i < envelopes.size(); i++) {
                        if (bound.intersects(envelopes.get(i))) {
                            overlapping.add(i + 1L);
                        }
                    }
                    List<Long> candidates = collect(indexer.getEnvelopeIntersections(boundInAnotherCrs(bound), false));
                    assertTrue(tree + " " + precision + " " + bound + " omits "
                            + overlapping.stream().filter(id -> !candidates.contains(id)).toList(),
                            candidates.containsAll(overlapping));
                }
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
                geometry("POLYGON((-1 -1,-1 1,1 1,1 -1,-1 -1))"), false)));
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
            assertEquals(List.of(1L, 2L, 3L, 4L), collect(indexer.getEnvelopeIntersections(bound, false)));
            assertEquals(4, indexer.storedReads.get());
        }
    }

    @Test
    public void envelopesMeetingAcrossTheAntimeridianRemainIntersectionCandidates() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("antimeridian", GeoSparqlConfig.PrefixTree.QUAD, 11);
        IndexGeometry east = transformed(new Envelope(170, 180, -10, 10));
        IndexGeometry west = transformed(new Envelope(-180, -170, -10, 10));
        index(indexer, List.of(east, west,
                transformed(new Envelope(-180, -170, 20, 30)),
                transformed(new Envelope(-179, -170, -10, 10)),
                transformed(new Envelope(-180 + 1e-7, -170, -10, 10))));

        assertEquals(List.of(1L, 2L, 5L), collect(indexer.getEnvelopeIntersections(
                boundInAnotherCrs(east.indexEnvelope()), false)));
        assertEquals(List.of(1L, 2L, 4L, 5L), collect(indexer.getEnvelopeIntersections(
                boundInAnotherCrs(west.indexEnvelope()), false)));
    }

    @Test
    public void envelopesReachingTheSamePolarCapRemainIntersectionCandidates() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("polar-cap", GeoSparqlConfig.PrefixTree.QUAD, 11);
        IndexGeometry south = transformed(new Envelope(10, 20, -90, -89.9995));
        IndexGeometry north = transformed(new Envelope(100, 110, 89.9995, 90));
        index(indexer, List.of(south,
                transformed(new Envelope(100, 110, -89.9999, -89.9998)),
                transformed(new Envelope(100, 110, -89.99, -89.98)),
                north));

        assertEquals(List.of(1L, 2L), collect(indexer.getEnvelopeIntersections(
                boundInAnotherCrs(south.indexEnvelope()), false)));
        assertEquals(List.of(4L), collect(indexer.getEnvelopeIntersections(
                boundInAnotherCrs(north.indexEnvelope()), false)));
    }

    private StoredReadCountingIndexer createIndexer(String name, GeoSparqlConfig.PrefixTree tree, int precision)
            throws Exception {
        StoredReadCountingIndexer indexer = new StoredReadCountingIndexer(plugin(name, tree, precision));
        indexer.initialize();
        return indexer;
    }

    private GeoSparqlPlugin plugin(String name, GeoSparqlConfig.PrefixTree tree, int precision) throws Exception {
        GeoSparqlPlugin parent = new GeoSparqlPlugin();
        GeoSparqlConfig config = new GeoSparqlConfig();
        config.setPrefixTree(tree);
        config.setPrecision(precision);
        config.updateCurrentSettings();
        parent.setConfig(config);
        parent.setLogger(LoggerFactory.getLogger(LuceneEnvelopeIntersectionTest.class));
        parent.setDataDir(Files.createDirectories(tmpFolder.getRoot().toPath().resolve(name)).toFile());
        return parent;
    }

    private static void index(LuceneGeoIndexer indexer, List<IndexGeometry> geometries) throws Exception {
        indexer.begin();
        for (int i = 0; i < geometries.size(); i++) {
            indexer.indexGeometryList(i + 1L, id -> "geometry-" + id, List.of(geometries.get(i)));
        }
        indexer.commit();
        indexer.complete();
        if (indexer instanceof StoredReadCountingIndexer counting) {
            counting.storedReads.set(0);
        }
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

    private static IndexGeometry transformed(Envelope indexEnvelope) {
        return IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(
                "<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(0 0)"), indexEnvelope);
    }

    /**
     * A bound with the given index envelope in another CRS than the {@link #transformed} sources, which are then
     * looked up by their CRS84 index envelopes.
     */
    private static IndexGeometry boundInAnotherCrs(Envelope indexEnvelope) {
        return IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(
                "<http://www.opengis.net/def/crs/EPSG/0/32633> POINT(500000 0)"), indexEnvelope);
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
