package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.util.List;

public class TestGDB354 extends AbstractGeoSparqlPluginTest {
    @Test
    public void testGMLWithNamespace() throws Exception {
        List<Value> result = executeSparqlQueryWithResult("PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>\n" +
                "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n" +
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
                "select *\n" +
                "{\n" +
                "BIND (geof:distance('''Point(1 1)'''^^geo:wktLiteral, '''Point(2 2)'''^^geo:wktLiteral, uom:metre) as ?distWkt) .\n" +
                "BIND (geof:distance('''<gml:Point xmlns:gml=\"http://www.opengis.net/gml\"><gml:pos>1 1</gml:pos></gml:Point>'''^^geo:gmlLiteral, " +
                "'''<gml:Point xmlns:gml=\"http://www.opengis.net/gml\"><gml:pos>2 2</gml:pos></gml:Point>'''^^geo:gmlLiteral, uom:metre) as ?distGml) .\n" +
                "}", "distGml");

        Assert.assertEquals(1, result.size());
        Assert.assertNotNull(result.get(0));
        Assert.assertEquals(157225.64850128975d, ((Literal) result.get(0)).doubleValue(), 0.00001);
    }

    @Ignore
    @Test
    public void testGMLWithoutNamespace() throws Exception {
        // This tests without specifying the gml: namespace in the XML serialisation. This is non-standard
        // and currently not supported.

        List<Value> result = executeSparqlQueryWithResult("PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>\n" +
                "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n" +
                "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
                "select *\n" +
                "WHERE\n" +
                "{\n" +
                "BIND (geof:distance('''Point(1 1)'''^^geo:wktLiteral, '''Point(2 2)'''^^geo:wktLiteral, uom:metre) as ?distWkt) .\n" +
                "BIND (geof:distance('''<gml:Point><gml:pos>1 1</gml:pos></gml:Point>'''^^geo:gmlLiteral, " +
                "'''<gml:Point><gml:pos>2 2</gml:pos></gml:Point>'''^^geo:gmlLiteral, uom:metre) as ?distGml) .\n" +
                "}", "distGml");

        Assert.assertEquals(1, result.size());
        Assert.assertNotNull(result.get(0));
        Assert.assertEquals(156876.14940214372d, ((Literal) result.get(0)).doubleValue(), 0.00001);
    }

}
