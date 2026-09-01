package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.common.io.FileUtil;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * @author Tsvetan Dimitrov <tsvetan.dimitrov@ontotext.com>
 * @since 14 Sep 2015.
 */
public class TestReindex extends AbstractGeoSparqlPluginTest {

    @Before
    public void setupConn() throws Exception {
        importData("simple_features_geometries.rdf", RDFFormat.RDFXML);
        importData("geosparql-example.rdf", RDFFormat.RDFXML);

        enablePlugin();
    }

    @Test
    public void queryFailsAfterIndexIsDeleted() throws Exception {
        //test with select query
        assertSparqlSelectExample5Results();

        //delete index
        FileUtil.deleteDir(getGeoSparqlStorageDir());
        assertFalse(getGeoSparqlStorageDir().exists());

        // select query should fail
        assertThrows(RuntimeException.class, this::assertSparqlSelectExample5Results);
    }

    @Test
    public void forceReindexPredicateRebuildsDeletedIndex() throws Exception {
        //test with select query
        assertSparqlSelectExample5Results();

        //delete index
        FileUtil.deleteDir(getGeoSparqlStorageDir());
        assertFalse(getGeoSparqlStorageDir().exists());

        //reindex through SPARQL-Update query
        executeSparqlUpdateQueryFromFile("testForceReindexPredicate");

        //test again with select query
        assertSparqlSelectExample5Results();

        //test if index exists
        final File indexDir = GeoSparqlConfig.resolveIndexPath(getGeoSparqlStorageDir().toPath()).toFile();
        assertTrue(indexDir.isDirectory());

    }

    @Test
    public void repositoryRestartDoesNotRebuildDeletedIndex() throws Exception {
        FileUtil.deleteDir(getGeoSparqlStorageDir());
        assertTrue(!getGeoSparqlStorageDir().exists());

        restartRepository();

        final File indexDir = GeoSparqlConfig.resolveIndexPath(getGeoSparqlStorageDir().toPath()).toFile();
        assertFalse(indexDir.exists());
    }

    @Test
    public void testCurrentV2IndexRemainsQueryableAfterRepositoryRestart() throws Exception {
        final File indexDir = GeoSparqlConfig.resolveIndexPath(getGeoSparqlStorageDir().toPath()).toFile();
        assertTrue(indexDir.isDirectory());
        assertSparqlSelectExample5Results();

        restartRepository();

        assertTrue(indexDir.isDirectory());
        assertSparqlSelectExample5Results();
    }

    private void assertSparqlSelectExample5Results() throws Exception {
        final List<Value> values1 = executeSparqlQueryWithResultFromFile("example5", "f");
        assertEquals(values1.size(), 2);
        assertTrue(values1.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#D")));
        assertTrue(values1.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#DExactGeom")));
    }
}
