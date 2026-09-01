package com.ontotext.trree.geosparql.function;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for <a href="https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/4">#4</a>.
 *
 * <p>{@code geof:sfEquals} and {@code geof:ehEquals} must use the GeoSPARQL DE-9IM equality pattern
 * {@code TFFFTFFFT}, including for generic {@code GeometryCollection} literals. Point, closed-line, and empty
 * collections have empty boundaries, so that pattern does not hold even when the collections are identical.
 */
public class GeoSparqlCollectionEqualityDifferentialTest {
	private static final ValueFactory VF = SimpleValueFactory.getInstance();
	private static final ValueFactoryTripleSource TRIPLE_SOURCE = new ValueFactoryTripleSource(VF);
	private static final String EQUALS_PATTERN = "TFFFTFFFT";
	private static final String POINT_COLLECTION = "GEOMETRYCOLLECTION(POINT(0 0))";
	private static final String CLOSED_LINE_COLLECTION = "GEOMETRYCOLLECTION(LINESTRING(0 0,1 0,1 1,0 0))";
	private static final String POLYGON_COLLECTION = "GEOMETRYCOLLECTION(POLYGON((0 0,0 1,1 1,1 0,0 0)))";
	private static final String HETEROGENEOUS_COLLECTION =
			"GEOMETRYCOLLECTION(POINT(0 0),LINESTRING(1 1,2 2))";
	private static final String NESTED_COLLECTION =
			"GEOMETRYCOLLECTION(GEOMETRYCOLLECTION(POINT(0 0)),LINESTRING(1 1,2 2))";
	private static final String EMPTY_COLLECTION = "GEOMETRYCOLLECTION EMPTY";
	private static final List<String> COLLECTION_LITERALS = List.of(
			POINT_COLLECTION,
			CLOSED_LINE_COLLECTION,
			POLYGON_COLLECTION,
			HETEROGENEOUS_COLLECTION,
			NESTED_COLLECTION,
			EMPTY_COLLECTION);

	@BeforeClass
	public static void registerFunctions() {
		GeoSparqlFunctionRegistration.registerAll();
	}

	@Test
	public void sfEqualsAgreesWithRelateForGenericCollections() throws Exception {
		assertEqualsAgreesWithRelate(GeoConstants.GEOF_SF_EQUALS, COLLECTION_LITERALS);
	}

	@Test
	public void ehEqualsAgreesWithRelateForGenericCollections() throws Exception {
		assertEqualsAgreesWithRelate(GeoConstants.GEOF_EH_EQUALS, COLLECTION_LITERALS);
	}

	@Test
	public void identicalPointCollectionsDoNotMatchEqualsPattern() throws Exception {
		assertRelateEquals(false, POINT_COLLECTION);
	}

	@Test
	public void identicalClosedLineCollectionsDoNotMatchEqualsPattern() throws Exception {
		assertRelateEquals(false, CLOSED_LINE_COLLECTION);
	}

	@Test
	public void identicalPolygonCollectionsMatchEqualsPattern() throws Exception {
		assertRelateEquals(true, POLYGON_COLLECTION);
	}

	@Test
	public void emptyCollectionsDoNotMatchEqualsPattern() throws Exception {
		assertRelateEquals(false, EMPTY_COLLECTION);
	}

	@Test
	public void pointCollectionAndEquivalentMultipointAgreeWithRelate() throws Exception {
		Literal collection = wkt(POINT_COLLECTION);
		Literal multipoint = wkt("MULTIPOINT((0 0))");
		Value relateResult = evaluate(GeoConstants.GEOF_RELATE, collection, multipoint,
				VF.createLiteral(EQUALS_PATTERN));
		assertEquals(relateResult, evaluate(GeoConstants.GEOF_SF_EQUALS, collection, multipoint));
		assertEquals(relateResult, evaluate(GeoConstants.GEOF_EH_EQUALS, collection, multipoint));
		assertEquals(VF.createLiteral(false), relateResult);
	}

	private static void assertEqualsAgreesWithRelate(IRI equalsFunction, List<String> wkts) throws Exception {
		for (String wktLexical : wkts) {
			Literal geometry = wkt(wktLexical);
			Value relateResult = evaluate(GeoConstants.GEOF_RELATE, geometry, geometry,
					VF.createLiteral(EQUALS_PATTERN));
			Value equalsResult = evaluate(equalsFunction, geometry, geometry);
			assertEquals(equalsFunction + " must match geof:relate " + EQUALS_PATTERN + " for " + wktLexical,
					relateResult, equalsResult);
		}
	}

	private static void assertRelateEquals(boolean expected, String wktLexical) throws Exception {
		Literal geometry = wkt(wktLexical);
		assertEquals(VF.createLiteral(expected),
				evaluate(GeoConstants.GEOF_RELATE, geometry, geometry, VF.createLiteral(EQUALS_PATTERN)));
	}

	private static Literal wkt(String lexical) {
		return VF.createLiteral(lexical, GeoConstants.GEO_WKT_LITERAL);
	}

	private static Value evaluate(IRI functionUri, Value... args) {
		Function function = FunctionRegistry.getInstance()
				.get(functionUri.stringValue())
				.orElseThrow(() -> new AssertionError("Function not registered: " + functionUri));
		return function.evaluate(TRIPLE_SOURCE, args);
	}
}
