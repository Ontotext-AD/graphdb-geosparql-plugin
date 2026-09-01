package com.ontotext.trree.geosparql;

import com.ontotext.graphdb.Config;
import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.test.functional.base.SingleRepositoryFunctionalTest;
import com.ontotext.test.utils.StandardUtils;
import com.ontotext.trree.geosparql.jena.GeoSparqlUnits;
import com.ontotext.trree.geosparql.jena.JenaFunctionEvaluator;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.commons.lang.Validate;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.junit.*;

import static org.junit.Assert.*;

/**
 * Created by avataar on 06.04.2015..
 */
public class TestGeosparql extends SingleRepositoryFunctionalTest {
	@ClassRule
	public static TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	private RepositoryConnection connection;

	@Override
	protected RepositoryConfig createRepositoryConfiguration() {
		return StandardUtils.createOwlimSe("empty");
	}

	@BeforeClass
	public static void setWorkDir() {
		System.setProperty("graphdb.home.work", String.valueOf(tmpFolder.getRoot()));
		Config.reset();
	}
	@AfterClass
	public static void resetWorkDir() {
		System.clearProperty("graphdb.home.work");
		Config.reset();
	}

	@Before
	public void setupConn() throws RepositoryException {
		connection = getRepository().getConnection();
	}

	@After
	public void closeConn() throws RepositoryException {
		connection.close();
	}

	private RepositoryConnection conn() {
		return connection;
	}

	private ValueFactory vf() {
		return connection.getValueFactory();
	}

	public static int count(CloseableIteration<?> statements) {
		int result = 0;
		try {
			while (statements.hasNext()) {
				++result;
				statements.next();
			}
		} finally {
			statements.close();
		}
		return result;
	}

	@Test public void areaFunctionReturnsResultForOneGeometryArgument() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.EXT_AREA + ">(?value) = 0)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void areaFunctionTypeErrorsOnExtraArgumentInFilter() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.EXT_AREA + ">(?value,?value) = 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void distanceFunctionFiltersGeometryArguments() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POINT(1 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) = 1.0)}");
		assertEquals(1, count(tq.evaluate()));
		tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) = 1.1)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void relationFunctionTypeErrorsOnExtraArgument() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:3"), vf().createLiteral("POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value2, ?value1, <opt:##byrdf>))}");
		assertEquals(0, count(tq.evaluate()));
		tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value1, ?value2, <opt:##byrdf>))}");
		assertEquals(0, count(tq.evaluate()));
		tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:3> ?value3 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value3, ?value1, <opt:##byrdf>))}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void distanceFunctionTypeErrorsOnMissingArgumentInFilter() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void relateRejectsOneArgument() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_RELATE, asLiteral("POINT(0 0)")));
	}

	@Test public void relateRejectsTwoArguments() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_RELATE, asLiteral("POINT(0 0)"), asLiteral("POINT(0 0)")));
	}

	@Test public void relateRejectsFourArguments() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_RELATE, asLiteral("POINT(0 0)"), asLiteral("POINT(0 0)"),
						vf().createLiteral("T**FF*FF*"), vf().createLiteral(false)));
	}

	@Test public void distance() throws RDF4JException {
		assertEquals(1.0611006, ((Literal)evaluate(GeoConstants.GEOF_DISTANCE, asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"))).doubleValue(), 0.00001);
		assertEquals(73322, (int)((Literal)evaluate(GeoConstants.GEOF_DISTANCE, asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				GeoSparqlUnits.URI_METRE)).doubleValue());
		assertEquals(73.322403, ((Literal)evaluate(GeoConstants.GEOF_DISTANCE, asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				GeoSparqlUnits.URI_KILOMETRE)).doubleValue(), 0.00001);
	}

	@Test public void distanceRejectsUnknownUnitOfMeasure() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class, () -> evaluate(GeoConstants.GEOF_DISTANCE,
				asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				SimpleValueFactory.getInstance().createIRI("u:unknownMeasure")));
	}

	@Test public void distanceRejectsLiteralUnitOfMeasure() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class, () -> evaluate(GeoConstants.GEOF_DISTANCE,
				asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				vf().createLiteral("not_a_measure")));
	}

	@Test public void distanceRejectsFourthArgument() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class, () -> evaluate(GeoConstants.GEOF_DISTANCE,
				asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				GeoSparqlUnits.URI_METRE, vf().createLiteral("")));
	}

	@Test public void distanceRejectsCoordinatesOutsideCrsDomain() throws RDF4JException {
		assertThrows(ValueExprEvaluationException.class, () -> evaluate(GeoConstants.GEOF_DISTANCE,
				asLiteral("POINT(200 200)"), asLiteral("POINT(300 300)"), GeoSparqlUnits.URI_METRE));
	}

	@Test public void distanceFunctionAcceptsUnitArgumentInFilter() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POINT(1 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . "
				+ "FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2, <http://www.opengis.net/def/uom/OGC/1.0/metre>) > 0)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void distanceFunctionTypeErrorsOnNonGeometryLiteral() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("AAP"));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POINT(1 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void distanceFunctionTypeErrorsOnIriArguments() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createIRI("u:1"));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void bufferFunctionAcceptsLexicalDoubleArgument() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, \"0.0\") = \"POLYGON EMPTY\"^^<" + GeoConstants.XMLSCHEMA_OGC_WKT + ">)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void bufferFunctionTypeErrorsOnMissingDistance() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1) = \"POLYGON EMPTY\"^^<" + GeoConstants.XMLSCHEMA_OGC_WKT + ">)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void bufferFunctionTypeErrorsOnIriDistance() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void bufferFunctionTypeErrorsOnNonNumericDistance() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, \"value\") > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void invalidGeometriesReportValidityAndRejectDifference() throws RDF4JException {
		Literal invalidGeo = asLiteral("POLYGON((2 2, 3 3, 3 2, 2 3, 2 2))");
		Literal validGeo = asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))");
		assertFalse(((Literal)evaluate(GeoConstants.EXT_IS_VALID, invalidGeo)).booleanValue());
		assertTrue(((Literal)evaluate(GeoConstants.EXT_IS_VALID, validGeo)).booleanValue());
		assertDifferenceThrows(invalidGeo, validGeo);
		assertDifferenceThrows(validGeo, invalidGeo);
		assertDifferenceThrows(invalidGeo, invalidGeo);
	}

	private void assertDifferenceThrows(Literal geo1, Literal geo2) {
		assertThrows(ValueExprEvaluationException.class,
				() -> evaluate(GeoConstants.GEOF_DIFFERENCE, geo1, geo2));
	}

	@Test public void isSimpleFunctionEvaluatesThroughSparql() throws RDF4JException {
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_IS_SIMPLE + ">(\"POINT(1 1)\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_IS_SIMPLE + ">(\"LINESTRING(1 1, 3 1, 2 2, 2 0)\"))}").evaluate());
	}

	@Test public void isValidFunctionEvaluatesThroughSparql() throws RDF4JException {
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POINT(1 1)\"))}").evaluate());
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"LINESTRING(1 1, 3 1, 2 2, 2 0)\"))}").evaluate());
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL,
				"ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POLYGON((2 2, 3 3, 3 2, 2 3, 2 2))\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL,
				"ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"NOT A GEO\"))}").evaluate());
	}

	@Test public void dimensionFunctionEvaluatesThroughSparql() throws RDF4JException {
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_DIMENSION + ">(\"POINT(1 1)\") = 0)}").evaluate());
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_DIMENSION + ">(\"LINESTRING(1 1, 3 1, 2 2, 2 0)\") = 1)}")
				.evaluate());
		// isValid returns false:
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_DIMENSION + ">(\"POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))\") = 2)}")
				.evaluate());
		// isvalid throws type-error:
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_DIMENSION + ">(\"POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))\") = 1)}")
				.evaluate());
	}

	private Value evaluate(IRI functionUri, Value... args) throws ValueExprEvaluationException {
		return JenaFunctionEvaluator.evaluate(vf(), functionUri.stringValue(), args);
	}

	private Literal asLiteral(String geom) {
		Validate.notNull(geom);
		return vf().createLiteral(geom, GeoConstants.XMLSCHEMA_OGC_WKT);
	}
}
