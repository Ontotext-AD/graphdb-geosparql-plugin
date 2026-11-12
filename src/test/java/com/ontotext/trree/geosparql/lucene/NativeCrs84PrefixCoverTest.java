package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.spatial4j.context.SpatialContext;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeCrs84PrefixCoverTest {
    private static final String TRIANGLE = "POLYGON((0 0,8 0,0 8,0 0))";
    private static final String EPSG4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";

    @Rule public TemporaryLocalFolder folder = new TemporaryLocalFolder();

    @Before public void initialize() { JenaGeometryAdapter.initialize(); }

    private record Setting(GeoSparqlConfig.PrefixTree tree, int precision) { }

    private List<Setting> settings() {
        return List.of(new Setting(GeoSparqlConfig.PrefixTree.QUAD,11),
                new Setting(GeoSparqlConfig.PrefixTree.QUAD,QuadPrefixTree.MAX_LEVELS_POSSIBLE),
                new Setting(GeoSparqlConfig.PrefixTree.GEOHASH,11),
                new Setting(GeoSparqlConfig.PrefixTree.GEOHASH,GeohashPrefixTree.getMaxLevelsPossible()));
    }

    private LuceneGeoIndexer indexer(Setting setting, String... sources) throws Exception {
        var path=Files.createTempDirectory(folder.getRoot().toPath(),"index-");
        var config=new GeoSparqlConfig();
        config.setPrefixTree(setting.tree()); config.setPrecision(setting.precision());
        config.updateCurrentSettings();
        var parent=new GeoSparqlPlugin();
        parent.setConfig(config);parent.setDataDir(path.toFile());
        parent.setLogger(LoggerFactory.getLogger(NativeCrs84PrefixCoverTest.class));
        var result=new LuceneGeoIndexer(parent);
        result.initialize(); result.begin();
        for(int i=0;i<sources.length;i++) result.indexGeometryList(i+1L,Object::toString,
                List.of(IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(sources[i]))));
        result.commit();result.complete();return result;
    }

    private Set<Long> candidates(LuceneGeoIndexer indexer,String wkt) throws Exception {
        var result=new LinkedHashSet<Long>();
        try(var iterator=indexer.getEnvelopeIntersections(IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt)))) {
            while(iterator.hasNext())result.add(iterator.next().entityId());
        }
        return result;
    }

    @Test public void convexHullExcludesAnEmptyEnvelopeCorner() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,TRIANGLE);
            assertEquals(setting.toString(),Set.of(),candidates(indexer,"POINT(7 7)"));
        }
    }

    @Test public void closedBoundariesAndInteriorRemainCandidates() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,TRIANGLE,"POINT(180 90)","POINT(-180 -90)");
            for(var point:List.of("POINT(0 0)","POINT(-0.0 0.0)","POINT(4 4)","POINT(1 1)","POINT(8 0)"))
                assertTrue(setting+" "+point,candidates(indexer,point).contains(1L));
            assertEquals(Set.of(2L),candidates(indexer,"POINT(180 90)"));
            assertEquals(Set.of(3L),candidates(indexer,"POINT(-180 -90)"));
        }
    }

    @Test public void wideLongitudePolygonsKeepPlanarInteriors() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,"POLYGON((-170 -1,170 -1,170 1,-170 -1))");
            assertEquals(setting.toString(),Set.of(1L),candidates(indexer,"POINT(0 -0.5)"));
        }
    }

    @Test public void mixedCrsBoundsKeepEnvelopeCandidates() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,TRIANGLE);
            assertEquals(Set.of(1L),candidates(indexer,"<"+EPSG4326+"> POINT(7 7)"));
        }
    }

    @Test public void transformedSourcesKeepEnvelopeCandidates() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,"<"+EPSG4326+"> "+TRIANGLE);
            assertEquals(Set.of(1L),candidates(indexer,"POINT(7 7)"));
        }
    }

    @Test public void disjointUncertainPhaseKeepsEnvelopeCandidates() throws Exception {
        for(var setting:settings()) {
            var indexer=indexer(setting,TRIANGLE);
            var bound=IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt("POINT(7 7)"));
            try(var iterator=indexer.getEnvelopeDisjointUncertainCandidates(bound)) {
                assertTrue(setting.toString(),iterator.hasNext());
                assertEquals(1L,iterator.next().entityId());
            }
        }
    }

    @Test public void envelopePrefixDocumentsRemainConservativeCandidates() throws Exception {
        var setting=new Setting(GeoSparqlConfig.PrefixTree.QUAD,11);
        var indexer=indexer(setting,TRIANGLE);
        var field=LuceneGeoIndexer.class.getDeclaredField("directory");field.setAccessible(true);
        var directory=(org.apache.lucene.store.Directory)field.get(indexer);
        var context=SpatialContext.GEO;
        var strategy=new RecursivePrefixTreeStrategy(new QuadPrefixTree(context,11),LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
        var source=IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(TRIANGLE));
        var document=LuceneGeoDocumentSchema.toDocument(1L,source,strategy,context);
        try(var reader=org.apache.lucene.index.DirectoryReader.open(directory)) {
            var searcher=new org.apache.lucene.search.IndexSearcher(reader);
            assertEquals(1,searcher.count(LuceneGeoDocumentSchema.envelopeIntersectsQuery(
                    new org.locationtech.jts.geom.Envelope(7,7,7,7))));
        }
        document.removeFields(LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
        for(var prefix:strategy.createIndexableFields(context.makeRectangle(0,8,0,8)))document.add(prefix);
        try(var writer=new IndexWriter(directory,new IndexWriterConfig())) {
            writer.deleteDocuments(LuceneGeoDocumentSchema.entityIdQuery(1L));
            writer.addDocument(document);
            writer.addDocument(LuceneGeoDocumentSchema.toDocument(2L,source,strategy,context));
        }
        assertEquals(Set.of(1L),candidates(indexer,"POINT(7 7)"));
        indexer.begin();
        indexer.indexGeometryList(1L,Object::toString,List.of(source));
        indexer.commit();indexer.complete();
        assertEquals(Set.of(),candidates(indexer,"POINT(7 7)"));
    }
}
