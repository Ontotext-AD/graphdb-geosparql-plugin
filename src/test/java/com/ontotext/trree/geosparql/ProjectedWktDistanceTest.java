package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the issue report in
 * {@code docs/issues/GDB-10773-distance/GDB-10773-distance-345.md}.
 *
 * <p>The issue report and supplied RDF data are retained verbatim in {@code docs/issues/GDB-10773-distance/}. The
 * executable copy of the supplied data is {@code src/test/resources/issues/epsg-3006-distance-345.ttl}.
 */
public class ProjectedWktDistanceTest extends AbstractGeoSparqlPluginTest {
	/**
	 * Reproduces the ticket query and verifies that {@code geof:distance} evaluates the EPSG:3006 easting/northing
	 * deltas in their projected CRS, returning the 5-metre distance of the supplied 3–4–5 triangle.
	 */
	@Test
	public void projectedWktDistanceUsesSourceCrs() throws Exception {
		importData("issues/epsg-3006-distance-345.ttl", RDFFormat.TURTLE);

		String query = """
				PREFIX geo: <http://www.opengis.net/ont/geosparql#>
				PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
				PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
				PREFIX data: <http://www.triona.se/data#>

				SELECT * WHERE {
				  data:obj3 geo:hasGeometry/geo:asWKT ?geom1 .
				  data:obj4 geo:hasGeometry/geo:asWKT ?geom2 .
				  BIND(geof:distance(?geom1, ?geom2, uom:metre) AS ?distance)
				}
				""";

		try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			assertTrue(result.hasNext());
			BindingSet bindings = result.next();
			assertFalse(result.hasNext());
			assertTrue(bindings.hasBinding("distance"));
			assertEquals(5.0, ((Literal) bindings.getValue("distance")).doubleValue(), 0.01);
		}
	}
}
