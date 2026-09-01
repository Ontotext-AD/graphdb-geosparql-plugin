package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.eclipse.rdf4j.model.Value;
import org.locationtech.jts.geom.Coordinate;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Compares registered GeoSPARQL filter functions with index-backed property relations through GraphDB SPARQL.
 * Each filter query enumerates every default geometry source and applies {@code DISTINCT} at the entity boundary so
 * it has the same existential source-set semantics as the corresponding property relation.
 */
public class GeoSparqlRegisteredFunctionDifferentialTest extends AbstractGeoSparqlPluginTest {
	private static final String EXAMPLE_NAMESPACE = "http://example.com/registered-function-differential/";
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX ex: <" + EXAMPLE_NAMESPACE + ">\n";
	private static final String UTM_32N = "http://www.opengis.net/def/crs/EPSG/0/32632";
	private static final String UTM_33N = "http://www.opengis.net/def/crs/EPSG/0/32633";
	private static final String EPSG_4979 = "http://www.opengis.net/def/crs/EPSG/0/4979";
	private static final String UTM_32_POINT = "<" + UTM_32N + "> POINT(500000 5200000)";
	private static final String UNEVALUABLE_UTM_32_POINT = "<" + UTM_32N + "> POINT(-100000000 0)";
	private static final String ENVELOPE_INTERSECTION_BOUND_POINT =
			"<" + EPSG_4979 + "> POINT Z(0 99.0000001 55)";
	private static final String CLEANUP_BOUND_POINT = "<" + EPSG_4979 + "> POINT Z(-27.47 153.03 55)";

	@Before
	public void insertFixtureAndEnablePlugin() throws Exception {
		String crs84AtCleanedCoordinate = transformedPoint(UTM_32_POINT, IndexGeometry.INDEX_CRS, false);
		String utm33AtCleanedCoordinate = transformedPoint(UTM_32_POINT, UTM_33N, true);
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ feature("utm32", "utm32Geom", UTM_32_POINT)
				+ feature("crs84Cleaned", "crs84CleanedGeom", crs84AtCleanedCoordinate)
				+ feature("utm33Cleaned", "utm33CleanedGeom", utm33AtCleanedCoordinate)
				+ feature("leftArea", "leftAreaGeom",
						"POLYGON((0 0,0 10,10 10,10 0,0 0))")
				+ feature("touchingArea", "touchingAreaGeom",
						"POLYGON((10 0,10 10,20 10,20 0,10 0))")
				+ feature("distantArea", "distantAreaGeom",
						"POLYGON((20 20,20 30,30 30,30 20,20 20))")
				+ feature("emptyPoint", "emptyPointGeom", "POINT EMPTY")
				+ feature("emptyCollection", "emptyCollectionGeom", "GEOMETRYCOLLECTION EMPTY")
				+ feature("collection", "collectionGeom",
						"GEOMETRYCOLLECTION(POINT(5 5),LINESTRING(20 20,21 21))")
				+ feature("envelopeUnevaluableCandidate", "envelopeUnevaluableCandidateGeom",
						UNEVALUABLE_UTM_32_POINT)
				+ feature("envelopeMatchingCandidate", "envelopeMatchingCandidateGeom",
						ENVELOPE_INTERSECTION_BOUND_POINT)
				+ feature("envelopeBound", "envelopeBoundGeom", ENVELOPE_INTERSECTION_BOUND_POINT)
				+ feature("transformCleanupUnevaluableCandidate", "transformCleanupUnevaluableCandidateGeom",
						UNEVALUABLE_UTM_32_POINT)
				+ feature("transformCleanupMatchingCandidate", "transformCleanupMatchingCandidateGeom",
						CLEANUP_BOUND_POINT)
				+ feature("transformCleanupBound", "transformCleanupBoundGeom", CLEANUP_BOUND_POINT)
				+ "  ex:multiSource a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:multiFarGeom, ex:multiInsideGeom .\n"
				+ "  ex:multiFarGeom a geo:Geometry ; geo:asWKT \"POINT(-40 -10)\"^^geo:wktLiteral .\n"
				+ "  ex:multiInsideGeom a geo:Geometry ; geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();
	}

	@Test
	public void mixedCrsRelationsAgreeForCrs84AndDifferentProjectedCrs() throws Exception {
		assertDifferential("sfIntersects", "utm32", false, "crs84Cleaned", "utm33Cleaned");
		assertDifferential("sfDisjoint", "utm32", false, "crs84Cleaned", "utm33Cleaned");
		assertDifferential("ehDisjoint", "utm32", true, "crs84Cleaned", "utm33Cleaned");
	}

	@Test
	public void boundaryContactRelationsAgreeForAreas() throws Exception {
		assertDifferential("sfTouches", "leftArea", false, "touchingArea", "distantArea");
		assertDifferential("rcc8dc", "leftArea", false, "touchingArea", "distantArea");
	}

	@Test
	public void emptyAndGenericCollectionRelationsAgree() throws Exception {
		assertDifferential("sfDisjoint", "leftArea", false, "emptyPoint", "emptyCollection", "collection");
		assertDifferential("ehDisjoint", "leftArea", false, "emptyPoint", "emptyCollection", "collection");
		assertDifferential("sfIntersects", "leftArea", true, "emptyPoint", "emptyCollection", "collection");
	}

	@Test
	public void multipleDefaultGeometrySourcesUseExistentialEntitySemantics() throws Exception {
		assertDifferential("sfIntersects", "leftArea", false, "multiSource");
	}

	@Test
	public void envelopeIntersectionUnevaluableCandidateDoesNotAbortPropertyRelationQuery() throws Exception {
		assertUnevaluableCandidateDoesNotAbortPropertyRelation(
				"envelopeUnevaluableCandidate", UNEVALUABLE_UTM_32_POINT,
				"envelopeMatchingCandidate", "envelopeBound", ENVELOPE_INTERSECTION_BOUND_POINT);
	}

	@Test
	public void transformCleanupUnevaluableCandidateDoesNotAbortPropertyRelationQuery() throws Exception {
		assertUnevaluableCandidateDoesNotAbortPropertyRelation(
				"transformCleanupUnevaluableCandidate", UNEVALUABLE_UTM_32_POINT,
				"transformCleanupMatchingCandidate", "transformCleanupBound", CLEANUP_BOUND_POINT);
	}

	private void assertUnevaluableCandidateDoesNotAbortPropertyRelation(
			String unevaluableCandidate, String unevaluableCandidateWkt,
			String matchingCandidate, String bound, String boundWkt) throws Exception {
		SourceGeometryLiteral candidateSource = SourceGeometryLiteral.fromWkt(unevaluableCandidateWkt);
		SourceGeometryLiteral boundSource = SourceGeometryLiteral.fromWkt(boundWkt);
		assertThrows(JenaGeoSparqlException.class, () -> GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				candidateSource, boundSource));

		Set<Value> propertyResults = queryCandidates(""
				+ "SELECT DISTINCT ?candidate WHERE {\n"
				+ "  ?candidate geo:sfIntersects ex:" + bound + " .\n"
				+ "}");
		propertyResults.retainAll(candidateIris(new String[] { unevaluableCandidate, matchingCandidate }));
		Set<Value> functionResults = queryCandidates(""
				+ "SELECT DISTINCT ?candidate WHERE {\n"
				+ candidateValues(new String[] { unevaluableCandidate, matchingCandidate })
				+ "  ?candidate geo:hasDefaultGeometry/geo:asWKT ?candidateWkt .\n"
				+ "  ex:" + bound + " geo:hasDefaultGeometry/geo:asWKT ?boundWkt .\n"
				+ "  FILTER(geof:sfIntersects(?candidateWkt, ?boundWkt))\n"
				+ "}");

		Set<Value> expected = Set.of(VF.createIRI(EXAMPLE_NAMESPACE, matchingCandidate));
		assertEquals("property relation", expected, propertyResults);
		assertEquals("registered filter function", expected, functionResults);
	}

	private void assertDifferential(String relation, String bound, boolean boundSubject, String... candidates)
			throws Exception {
		String propertyPattern = boundSubject
				? "  ex:" + bound + " geo:" + relation + " ?candidate .\n"
				: "  ?candidate geo:" + relation + " ex:" + bound + " .\n";
		String functionArguments = boundSubject
				? "?boundWkt, ?candidateWkt"
				: "?candidateWkt, ?boundWkt";
		Set<Value> propertyResults = queryCandidates(""
				+ "SELECT DISTINCT ?candidate WHERE {\n"
				+ propertyPattern
				+ "}");
		propertyResults.retainAll(candidateIris(candidates));
		Set<Value> functionResults = queryCandidates(""
				+ "SELECT DISTINCT ?candidate WHERE {\n"
				+ candidateValues(candidates)
				+ "  ?candidate geo:hasDefaultGeometry/geo:asWKT ?candidateWkt .\n"
				+ "  ex:" + bound + " geo:hasDefaultGeometry/geo:asWKT ?boundWkt .\n"
				+ "  FILTER(geof:" + relation + "(" + functionArguments + "))\n"
				+ "}");

		assertEquals(relation + " " + (boundSubject ? "subject" : "object") + "-bound " + bound,
				functionResults, propertyResults);
	}

	private Set<Value> queryCandidates(String query) throws Exception {
		return new LinkedHashSet<>(executeSparqlQueryWithResult(PREFIXES + query, "candidate"));
	}

	private static Set<Value> candidateIris(String[] candidates) {
		Set<Value> iris = new LinkedHashSet<>();
		for (String candidate : candidates) {
			iris.add(VF.createIRI(EXAMPLE_NAMESPACE, candidate));
		}
		return iris;
	}

	private static String candidateValues(String[] candidates) {
		StringBuilder values = new StringBuilder("  VALUES ?candidate {");
		for (String candidate : candidates) {
			values.append(" ex:").append(candidate);
		}
		return values.append(" }\n").toString();
	}

	private static String transformedPoint(String sourceWkt, String targetCrs, boolean includeTargetCrs) throws Exception {
		Coordinate coordinate = SourceGeometryLiteral.fromWkt(sourceWkt)
				.asGeometryWrapper()
				.transform(targetCrs)
				.getXYGeometry()
				.getCoordinate();
		String prefix = includeTargetCrs ? "<" + targetCrs + "> " : "";
		return prefix + "POINT(" + coordinate.x + " " + coordinate.y + ")";
	}

	private static String feature(String feature, String geometry, String wkt) {
		return "  ex:" + feature + " a geo:Feature ; geo:hasDefaultGeometry ex:" + geometry + " .\n"
				+ "  ex:" + geometry + " a geo:Geometry ; geo:asWKT \"" + wkt + "\"^^geo:wktLiteral .\n";
	}
}
