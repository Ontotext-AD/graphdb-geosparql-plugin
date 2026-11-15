package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Shape;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeCrs84PrefixCoverTest {
    private static final String TRIANGLE = "POLYGON((0 0,8 0,0 8,0 0))";
    private static final String EPSG4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";

    @Rule
    public TemporaryLocalFolder folder = new TemporaryLocalFolder();

    @Before
    public void initialize() {
        JenaGeometryAdapter.initialize();
    }

    private record Setting(GeoSparqlConfig.PrefixTree tree, int precision) { }

    private List<Setting> settings() {
        return List.of(new Setting(GeoSparqlConfig.PrefixTree.QUAD, 11),
                new Setting(GeoSparqlConfig.PrefixTree.QUAD, QuadPrefixTree.MAX_LEVELS_POSSIBLE),
                new Setting(GeoSparqlConfig.PrefixTree.GEOHASH, 11),
                new Setting(GeoSparqlConfig.PrefixTree.GEOHASH, GeohashPrefixTree.getMaxLevelsPossible()));
    }

    private LuceneGeoIndexer indexer(Setting setting, String... sources) throws Exception {
        var path = Files.createTempDirectory(folder.getRoot().toPath(), "index-");
        var config = new GeoSparqlConfig();
        config.setPrefixTree(setting.tree());
        config.setPrecision(setting.precision());
        config.updateCurrentSettings();
        var parent = new GeoSparqlPlugin();
        parent.setConfig(config);
        parent.setDataDir(path.toFile());
        parent.setLogger(LoggerFactory.getLogger(NativeCrs84PrefixCoverTest.class));
        var result = new LuceneGeoIndexer(parent);
        result.initialize();
        result.begin();
        for (int i = 0; i < sources.length; i++) {
            result.indexGeometryList(i + 1L, Object::toString, List.of(geometry(sources[i])));
        }
        result.commit();
        result.complete();
        return result;
    }

    private IndexGeometry geometry(String wkt) {
        return IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt));
    }

    private Set<Long> candidates(LuceneGeoIndexer indexer, String wkt) throws Exception {
        return candidates(indexer, geometry(wkt));
    }

    private Set<Long> candidates(LuceneGeoIndexer indexer, IndexGeometry bound) throws Exception {
        var result = new LinkedHashSet<Long>();
        try (var iterator = indexer.getEnvelopeIntersections(bound)) {
            while (iterator.hasNext()) {
                result.add(iterator.next().entityId());
            }
        }
        return result;
    }

    @Test
    public void convexHullExcludesAnEmptyEnvelopeCorner() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, TRIANGLE);
            assertEquals(setting.toString(), Set.of(), candidates(indexer, "POINT(7 7)"));
        }
    }

    @Test
    public void closedBoundariesAndInteriorRemainCandidates() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, TRIANGLE, "POINT(180 90)", "POINT(-180 -90)");
            for (var point : List.of("POINT(0 0)", "POINT(-0.0 0.0)", "POINT(4 4)", "POINT(1 1)", "POINT(8 0)")) {
                assertTrue(setting + " " + point, candidates(indexer, point).contains(1L));
            }
            assertEquals(Set.of(2L), candidates(indexer, "POINT(180 90)"));
            assertEquals(Set.of(3L), candidates(indexer, "POINT(-180 -90)"));
        }
    }

    @Test
    public void wideLongitudePolygonsKeepPlanarInteriors() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, "POLYGON((-170 -1,170 -1,170 1,-170 -1))");
            assertEquals(setting.toString(), Set.of(1L), candidates(indexer, "POINT(0 -0.5)"));
        }
    }

    @Test
    public void mixedCrsBoundsKeepEnvelopeCandidates() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, TRIANGLE);
            assertEquals(Set.of(1L), candidates(indexer, "<" + EPSG4326 + "> POINT(7 7)"));
        }
    }

    /**
     * Exact evaluation can relate a mixed-CRS pair whose index envelopes are apart, so a native CRS84 source stays a
     * candidate for a transformed bound in every prefix cell of its index envelope. The bound lies in the default
     * grid cell holding the triangle's empty envelope corner (8 8).
     */
    @Test
    public void transformedBoundsFindNativeSourcesInThePrefixCellsOfTheirEnvelopes() throws Exception {
        var indexer = indexer(new Setting(GeoSparqlConfig.PrefixTree.QUAD, 11), TRIANGLE);
        var bound = IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(
                "<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(0 0)"), new Envelope(8.001, 8.002, 8.001, 8.002));

        assertEquals(Set.of(1L), candidates(indexer, bound));
    }

    @Test
    public void transformedSourcesKeepEnvelopeCandidates() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, "<" + EPSG4326 + "> " + TRIANGLE);
            assertEquals(Set.of(1L), candidates(indexer, "POINT(7 7)"));
        }
    }

    @Test
    public void disjointUncertainPhaseKeepsEnvelopeCandidates() throws Exception {
        for (var setting : settings()) {
            var indexer = indexer(setting, TRIANGLE);
            var bound = geometry("POINT(7 7)");
            try (var iterator = indexer.getEnvelopeDisjointUncertainCandidates(bound)) {
                assertTrue(setting.toString(), iterator.hasNext());
                assertEquals(1L, iterator.next().entityId());
            }
        }
    }

    @Test
    public void sourcesWhoseHullCellsCannotBeGeneratedKeepOnlyTheirEnvelopeCells() {
        var context = SpatialContext.GEO;
        var grid = new QuadPrefixTree(context, 11);
        var strategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
        var failingHullStrategy = new RecursivePrefixTreeStrategy(
                grid, LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX) {
            @Override
            public Field[] createIndexableFields(Shape shape) {
                throw new IllegalStateException("Cells cannot be generated for " + shape);
            }
        };

        var document = LuceneGeoDocumentSchema.toDocument(
                1L, geometry(TRIANGLE), strategy, failingHullStrategy, context);

        assertNotNull(document.getField(LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX));
        assertNull(document.getField(LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX));
        assertNull(document.getField(LuceneGeoDocumentSchema.FIELD_HAS_NATIVE_HULL_CELLS));
    }

    /**
     * A hull that fills its envelope separates no bound that the numeric envelope ranges admit, so a point, an
     * axis-parallel line and a rectangle carry no hull cells.
     */
    @Test
    public void onlySourcesWhoseHullLeavesPartOfTheirEnvelopeEmptyCarryHullCells() {
        var context = SpatialContext.GEO;
        var grid = new QuadPrefixTree(context, 11);
        var strategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
        var hullStrategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX);
        for (var wkt : List.of("POINT(1 1)", "MULTIPOINT((1 1),(1 1))", "LINESTRING(0 1,8 1)",
                "LINESTRING(1 0,1 4,1 8)", "POLYGON((0 0,8 0,8 8,0 8,0 0))")) {
            var document = LuceneGeoDocumentSchema.toDocument(1L, geometry(wkt), strategy, hullStrategy, context);
            assertNotNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX));
            assertNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX));
            assertNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_HAS_NATIVE_HULL_CELLS));
        }
        for (var wkt : List.of(TRIANGLE, "LINESTRING(0 0,8 8)", "MULTIPOINT((0 0),(8 8))")) {
            var document = LuceneGeoDocumentSchema.toDocument(1L, geometry(wkt), strategy, hullStrategy, context);
            assertNotNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX));
            assertNotNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX));
            assertNotNull(wkt, document.getField(LuceneGeoDocumentSchema.FIELD_HAS_NATIVE_HULL_CELLS));
        }
    }

    /** A source indexed without hull cells is found through its envelope beside a source that carries them. */
    @Test
    public void sourcesWithoutHullCellsRemainEnvelopeCandidates() throws Exception {
        var indexer = indexer(new Setting(GeoSparqlConfig.PrefixTree.QUAD, 11), TRIANGLE);
        var context = SpatialContext.GEO;
        var grid = new QuadPrefixTree(context, 11);
        var strategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
        var hullStrategy = new RecursivePrefixTreeStrategy(grid, LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX);
        var source = geometry(TRIANGLE);
        var withoutHullCells = LuceneGeoDocumentSchema.toDocument(1L, source, strategy, hullStrategy, context);
        withoutHullCells.removeFields(LuceneGeoDocumentSchema.FIELD_NATIVE_HULL_PREFIX);
        withoutHullCells.removeFields(LuceneGeoDocumentSchema.FIELD_HAS_NATIVE_HULL_CELLS);
        try (var reader = (DirectoryReader) indexer.openReader()) {
            assertEquals(1, new IndexSearcher(reader).count(LuceneGeoDocumentSchema.envelopeIntersectsQuery(
                    new Envelope(7, 7, 7, 7))));
            try (var writer = new IndexWriter(reader.directory(), new IndexWriterConfig())) {
                writer.deleteDocuments(LuceneGeoDocumentSchema.entityIdQuery(1L));
                writer.addDocument(withoutHullCells);
                writer.addDocument(LuceneGeoDocumentSchema.toDocument(2L, source, strategy, hullStrategy, context));
            }
        }
        assertEquals(Set.of(1L), candidates(indexer, "POINT(7 7)"));

        indexer.begin();
        indexer.indexGeometryList(1L, Object::toString, List.of(source));
        indexer.commit();
        indexer.complete();
        assertEquals(Set.of(), candidates(indexer, "POINT(7 7)"));
    }
}
