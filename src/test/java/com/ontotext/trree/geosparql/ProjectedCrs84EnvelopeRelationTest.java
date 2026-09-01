package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A straight projected segment can curve outside the CRS84 envelope of its transformed vertices.
 * Index envelopes must stay conservative so Lucene cannot omit a real non-disjoint match.
 */
public class ProjectedCrs84EnvelopeRelationTest extends AbstractGeoSparqlPluginTest {
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX ex: <http://example.com/projected-envelope/>\n";
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";
	private static final String LINE_WKT =
			"<" + EPSG_32634 + "> LINESTRING(200000 7000000, 800000 7000000)";
	private static final String POINT_WKT =
			"<" + EPSG_32634 + "> POINT(500000 7000000)";

	@Test
	public void projectedLineAndOnLinePointAgreeForDirectAndIndexedRelations() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:line a geo:Feature ; geo:hasDefaultGeometry ex:lineGeom .\n"
				+ "  ex:lineGeom a geo:Geometry ; geo:asWKT \"" + LINE_WKT + "\"^^geo:wktLiteral .\n"
				+ "  ex:point a geo:Feature ; geo:hasDefaultGeometry ex:pointGeom .\n"
				+ "  ex:pointGeom a geo:Geometry ; geo:asWKT \"" + POINT_WKT + "\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertTrue(filterAsk("geof:sfIntersects"));
		assertTrue(propertyAsk("geo:sfIntersects"));
		assertFalse(filterAsk("geof:sfDisjoint"));
		assertFalse(propertyAsk("geo:sfDisjoint"));
		assertFalse(filterAsk("geof:ehDisjoint"));
		assertFalse(propertyAsk("geo:ehDisjoint"));
	}

	private boolean filterAsk(String function) {
		return connection.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES
				+ "ASK {\n"
				+ "  ex:lineGeom geo:asWKT ?lineWkt .\n"
				+ "  ex:pointGeom geo:asWKT ?pointWkt .\n"
				+ "  FILTER(" + function + "(?lineWkt, ?pointWkt))\n"
				+ "}").evaluate();
	}

	private boolean propertyAsk(String predicate) {
		return connection.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES
				+ "ASK { ex:line " + predicate + " ex:point }").evaluate();
	}
}
