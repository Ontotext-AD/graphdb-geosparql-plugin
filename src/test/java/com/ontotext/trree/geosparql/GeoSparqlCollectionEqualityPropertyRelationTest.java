package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for <a href="https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/4">#4</a>.
 *
 * <p>Index-backed {@code geo:sfEquals} and {@code geo:ehEquals} must agree with {@code geof:relate}
 * using the GeoSPARQL DE-9IM equality pattern {@code TFFFTFFFT} for generic collections.
 */
public class GeoSparqlCollectionEqualityPropertyRelationTest extends AbstractGeoSparqlPluginTest {
	private static final String EXAMPLE_NAMESPACE = "http://example.com/collection-equality/";
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX ex: <" + EXAMPLE_NAMESPACE + ">\n";
	private static final String EQUALS_PATTERN = "TFFFTFFFT";
	private static final Map<String, String> COLLECTION_FEATURES = collectionFeatures();

	@Before
	public void insertFixtureAndEnablePlugin() throws Exception {
		StringBuilder insert = new StringBuilder(PREFIXES + "INSERT DATA {\n");
		for (Map.Entry<String, String> feature : COLLECTION_FEATURES.entrySet()) {
			insert.append(feature(feature.getKey(), feature.getKey() + "Geom", feature.getValue()));
			insert.append(feature(feature.getKey() + "Copy", feature.getKey() + "CopyGeom", feature.getValue()));
		}
		insert.append("}");
		executeSparqlUpdateQuery(insert.toString());
		enablePlugin();
	}

	@Test
	public void sfEqualsAgreesWithRelateForGenericCollections() {
		assertPropertyEqualsAgreesWithRelate("sfEquals");
	}

	@Test
	public void ehEqualsAgreesWithRelateForGenericCollections() {
		assertPropertyEqualsAgreesWithRelate("ehEquals");
	}

	private void assertPropertyEqualsAgreesWithRelate(String relation) {
		for (String feature : COLLECTION_FEATURES.keySet()) {
			boolean propertyResult = ask("ex:" + feature + " geo:" + relation + " ex:" + feature + "Copy");
			boolean relateResult = ask(""
					+ "ex:" + feature + " geo:hasDefaultGeometry/geo:asWKT ?left .\n"
					+ "  ex:" + feature + "Copy geo:hasDefaultGeometry/geo:asWKT ?right .\n"
					+ "  FILTER(geof:relate(?left, ?right, \"" + EQUALS_PATTERN + "\"))");
			assertEquals(relation + " must match geof:relate " + EQUALS_PATTERN + " for " + feature,
					relateResult, propertyResult);
		}
	}

	private boolean ask(String pattern) {
		return connection.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES + "ASK {\n  " + pattern + "\n}").evaluate();
	}

	private static Map<String, String> collectionFeatures() {
		Map<String, String> features = new LinkedHashMap<>();
		features.put("pointCollection", "GEOMETRYCOLLECTION(POINT(0 0))");
		features.put("closedLineCollection", "GEOMETRYCOLLECTION(LINESTRING(0 0,1 0,1 1,0 0))");
		features.put("polygonCollection", "GEOMETRYCOLLECTION(POLYGON((0 0,0 1,1 1,1 0,0 0)))");
		features.put("heterogeneousCollection", "GEOMETRYCOLLECTION(POINT(0 0),LINESTRING(1 1,2 2))");
		features.put("nestedCollection",
				"GEOMETRYCOLLECTION(GEOMETRYCOLLECTION(POINT(0 0)),LINESTRING(1 1,2 2))");
		features.put("emptyCollection", "GEOMETRYCOLLECTION EMPTY");
		return features;
	}

	private static String feature(String feature, String geometry, String wkt) {
		return "  ex:" + feature + " a geo:Feature ; geo:hasDefaultGeometry ex:" + geometry + " .\n"
				+ "  ex:" + geometry + " a geo:Geometry ; geo:asWKT \"" + wkt + "\"^^geo:wktLiteral .\n";
	}
}
