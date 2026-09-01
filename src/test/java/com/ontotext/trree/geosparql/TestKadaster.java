package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Tests some simple queries with Kadaster data
 */
public class TestKadaster extends AbstractGeoSparqlPluginTest {
    @Before
    public void setupConn() throws Exception {
        importData("kadaster-data.ttl", RDFFormat.TURTLE);
        enablePlugin();
    }

    @Test
    public void testVerySpecific() throws Exception {
        List<Value> result = executeSparqlQueryWithResult("PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
                "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n" +
                "SELECT ?f\n" +
                "WHERE {\n" +
                "    ?f geo:sfContains \"POLYGON ((5.458713474968683 52.524611984480096,5.458637011977173 52.524609190664023,5.458637214924984 52.524607132456225,5.458578935662133 52.524605001674729,5.458581008700381 52.524584006157092,5.458522758969258 52.524581893304763,5.458522961907719 52.524579826109878,5.45849061731855 52.524578650283736,5.458347292731427 52.524587723773628,5.458352380416623 52.524535603524541,5.458719649697075 52.524549006933817,5.458713474968683 52.524611984480096))\"\n" +
                "}", "f");
        Assert.assertEquals(1, result.size());
    }

    @Test
    public void testVeryBroad() throws Exception {
        // This test is important as it verifies that paging through the Lucene results works
        List<Value> result = executeSparqlQueryWithResult("PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
                "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n" +
                "SELECT ?f\n" +
                "WHERE {\n" +
	                "    ?f geo:sfWithin \"POLYGON ((0 0, 0 89, 100 89, 100 0, 0 0))\"\n" +
                "}", "f");
        Assert.assertEquals(69230, result.size());
    }
}
