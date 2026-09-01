package com.ontotext.trree.geosparql.function;

import com.ontotext.trree.geosparql.jena.GeoSparqlUnits;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JenaBackedFunctionCoverageTest {
	private static final ValueFactory VF = SimpleValueFactory.getInstance();
	private static final ValueFactoryTripleSource TRIPLE_SOURCE = new ValueFactoryTripleSource(VF);

	private static final String SAME_AREA_A = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String SAME_AREA_B = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String DISJOINT_A = "POLYGON((0 0,0 2,2 2,2 0,0 0))";
	private static final String DISJOINT_B = "POLYGON((3 3,3 5,5 5,5 3,3 3))";
	private static final String MEET_A = "POLYGON((0 0,0 2,2 2,2 0,0 0))";
	private static final String MEET_B = "POLYGON((2 0,2 2,4 2,4 0,2 0))";
	private static final String OVERLAP_A = "POLYGON((0 0,0 3,3 3,3 0,0 0))";
	private static final String OVERLAP_B = "POLYGON((2 2,2 5,5 5,5 2,2 2))";
	private static final String CONTAINS_A = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String CONTAINS_B = "POLYGON((1 1,1 2,2 2,2 1,1 1))";
	private static final String INSIDE_A = "POLYGON((1 1,1 2,2 2,2 1,1 1))";
	private static final String INSIDE_B = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String BOUNDARY_CONTAINS_A = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String BOUNDARY_CONTAINS_B = "POLYGON((0 1,0 2,2 2,2 1,0 1))";
	private static final String BOUNDARY_INSIDE_A = "POLYGON((0 1,0 2,2 2,2 1,0 1))";
	private static final String BOUNDARY_INSIDE_B = "POLYGON((0 0,0 4,4 4,4 0,0 0))";
	private static final String CROSS_A = "LINESTRING(0 0,2 2)";
	private static final String CROSS_B = "LINESTRING(0 2,2 0)";
	private static final String DISJOINT_LINE_A = "LINESTRING(0 0,1 1)";
	private static final String DISJOINT_LINE_B = "LINESTRING(2 2,3 3)";

	private static final List<TopologicalCase> SIMPLE_FEATURES_CASES = List.of(
			topology(GeoConstants.GEOF_SF_EQUALS, SAME_AREA_A, SAME_AREA_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_SF_DISJOINT, DISJOINT_A, DISJOINT_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_SF_INTERSECTS, OVERLAP_A, OVERLAP_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_SF_TOUCHES, MEET_A, MEET_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_SF_CROSSES, CROSS_A, CROSS_B, DISJOINT_LINE_A, DISJOINT_LINE_B),
			topology(GeoConstants.GEOF_SF_WITHIN, INSIDE_A, INSIDE_B, CONTAINS_A, CONTAINS_B),
			topology(GeoConstants.GEOF_SF_CONTAINS, CONTAINS_A, CONTAINS_B, INSIDE_A, INSIDE_B),
			topology(GeoConstants.GEOF_SF_OVERLAPS, OVERLAP_A, OVERLAP_B, DISJOINT_A, DISJOINT_B));

	private static final List<TopologicalCase> EGENHOFER_CASES = List.of(
			topology(GeoConstants.GEOF_EH_EQUALS, SAME_AREA_A, SAME_AREA_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_EH_DISJOINT, DISJOINT_A, DISJOINT_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_EH_MEET, MEET_A, MEET_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_EH_OVERLAP, OVERLAP_A, OVERLAP_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_EH_COVERS, BOUNDARY_CONTAINS_A, BOUNDARY_CONTAINS_B,
					DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_EH_COVERED_BY, BOUNDARY_INSIDE_A, BOUNDARY_INSIDE_B,
					DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_EH_INSIDE, INSIDE_A, INSIDE_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_EH_CONTAINS, CONTAINS_A, CONTAINS_B, DISJOINT_A, DISJOINT_B));

	private static final List<TopologicalCase> RCC8_CASES = List.of(
			topology(GeoConstants.GEOF_RCC8_EQ, SAME_AREA_A, SAME_AREA_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_RCC8_DC, DISJOINT_A, DISJOINT_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_RCC8_EC, MEET_A, MEET_B, OVERLAP_A, OVERLAP_B),
			topology(GeoConstants.GEOF_RCC8_PO, OVERLAP_A, OVERLAP_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_RCC8_TPPI, BOUNDARY_CONTAINS_A, BOUNDARY_CONTAINS_B,
					DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_RCC8_TPP, BOUNDARY_INSIDE_A, BOUNDARY_INSIDE_B,
					DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_RCC8_NTPP, INSIDE_A, INSIDE_B, DISJOINT_A, DISJOINT_B),
			topology(GeoConstants.GEOF_RCC8_NTPPI, CONTAINS_A, CONTAINS_B, DISJOINT_A, DISJOINT_B));

	private static final List<IRI> NON_TOPOLOGICAL_COVERAGE = List.of(
			GeoConstants.GEOF_RELATE,
			GeoConstants.GEOF_DISTANCE,
			GeoConstants.GEOF_BUFFER,
			GeoConstants.GEOF_CONVEX_HULL,
			GeoConstants.GEOF_INTERSECTION,
			GeoConstants.GEOF_UNION,
			GeoConstants.GEOF_DIFFERENCE,
			GeoConstants.GEOF_SYM_DIFFERENCE,
			GeoConstants.GEOF_ENVELOPE,
			GeoConstants.GEOF_BOUNDARY,
			GeoConstants.GEOF_GETSRID,
			GeoConstants.GEO_DIMENSION,
			GeoConstants.GEO_COORDINATE_DIMENSION,
			GeoConstants.GEO_SPATIAL_DIMENSION,
			GeoConstants.GEO_IS_EMPTY,
			GeoConstants.GEO_IS_SIMPLE,
			GeoConstants.EXT_AREA,
			GeoConstants.EXT_CLOSEST_POINT,
			GeoConstants.EXT_CONTAINS_PROPERLY,
			GeoConstants.EXT_COVERED_BY,
			GeoConstants.EXT_COVERS,
			GeoConstants.EXT_HAUSDORFF_DISTANCE,
			GeoConstants.EXT_SHORTEST_LINE,
			GeoConstants.EXT_SIMPLIFY,
			GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY,
			GeoConstants.EXT_IS_VALID);

	@BeforeClass
	public static void registerFunctions() {
		GeoSparqlFunctionRegistration.registerAll();
	}

	@Test
	public void registeredFunctionUrisHaveCoverageCases() {
		Set<String> expected = new HashSet<>(GeoSparqlFunctionRegistration.supportedFunctionUris());
		Set<String> actual = coveredFunctionUris();

		assertEquals(expected, actual);
	}

	@Test
	public void simpleFeaturesFunctionsHaveDirectCoverage() throws Exception {
		assertTopologicalCases(SIMPLE_FEATURES_CASES);
		assertBoolean(GeoConstants.GEOF_SF_CROSSES, false, INSIDE_A, INSIDE_B);
		assertBoolean(GeoConstants.GEOF_SF_OVERLAPS, false, INSIDE_A, INSIDE_B);
	}

	@Test
	public void egenhoferFunctionsHaveDirectCoverage() throws Exception {
		assertTopologicalCases(EGENHOFER_CASES);
	}

	@Test
	public void rcc8FunctionsHaveDirectCoverage() throws Exception {
		assertTopologicalCases(RCC8_CASES);
	}

	@Test
	public void relatePatternsReturnBooleanResultsThroughRegisteredFunction() throws Exception {
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.GEOF_RELATE,
				wkt("POINT(0 0)"), wkt("POINT(0 0)"), VF.createLiteral("T********")));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.GEOF_RELATE,
				wkt("POLYGON((2 2,2 3,3 3,3 2,2 2))"),
				wkt("POLYGON((1 1,1 4,4 4,4 1,1 1))"),
				VF.createLiteral("T**FF*FF*")));
	}

	@Test
	public void binaryTopologicalFunctionsEnforceExactArity() {
		for (TopologicalCase testCase : topologicalCases()) {
			IRI uri = testCase.functionUri;
			assertThrows("Expected " + uri + " to reject 1 argument",
					ValueExprEvaluationException.class,
					() -> evaluate(uri, wkt(testCase.trueLeftWkt)));
			assertThrows("Expected " + uri + " to reject 3 arguments",
					ValueExprEvaluationException.class,
					() -> evaluate(uri, wkt(testCase.trueLeftWkt), wkt(testCase.trueRightWkt), VF.createLiteral("extra")));
		}
	}

	@Test
	public void relateEnforcesExactArity() {
		assertThrows("Expected geof:relate to reject 2 arguments",
				ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_RELATE, wkt("POINT(0 0)"), wkt("POINT(0 0)")));
		assertThrows("Expected geof:relate to reject 4 arguments",
				ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_RELATE, wkt("POINT(0 0)"), wkt("POINT(0 0)"),
						VF.createLiteral("T********"), VF.createLiteral("extra")));
	}

	@Test
	public void extensionBooleanFunctionsEnforceExactArity() {
		List<IRI> extensionBooleans = List.of(
				GeoConstants.EXT_CONTAINS_PROPERLY,
				GeoConstants.EXT_COVERED_BY,
				GeoConstants.EXT_COVERS);
		for (IRI uri : extensionBooleans) {
			assertThrows("Expected " + uri + " to reject 1 argument",
					ValueExprEvaluationException.class,
					() -> evaluate(uri, wkt(CONTAINS_A)));
			assertThrows("Expected " + uri + " to reject 3 arguments",
					ValueExprEvaluationException.class,
					() -> evaluate(uri, wkt(CONTAINS_A), wkt(CONTAINS_B), VF.createLiteral("extra")));
		}
	}

	@Test
	public void distanceFunctionHasDirectCoverage() throws Exception {
		Value distance = evaluate(GeoConstants.GEOF_DISTANCE,
				wkt("POINT(0 0)"), wkt("LINESTRING(10 0, 10 0)"), GeoSparqlUnits.URI_METRE);

		assertTrue(distance instanceof Literal);
		assertDoubleValue(distance, 1111950.7973436872, 1e-6);
		assertEquals(VF.createIRI("http://www.w3.org/2001/XMLSchema#double"),
				((Literal) distance).getDatatype());
	}

	@Test
	public void documentedDistanceUnitsHaveDirectCoverage() throws Exception {
		Literal left = wkt("POINT(0 0)");
		Literal right = wkt("POINT(1 0)");

		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_METRE),
				111195.07973436874, 1e-6);
		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_DEGREE),
				1.0, 1e-9);
		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_RADIAN),
				0.017453292519943295, 1e-12);
		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_GRID_SPACING),
				1.0, 1e-12);
		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_UNITY),
				1.0, 1e-12);
		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_LIGHT_YEAR),
				1.1753580241416625e-11, 1e-20);
	}

	@Test
	public void epsg4326DistanceUsesGreatCircleDistance() throws Exception {
		Literal left = wkt("<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(10.0 20.0)");
		Literal right = wkt("<http://www.opengis.net/def/crs/EPSG/0/4326> POINT(10.0 21.0)");

		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_KILOMETRE),
				109.5057, 0.0001);
	}

	@Test
	public void bufferWithDocumentedUnitArgumentUsesRegisteredFunction() throws Exception {
		assertWktLiteral(evaluate(GeoConstants.GEOF_BUFFER,
				wkt("POINT(0 0)"), VF.createLiteral("0.0"), GeoSparqlUnits.URI_METRE),
				"POLYGON EMPTY");
	}

	@Test
	public void gmlDistanceWorksThroughRegisteredFunction() throws Exception {
		Literal left = gml("<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\">"
				+ "<gml:pos>0 0</gml:pos></gml:Point>");
		Literal right = gml("<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\">"
				+ "<gml:pos>1 0</gml:pos></gml:Point>");

		assertDoubleValue(evaluate(GeoConstants.GEOF_DISTANCE, left, right, GeoSparqlUnits.URI_METRE),
				111195.07973436874, 1e-6);
	}

	@Test
	public void relateInvalidPatternThrowsEvaluationException() throws Exception {
		assertThrows(ValueExprEvaluationException.class, () -> evaluate(GeoConstants.GEOF_RELATE,
				wkt("POINT(0 0)"), wkt("POINT(0 0)"), VF.createLiteral("wrong")));
	}

	@Test
	public void geometryProducingFunctionsHaveDirectCoverage() throws Exception {
		assertWktLiteral(evaluate(GeoConstants.GEOF_BUFFER, wkt("POINT(0 0)"), VF.createLiteral("0.0")),
				"POLYGON EMPTY");
		assertWktLiteral(evaluate(GeoConstants.GEOF_CONVEX_HULL,
				wkt("MULTIPOINT ((0 0), (1 1), (0 1))")),
				"POLYGON ((0 0, 0 1, 1 1, 0 0))");
		assertWktLiteral(evaluate(GeoConstants.GEOF_INTERSECTION,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")),
				"LINESTRING (0 0, 1 1)");
		assertWktLiteral(evaluate(GeoConstants.GEOF_UNION,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")),
				"LINESTRING (0 0, 1 1)");
		assertWktLiteral(evaluate(GeoConstants.GEOF_DIFFERENCE,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")),
				"LINESTRING EMPTY");
		assertWktLiteral(evaluate(GeoConstants.GEOF_SYM_DIFFERENCE,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 2 2)")),
				"MULTILINESTRING ((0 0, 1 1), (1 1, 2 2))");
		assertWktLiteral(evaluate(GeoConstants.GEOF_ENVELOPE, wkt("LINESTRING(0 0, 1 1)")),
				"POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))");
		assertWktLiteral(evaluate(GeoConstants.GEOF_BOUNDARY, wkt("LINESTRING(0 0, 1 1)")),
				"MULTIPOINT ((0 0), (1 1))");
	}

	@Test
	public void getSridHasDirectCoverage() throws Exception {
		Value srid = evaluate(GeoConstants.GEOF_GETSRID,
				wkt("<http://www.opengis.net/def/crs/OGC/1.3/CRS84> POINT(1 2)"));

		assertEquals("http://www.opengis.net/def/crs/OGC/1.3/CRS84", srid.stringValue());
		assertEquals(XSD.ANYURI, ((Literal) srid).getDatatype());
	}

	@Test
	public void geometryPropertyFunctionsHaveDirectCoverage() throws Exception {
		assertIntegerValue(evaluate(GeoConstants.GEO_DIMENSION, wkt("POINT(1 1)")), 0);
		assertIntegerValue(evaluate(GeoConstants.GEO_DIMENSION, wkt("LINESTRING(1 1, 3 1)")), 1);
		assertIntegerValue(evaluate(GeoConstants.GEO_DIMENSION,
				wkt("POLYGON((2 2,2 3,3 3,3 2,2 2))")), 2);
		assertIntegerValue(evaluate(GeoConstants.GEO_COORDINATE_DIMENSION, wkt("POINT Z(1 2 3)")), 3);
		assertIntegerValue(evaluate(GeoConstants.GEO_SPATIAL_DIMENSION, wkt("POINT Z(1 2 3)")), 3);
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.GEO_IS_EMPTY, wkt("POINT EMPTY")));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.GEO_IS_EMPTY, wkt("POINT(1 2)")));
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.GEO_IS_SIMPLE, wkt("POINT(1 1)")));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.GEO_IS_SIMPLE,
				wkt("LINESTRING(1 1, 3 1, 2 2, 2 0)")));
	}

	@Test
	public void extensionFunctionsHaveDirectCoverage() throws Exception {
		assertDoubleValue(evaluate(GeoConstants.EXT_AREA,
				wkt("POLYGON((0 0,0 2,2 2,2 0,0 0))")), 4.0, 0.0);
		assertWktLiteral(evaluate(GeoConstants.EXT_CLOSEST_POINT,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")), "POINT (1 1)");
		assertWktLiteral(evaluate(GeoConstants.EXT_SHORTEST_LINE,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")), "LINESTRING (1 1, 1 1)");
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.EXT_CONTAINS_PROPERLY,
				wkt(CONTAINS_A), wkt(CONTAINS_B)));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.EXT_CONTAINS_PROPERLY,
				wkt(CONTAINS_B), wkt(CONTAINS_A)));
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.EXT_COVERED_BY,
				wkt(CONTAINS_B), wkt(CONTAINS_A)));
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.EXT_COVERS,
				wkt(CONTAINS_A), wkt(CONTAINS_B)));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.EXT_COVERS,
				wkt(CONTAINS_B), wkt(CONTAINS_A)));
		assertDoubleValue(evaluate(GeoConstants.EXT_HAUSDORFF_DISTANCE,
				wkt("LINESTRING(0 0, 1 1)"), wkt("LINESTRING(1 1, 0 0)")), 1.0, 0.0);
		assertWktLiteral(evaluate(GeoConstants.EXT_SIMPLIFY,
				wkt("LINESTRING(0 0, 1 0.1, 2 0)"), VF.createLiteral("0.2")),
				"LINESTRING (0 0, 2 0)");
		assertWktLiteral(evaluate(GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY,
				wkt("LINESTRING(0 0, 1 0.1, 2 0)"), VF.createLiteral("0.2")),
				"LINESTRING (0 0, 2 0)");
		assertWktLiteral(evaluate(GeoConstants.EXT_SIMPLIFY,
				wkt("LINESTRING(0 0, 1 1)"), VF.createLiteral("0.0")),
				"LINESTRING (0 0, 1 1)");
		assertWktLiteral(evaluate(GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY,
				wkt("LINESTRING(0 0, 1 1)"), VF.createLiteral("0.0")),
				"LINESTRING (0 0, 1 1)");
		assertEquals(VF.createLiteral(true), evaluate(GeoConstants.EXT_IS_VALID,
				wkt("POLYGON((2 2,2 3,3 3,3 2,2 2))")));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.EXT_IS_VALID,
				wkt("POLYGON((2 2,3 3,3 2,2 3,2 2))")));
		assertEquals(VF.createLiteral(false), evaluate(GeoConstants.EXT_IS_VALID, wkt("NOT A GEO")));
	}

	private static void assertTopologicalCases(List<TopologicalCase> cases) throws Exception {
		for (TopologicalCase testCase : cases) {
			assertBoolean(testCase.functionUri, true, testCase.trueLeftWkt, testCase.trueRightWkt);
			assertBoolean(testCase.functionUri, false, testCase.falseLeftWkt, testCase.falseRightWkt);
		}
	}

	private static void assertBoolean(IRI uri, boolean expected, String leftWkt, String rightWkt) throws Exception {
		assertEquals("Unexpected result for " + uri, VF.createLiteral(expected), evaluate(uri, wkt(leftWkt), wkt(rightWkt)));
	}

	private static void assertDoubleValue(Value value, double expected, double tolerance) {
		assertTrue(value instanceof Literal);
		assertEquals(expected, ((Literal) value).doubleValue(), tolerance);
	}

	private static void assertIntegerValue(Value value, int expected) {
		assertTrue(value instanceof Literal);
		assertEquals(expected, ((Literal) value).intValue());
	}

	private static void assertWktLiteral(Value value, String expectedLexicalForm) {
		assertTrue(value instanceof Literal);
		Literal literal = (Literal) value;
		assertEquals(expectedLexicalForm, literal.stringValue());
		assertEquals(GeoConstants.GEO_WKT_LITERAL, literal.getDatatype());
	}

	private static Literal wkt(String lexical) {
		return VF.createLiteral(lexical, GeoConstants.GEO_WKT_LITERAL);
	}

	private static Literal gml(String lexical) {
		return VF.createLiteral(lexical, GeoConstants.GEO_GML_LITERAL);
	}

	private static Value evaluate(IRI functionUri, Value... args) throws ValueExprEvaluationException {
		Function function = FunctionRegistry.getInstance()
				.get(functionUri.stringValue())
				.orElseThrow(() -> new AssertionError("Function not registered: " + functionUri));
		return function.evaluate(TRIPLE_SOURCE, args);
	}

	private static Set<String> coveredFunctionUris() {
		Set<String> covered = new HashSet<>();
		for (TopologicalCase testCase : topologicalCases()) {
			covered.add(testCase.functionUri.stringValue());
		}
		for (IRI uri : NON_TOPOLOGICAL_COVERAGE) {
			covered.add(uri.stringValue());
		}
		return covered;
	}

	private static List<TopologicalCase> topologicalCases() {
		List<TopologicalCase> cases = new ArrayList<>();
		cases.addAll(SIMPLE_FEATURES_CASES);
		cases.addAll(EGENHOFER_CASES);
		cases.addAll(RCC8_CASES);
		return cases;
	}

	private static TopologicalCase topology(IRI functionUri, String trueLeftWkt, String trueRightWkt,
											String falseLeftWkt, String falseRightWkt) {
		return new TopologicalCase(functionUri, trueLeftWkt, trueRightWkt, falseLeftWkt, falseRightWkt);
	}

	private static final class TopologicalCase {
		private final IRI functionUri;
		private final String trueLeftWkt;
		private final String trueRightWkt;
		private final String falseLeftWkt;
		private final String falseRightWkt;

		private TopologicalCase(IRI functionUri, String trueLeftWkt, String trueRightWkt,
								String falseLeftWkt, String falseRightWkt) {
			this.functionUri = functionUri;
			this.trueLeftWkt = trueLeftWkt;
			this.trueRightWkt = trueRightWkt;
			this.falseLeftWkt = falseLeftWkt;
			this.falseRightWkt = falseRightWkt;
		}
	}
}
