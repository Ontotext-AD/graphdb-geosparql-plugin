package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.eclipse.rdf4j.model.Value;
import org.locationtech.jts.geom.Coordinate;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
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
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String EPSG_4955 = "http://www.opengis.net/def/crs/EPSG/0/4955";
	private static final String UTM_34N = "http://www.opengis.net/def/crs/EPSG/0/32634";
	private static final String MERCATOR = "http://www.opengis.net/def/crs/EPSG/0/3857";
	private static final String WEB_MERCATOR = "<http://www.opengis.net/def/crs/EPSG/0/3857> ";
	/**
	 * Collections of area dimension, separated from {@code distantArea}. The first four hold no polygon, and the
	 * tiles are squares whose rings all start inside their union.
	 */
	private static final String[] AREA_DIMENSION_COLLECTIONS = { "pointAndEmptyArea", "projectedPointAndEmptyArea",
			"lineAndEmptyArea", "ringAndEmptyArea", "projectedPointAndArea", "tiles", "projectedTiles" };
	private static final String TILES_FROM_CENTRE = "GEOMETRYCOLLECTION(POLYGON((40 40,41 40,41 41,40 41,40 40)),"
				+ "POLYGON((40 40,39 40,39 41,40 41,40 40)),"
				+ "POLYGON((40 40,39 40,39 39,40 39,40 40)),"
				+ "POLYGON((40 40,41 40,41 39,40 39,40 40)))";
	private static final String UTM_32_POINT = "<" + UTM_32N + "> POINT(500000 5200000)";
	private static final String UNEVALUABLE_UTM_32_POINT = "<" + UTM_32N + "> POINT(-100000000 0)";
	private static final String ENVELOPE_INTERSECTION_BOUND_POINT =
			"<" + EPSG_4979 + "> POINT Z(0 99.0000001 55)";
	private static final String CLEANUP_BOUND_POINT = "<" + EPSG_4979 + "> POINT Z(-27.47 153.03 55)";

	@Before
	public void insertFixtureAndEnablePlugin() throws Exception {
		String crs84AtCleanedCoordinate = transformedPoint(UTM_32_POINT, IndexGeometry.INDEX_CRS, false);
		String utm33AtCleanedCoordinate = transformedPoint(UTM_32_POINT, UTM_33N, true);
		String mercatorOverlappingCentre = transformedPoint("POINT(50.5 50.5)", MERCATOR, true);
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
				+ feature("pointAndEmptyArea", "pointAndEmptyAreaGeom",
						"GEOMETRYCOLLECTION(POINT(40 40),POLYGON EMPTY)")
				+ feature("projectedPointAndEmptyArea", "projectedPointAndEmptyAreaGeom",
						WEB_MERCATOR + "GEOMETRYCOLLECTION(POINT(0 0),POLYGON EMPTY)")
				+ feature("lineAndEmptyArea", "lineAndEmptyAreaGeom",
						"GEOMETRYCOLLECTION(LINESTRING(40 40,41 41),POLYGON EMPTY)")
				+ feature("ringAndEmptyArea", "ringAndEmptyAreaGeom",
						"GEOMETRYCOLLECTION(LINESTRING(40 40,41 40,41 41,40 40),POLYGON EMPTY)")
				+ feature("projectedPointAndArea", "projectedPointAndAreaGeom",
						WEB_MERCATOR + "GEOMETRYCOLLECTION(POINT(0 0),POLYGON((0 0,0 1000,1000 1000,1000 0,0 0)))")
				+ feature("projectedArea", "projectedAreaGeom",
						WEB_MERCATOR + "POLYGON((0 0,0 1000,1000 1000,1000 0,0 0))")
				+ feature("nearBrisbane", "nearBrisbaneGeom", "POINT(153.031 -27.47)")
				+ feature("infiniteHeight", "infiniteHeightGeom", "<" + EPSG_4326 + "> POINT Z(-27.47 153.03 1e309)")
				+ feature("finiteHeight", "finiteHeightGeom", "<" + EPSG_4326 + "> POINT Z(-27.47 153.03 55)")
				+ feature("nearVancouver", "nearVancouverGeom",
						"POLYGON((-123.002 49,-123.002 49.0005,-123.0015 49.0005,-123.002 49))")
				+ feature("openingRing", "openingRingGeom", "<" + EPSG_4955
						+ "> POLYGON Z((49 -123 0,49 -123.001 1,49.001 -123.001 2,49 -123 1000000))")
				+ feature("closingRing", "closingRingGeom", "<" + EPSG_4955
						+ "> POLYGON Z((49 -123 0,49 -123.001 1,49.001 -123.001 2,49 -123 0))")
				+ feature("eastOfBrisbane", "eastOfBrisbaneGeom", "POINT(153.481 -27.47)")
				+ feature("aroundBrisbane", "aroundBrisbaneGeom",
						"POLYGON((153 -27.5,153.1 -27.5,153.1 -27.4,153 -27.4,153 -27.5))")
				+ feature("besideBrisbane", "besideBrisbaneGeom", "LINESTRING(153 -27.5,153.06 -27.5,153.06 -27.44)")
				+ feature("utmPoint", "utmPointGeom", "<" + UTM_34N + "> POINT(500000 6700000)")
				+ feature("utmControl", "utmControlGeom", "<" + UTM_34N + "> POINT(400000 6600000)")
				+ feature("overlappingSquare", "overlappingSquareGeom", "<" + MERCATOR + "> POLYGON((2000000 2000000,"
						+ "2010000 2000000,2010000 2010000,2000000 2010000,2000000 2000000,2010000 2000000,"
						+ "2010000 2010000,2000000 2010000,2000000 2000000))")
				+ feature("overlappingSquareCentre", "overlappingSquareCentreGeom",
						"<" + MERCATOR + "> POINT(2005000 2005000)")
				+ feature("overlappingSquareOutside", "overlappingSquareOutsideGeom",
						"<" + MERCATOR + "> POINT(2020000 2005000)")
				+ feature("crs84OverlappingSquare", "crs84OverlappingSquareGeom",
						"POLYGON((50 50,51 50,51 51,50 51,50 50,51 50,51 51,50 51,50 50))")
				+ feature("crs84OverlappingCentre", "crs84OverlappingCentreGeom", "POINT(50.5 50.5)")
				+ feature("projectedOverlappingCentre", "projectedOverlappingCentreGeom", mercatorOverlappingCentre)
				+ feature("infiniteRectangle", "infiniteRectangleGeom", "<" + UTM_34N
						+ "> POLYGON((400000 6600000,1e309 6600000,1e309 6700000,400000 6700000,400000 6600000))")
				+ feature("utmInside", "utmInsideGeom", "<" + UTM_34N + "> POINT(450000 6650000)")
				+ feature("utmWest", "utmWestGeom", "<" + UTM_34N + "> POINT(300000 6650000)")
				+ feature("infiniteCollection", "infiniteCollectionGeom", "<" + UTM_34N
						+ "> GEOMETRYCOLLECTION(POINT(1e309 5),POINT(500000 6700000))")
				+ "  ex:nanCollection a geo:Feature ; geo:hasDefaultGeometry ex:nanCollectionGeom .\n"
				+ "  ex:nanCollectionGeom a geo:Geometry ; geo:asGML '''<gml:MultiPoint"
				+ " xmlns:gml=\"http://www.opengis.net/gml/3.2\" srsName=\"" + UTM_34N + "\">"
				+ "<gml:pointMember><gml:Point><gml:pos>NaN 5</gml:pos></gml:Point></gml:pointMember>"
				+ "<gml:pointMember><gml:Point><gml:pos>500000 6700000</gml:pos></gml:Point></gml:pointMember>"
				+ "</gml:MultiPoint>'''^^geo:gmlLiteral .\n"
				+ feature("mixedHeights", "mixedHeightsGeom", "<" + EPSG_4326
						+ "> GEOMETRYCOLLECTION Z(POINT(-27.47 153.03),POINT Z(-27.47 153.03 55))")
				+ feature("uniformHeights", "uniformHeightsGeom", "<" + EPSG_4326
						+ "> GEOMETRYCOLLECTION Z(POINT Z(-27.47 153.03 54),POINT Z(-27.47 153.03 55))")
				+ feature("tiles", "tilesGeom", TILES_FROM_CENTRE)
				+ feature("projectedTiles", "projectedTilesGeom", WEB_MERCATOR
						+ "GEOMETRYCOLLECTION(POLYGON((0 0,1000 0,1000 1000,0 1000,0 0)),"
						+ "POLYGON((0 0,-1000 0,-1000 1000,0 1000,0 0)),POLYGON((0 0,-1000 0,-1000 -1000,0 -1000,0 0)),"
						+ "POLYGON((0 0,1000 0,1000 -1000,0 -1000,0 0)))")
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

	/**
	 * A collection of area dimension is disjoint from a separated area, and exact evaluation decides whether it is
	 * RCC8 disconnected from it. Without a polygon, that depends on whether its points and lines have a boundary: an
	 * open line has one, a point or closed ring does not. For tiles it depends on where their rings start, as
	 * {@code JenaGeometryAdapterTest} records.
	 */
	@Test
	public void disjointRelationsAgreeForCollectionsOfAreaDimension() throws Exception {
		for (boolean boundSubject : new boolean[] { false, true }) {
			assertDifferential("rcc8dc", "distantArea", boundSubject, AREA_DIMENSION_COLLECTIONS);
			assertDifferential("sfDisjoint", "distantArea", boundSubject, AREA_DIMENSION_COLLECTIONS);
			assertDifferential("rcc8dc", "pointAndEmptyArea", boundSubject, "leftArea", "projectedArea");
			assertDifferential("rcc8dc", "lineAndEmptyArea", boundSubject, "leftArea", "projectedArea");
			assertDifferential("rcc8dc", "tiles", boundSubject, "leftArea", "projectedArea");
		}
	}

	/**
	 * Jena cannot transform a point with an infinite height into CRS84, so no relation holds between it and a CRS84
	 * subject, although their envelopes are apart within one prefix cell. The same point with a finite height is
	 * disjoint from the subject.
	 */
	@Test
	public void disjointRelationsAgreeForASourceJenaCannotTransform() throws Exception {
		assertDifferential("sfDisjoint", "nearBrisbane", true, "infiniteHeight", "finiteHeight");
		assertDifferential("ehDisjoint", "nearBrisbane", true, "infiniteHeight", "finiteHeight");
	}

	/**
	 * A height-dependent datum shift moves the ends of a ring in EPSG:4955 apart when their heights differ, so Jena
	 * cannot transform that ring into CRS84 although its horizontal envelope transforms, and no relation holds with
	 * a CRS84 subject. The same ring with matching heights is disjoint from the subject.
	 */
	@Test
	public void disjointRelationsAgreeForARingThatOpensWhenTransformed() throws Exception {
		for (boolean boundSubject : new boolean[] { true, false }) {
			assertDifferential("sfDisjoint", "nearVancouver", boundSubject, "openingRing", "closingRing");
			assertDifferential("ehDisjoint", "nearVancouver", boundSubject, "openingRing", "closingRing");
		}
	}

	/**
	 * A collection of an XY point and an XYZ point is related as written, like a collection whose points share a
	 * dimension: by its separated envelope, and through exact evaluation when envelopes meet, both for a line that
	 * passes beside its points and for a polygon around them.
	 */
	@Test
	public void relationsAgreeForACollectionOfMembersInDifferentDimensions() throws Exception {
		for (boolean boundSubject : new boolean[] { true, false }) {
			assertDifferential("sfDisjoint", "eastOfBrisbane", boundSubject, "mixedHeights", "uniformHeights");
			assertDifferential("ehDisjoint", "eastOfBrisbane", boundSubject, "mixedHeights", "uniformHeights");
			assertDifferential("sfDisjoint", "besideBrisbane", boundSubject, "mixedHeights", "uniformHeights");
			assertDifferential("ehDisjoint", "besideBrisbane", boundSubject, "mixedHeights", "uniformHeights");
			assertDifferential("sfIntersects", "aroundBrisbane", boundSubject, "mixedHeights", "uniformHeights");
		}
	}

	/**
	 * A NaN or infinite coordinate leaves a source's own envelope with a non-finite bound, which shows nothing about
	 * where the source lies. Such a source is related exactly: it intersects a point it holds and is not disjoint from
	 * it, while a distant point is disjoint from that point.
	 */
	@Test
	public void relationsAgreeForSourcesWithNonFiniteEnvelopes() throws Exception {
		for (boolean boundSubject : new boolean[] { false, true }) {
			for (String relation : List.of("sfIntersects", "sfDisjoint", "ehDisjoint", "sfEquals")) {
				assertDifferential(relation, "utmPoint", boundSubject, "nanCollection", "infiniteCollection",
						"utmControl");
			}
		}
	}

	/**
	 * A polygon with an infinite coordinate fills its raw envelope, but lookup compares the unbounded envelope that
	 * replaces it, so filling proves nothing about which sources lie inside the bound. The disjoint relations agree
	 * with Jena for sources inside and outside its finite corner.
	 */
	@Test
	public void disjointRelationsAgreeForABoundWithAnInfiniteCoordinate() throws Exception {
		for (boolean boundSubject : new boolean[] { false, true }) {
			for (String relation : List.of("sfDisjoint", "ehDisjoint")) {
				assertDifferential(relation, "infiniteRectangle", boundSubject, "utmInside", "utmWest", "utmPoint",
						"utmControl");
			}
		}
	}

	/**
	 * A polygon whose ring winds twice around a square is invalid. JTS can find that it fills its envelope, while
	 * Jena finds a point inside the square disjoint from it, so filling proves nothing about an invalid bound, in a
	 * projected CRS or in CRS84.
	 */
	@Test
	public void disjointRelationsAgreeForASelfOverlappingBound() throws Exception {
		for (boolean boundSubject : new boolean[] { false, true }) {
			for (String relation : List.of("sfDisjoint", "ehDisjoint")) {
				assertDifferential(relation, "overlappingSquare", boundSubject, "overlappingSquareCentre",
						"overlappingSquareOutside");
				assertDifferential(relation, "crs84OverlappingSquare", boundSubject, "crs84OverlappingCentre",
						"projectedOverlappingCentre");
			}
		}
	}

	/** An IRI that the repository does not hold has no geometry, so no relation holds with it. */
	@Test
	public void boundIriAbsentFromTheRepositoryMatchesNothing() throws Exception {
		for (String relation : List.of("sfIntersects", "sfDisjoint", "ehCovers")) {
			assertEquals(relation + " object-bound", Set.of(), queryCandidates(
					"SELECT ?candidate WHERE { ?candidate geo:" + relation + " ex:absentFeature . }"));
			assertEquals(relation + " subject-bound", Set.of(), queryCandidates(
					"SELECT ?candidate WHERE { ex:absentFeature geo:" + relation + " ?candidate . }"));
			assertEquals(relation + " both bound", Set.of(), queryCandidates("SELECT ?candidate WHERE {\n"
					+ candidateValues(new String[] { "tiles", "nearBrisbane" })
					+ "  ?candidate geo:" + relation + " ex:absentFeature .\n}"));
		}
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
				+ "  ?candidate geo:hasDefaultGeometry/(geo:asWKT|geo:asGML) ?candidateWkt .\n"
				+ "  ex:" + bound + " geo:hasDefaultGeometry/(geo:asWKT|geo:asGML) ?boundWkt .\n"
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
				+ "  ?candidate geo:hasDefaultGeometry/(geo:asWKT|geo:asGML) ?candidateWkt .\n"
				+ "  ex:" + bound + " geo:hasDefaultGeometry/(geo:asWKT|geo:asGML) ?boundWkt .\n"
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
