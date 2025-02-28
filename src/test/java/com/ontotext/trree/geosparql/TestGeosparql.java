package com.ontotext.trree.geosparql;

import com.ontotext.graphdb.Config;
import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.test.functional.base.SingleRepositoryFunctionalTest;
import com.ontotext.test.utils.StandardUtils;
import com.useekm.geosparql.*;
import com.useekm.indexing.GeoConstants;
import org.apache.commons.lang.Validate;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
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

	@Test public void unaryOneArg() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.EXT_AREA + ">(?value) = 0)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void unaryTwoArgs() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.EXT_AREA + ">(?value,?value) = 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void binaryGeometryArgs() throws RDF4JException {
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

	@Test public void binaryRelateGeometryArgs() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:3"), vf().createLiteral("POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value2, ?value1, <opt:##byrdf>))}");
		assertEquals(1, count(tq.evaluate()));
		tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value1, ?value2, <opt:##byrdf>))}");
		assertEquals(0, count(tq.evaluate()));
		tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:3> ?value3 . FILTER(<" + GeoConstants.GEOF_SF_WITHIN + ">(?value3, ?value1, <opt:##byrdf>))}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void binaryOneArg() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?dist WHERE { <u:1> <u:1> ?value . FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test(expected = ValueExprEvaluationException.class) public void relateOneArg() throws RDF4JException {
		new Relate().evaluate(vf(), asLiteral("POINT(0 0)"));
	}

	@Test(expected = ValueExprEvaluationException.class) public void relateTwoArgs() throws RDF4JException {
		//uses a different code patrh as oneArg, (AbstractBooleanBinaryFunction throws for < 2 args, or Relate throws for < 3 args)
		new Relate().evaluate(vf(), asLiteral("POINT(0 0)"), asLiteral("POINT(0 0)"));
	}

	@Test public void relateFourArgs() throws RDF4JException {
		//extra arguments are currently just ignored...
		new Relate().evaluate(vf(), asLiteral("POINT(0 0)"), asLiteral("POINT(0 0)"), vf().createLiteral("T**FF*FF*"), vf().createLiteral(false));
	}

	@Test(expected = ValueExprEvaluationException.class) public void relateInvalidPattern() throws RDF4JException {
		new Relate().evaluate(vf(), asLiteral("POINT(0 0)"), asLiteral("POINT(0 0)"), vf().createLiteral("wrong"));
	}

	@Test public void distance() throws RDF4JException {
		assertEquals(1.0611006, ((Literal)new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"))).doubleValue(), 0.00001);
		assertEquals(73549, (int)((Literal)new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				UnitsOfMeasure.URI_METRE)).doubleValue());
		assertEquals(73.54954, ((Literal)new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"),
				UnitsOfMeasure.URI_KILOMETRE)).doubleValue(), 0.00001);
	}

	@Test(expected = ValueExprEvaluationException.class) public void distanceInvalidUnitOfMeasure() throws RDF4JException {
		new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"), SimpleValueFactory.getInstance().createIRI("u:unknownMeasure"));
	}

	@Test(expected = ValueExprEvaluationException.class) public void distanceUnitOfMeasureNotAnUri() throws RDF4JException {
		new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"), vf().createLiteral("not_a_measure"));
	}

	@Test(expected = ValueExprEvaluationException.class) public void distanceUnitOfMeasureFourArgs() throws RDF4JException {
		new Distance().evaluate(vf(), asLiteral("POINT(4.9186383 52.3563603)"), asLiteral("POINT(5.96957 52.20981)"), UnitsOfMeasure.URI_METRE, vf().createLiteral(""));
	}

	@Test(expected = ValueExprEvaluationException.class) public void distanceInvalidGeometry() throws RDF4JException {
		new Distance().evaluate(vf(), asLiteral("POINT(200 200)"), asLiteral("POINT(300 300)"), UnitsOfMeasure.URI_METRE);
	}

	@Test public void binaryThreeArgs() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POINT(1 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL, "SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . "
				+ "FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2, <http://www.opengis.net/def/uom/OGC/1.0/metre>) > 0)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void binaryNonGeometryArgs() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("AAP"));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createLiteral("POINT(1 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void binaryNonLiterals() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createIRI("u:1"));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_DISTANCE + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void geomDouble() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, \"0.0\") = \"POLYGON EMPTY\"^^<" + GeoConstants.XMLSCHEMA_OGC_WKT + ">)}");
		assertEquals(1, count(tq.evaluate()));
	}

	@Test public void geomDoubleOneArg() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1) = \"POLYGON EMPTY\"^^<" + GeoConstants.XMLSCHEMA_OGC_WKT + ">)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void geomDoubleNonDouble1() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:2"), vf().createIRI("u:1"));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . <u:1> <u:2> ?value2 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, ?value2) > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void geomDoubleNonDouble2() throws RDF4JException {
		conn().add(vf().createIRI("u:1"), vf().createIRI("u:1"), vf().createLiteral("POINT(0 0)", GeoConstants.XMLSCHEMA_OGC_WKT));
		TupleQuery tq = conn().prepareTupleQuery(QueryLanguage.SPARQL,
				"SELECT ?value1 ?value2 WHERE { <u:1> <u:1> ?value1 . " +
						"FILTER(<" + GeoConstants.GEOF_BUFFER + ">(?value1, \"value\") > 0)}");
		assertEquals(0, count(tq.evaluate()));
	}

	@Test public void functions() throws ValueExprEvaluationException {
		assertEquals("1.0", new HausdorffDistance().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals("MULTIPOINT ((0 0), (1 1))", new Boundary().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)")).stringValue());
		assertEquals("POINT (1 1)", new ClosestPoint().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals("LINESTRING (1 1, 1 1)", new ShortestLine().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals("LINESTRING EMPTY", new Difference().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals("POLYGON ((0 0, 0 1, 1 1, 1 0, 0 0))", new Envelope().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)")).stringValue());
		assertEquals("LINESTRING (0 0, 1 1)", new Intersection().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals("LINESTRING (0 0, 1 1)", new Simplify().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), vf().createLiteral("0.0")).stringValue());
		assertEquals("LINESTRING (0 0, 1 1)", new SimplifyPreserveTopology().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), vf().createLiteral("0.0")).stringValue());
		assertEquals("LINESTRING (0 0, 1 1)", new Union().evaluate(vf(), asLiteral("LINESTRING(0 0, 1 1)"), asLiteral("LINESTRING(1 1, 0 0)")).stringValue());
		assertEquals(vf().createLiteral(false), new Within().evaluate(vf(), asLiteral("POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Within().evaluate(vf(), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))"), asLiteral("POLYGON((0 0, 0 2, 2 2, 2 0, 0 0))")));
		assertEquals(vf().createLiteral(true), new Within().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false),
				new Relate().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))"), vf().createLiteral("T**FF*FF*")));
		assertEquals(vf().createLiteral(false), new Contains().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false),
				new ContainsProperly().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(true),
				new ContainsProperly().evaluate(vf(), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))"), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))")));
		assertEquals(vf().createLiteral(true), new CoveredBy().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Covers().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Crosses().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Disjoint().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Equals().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(true), new Intersects().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Overlaps().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
		assertEquals(vf().createLiteral(false), new Touches().evaluate(vf(), asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))"), asLiteral("POLYGON((1 1, 1 4, 4 4, 4 1, 1 1))")));
	}

	@Test public void invalidGeos() throws RDF4JException {
		Literal invalidGeo = asLiteral("POLYGON((2 2, 3 3, 3 2, 2 3, 2 2))");
		Literal validGeo = asLiteral("POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))");
		assertFalse(((Literal)new IsValid().evaluate(vf(), invalidGeo)).booleanValue());
		assertTrue(((Literal)new IsValid().evaluate(vf(), validGeo)).booleanValue());
		assertDifferenceThrows(invalidGeo, validGeo);
		assertDifferenceThrows(validGeo, invalidGeo);
		assertDifferenceThrows(invalidGeo, invalidGeo);
	}

	private void assertDifferenceThrows(Literal geo1, Literal geo2) {
		ValueExprEvaluationException exc = null;
		try {
			new Difference().evaluate(vf(), geo1, geo2);
		} catch (ValueExprEvaluationException e) {
			exc = e;
		}
		assertNotNull(exc);
	}

	@Test public void isSimple() throws RDF4JException {
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_IS_SIMPLE + ">(\"POINT(1 1)\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.GEO_IS_SIMPLE + ">(\"LINESTRING(1 1, 3 1, 2 2, 2 0)\"))}").evaluate());
	}

	@Test public void isValid() throws RDF4JException {
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POINT(1 1)\"))}").evaluate());
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"LINESTRING(1 1, 3 1, 2 2, 2 0)\"))}").evaluate());
		assertTrue(conn().prepareBooleanQuery(QueryLanguage.SPARQL, "ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POLYGON((2 2, 2 3, 3 3, 3 2, 2 2))\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL,
				"ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"POLYGON((2 2, 3 3, 3 2, 2 3, 2 2))\"))}").evaluate());
		assertFalse(conn().prepareBooleanQuery(QueryLanguage.SPARQL,
				"ASK WHERE { FILTER(<" + GeoConstants.EXT_IS_VALID + ">(\"NOT A GEO\"))}").evaluate());
	}

	@Test public void dimension() throws RDF4JException {
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

	@Test public void getName() {
		for (Function ofun: FunctionRegistry.getInstance().getAll()) {
			if (ofun instanceof AbstractFunction) {
				AbstractFunction fun = (AbstractFunction)ofun;
				if (fun.getURI().contains("/sf"))
					validateSfName(fun);
				else
					validateName(fun);
			}
		}
	}

	protected void validateName(AbstractFunction fun) {
		String clazzName = fun.getClass().getSimpleName();
		String expectedFunName = (Character.toLowerCase(clazzName.charAt(0))) + clazzName.substring(1);
		String name = fun.getURI();
		int idx = Math.max(name.lastIndexOf('#'), name.lastIndexOf('/'));
		name = name.substring(idx + 1);
		assertEquals(expectedFunName, name);
	}

	protected void validateSfName(AbstractFunction fun) {
		String clazzName = fun.getClass().getSimpleName();
		String name = fun.getURI();
		int idx = Math.max(name.lastIndexOf('#'), name.lastIndexOf('/'));
		name = name.substring(idx + 1);
		assertEquals("sf" + clazzName, name);
	}

	private Literal asLiteral(String geom) {
		Validate.notNull(geom);
		return vf().createLiteral(geom, GeoConstants.XMLSCHEMA_OGC_WKT);
	}
}
