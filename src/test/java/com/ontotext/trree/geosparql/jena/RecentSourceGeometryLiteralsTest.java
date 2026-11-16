package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class RecentSourceGeometryLiteralsTest {
	private static final ValueFactory VALUES = SimpleValueFactory.getInstance();
	private static final String MERCATOR = "<http://www.opengis.net/def/crs/EPSG/0/3857> ";
	private static final String CRS84_POLYGON = "POLYGON((15 60,16 60,16 61,15 61,15 60))";
	// CRS84 and Mercator geometries inside the polygon, outside it and across its edge.
	private static final List<String> CHANGING = List.of(
			"POINT(15.5 60.5)",
			"POINT(14 59)",
			"LINESTRING(14.5 60.5,15.5 60.5)",
			MERCATOR + "POINT(1725000 8500000)",
			MERCATOR + "POINT(1600000 8500000)",
			MERCATOR + "LINESTRING(1600000 8500000,1725000 8500000)");

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void valueSeenAgainIsNotConvertedAgain() {
		SourceGeometryLiteral first = RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"));

		assertSame(first, RecentSourceGeometryLiterals.of(wkt("POINT(1 1)")));
		assertEquals("POINT(1 1)", first.lexicalForm());
	}

	/** One geometry stays the same while the other changes with every call, on either side of the function. */
	@Test
	public void unchangingValueIsKeptWhileTheOtherValueChanges() {
		SourceGeometryLiteral unchanging = RecentSourceGeometryLiterals.of(wkt(CRS84_POLYGON));
		for (String changing : CHANGING) {
			assertEquals(changing, RecentSourceGeometryLiterals.of(wkt(changing)).lexicalForm());
			assertSame(unchanging, RecentSourceGeometryLiterals.of(wkt(CRS84_POLYGON)));
		}
		for (String changing : CHANGING) {
			assertSame(unchanging, RecentSourceGeometryLiterals.of(wkt(CRS84_POLYGON)));
			assertEquals(changing, RecentSourceGeometryLiterals.of(wkt(changing)).lexicalForm());
		}
	}

	@Test
	public void thirdValueReplacesTheEarlierOfTheTwo() {
		SourceGeometryLiteral first = RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"));
		SourceGeometryLiteral second = RecentSourceGeometryLiterals.of(wkt("POINT(2 2)"));
		RecentSourceGeometryLiterals.of(wkt("POINT(3 3)"));

		assertSame(second, RecentSourceGeometryLiterals.of(wkt("POINT(2 2)")));
		assertNotSame(first, RecentSourceGeometryLiterals.of(wkt("POINT(1 1)")));
	}

	@Test
	public void sameTextWithAnotherDatatypeIsAnotherValue() {
		SourceGeometryLiteral typed = RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"));

		assertNotSame(typed, RecentSourceGeometryLiterals.of(VALUES.createLiteral("POINT(1 1)")));
	}

	@Test
	public void valueThatIsNotAGeometryLiteralIsRejectedAndKeepsTheRecentValues() {
		SourceGeometryLiteral recent = RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"));

		assertThrows(JenaGeoSparqlException.class,
				() -> RecentSourceGeometryLiterals.of(VALUES.createIRI("https://example.org/feature")));
		assertThrows(JenaGeoSparqlException.class, () -> RecentSourceGeometryLiterals.of(null));
		assertSame(recent, RecentSourceGeometryLiterals.of(wkt("POINT(1 1)")));
	}

	@Test
	public void eachThreadKeepsItsOwnValues() throws Exception {
		SourceGeometryLiteral here = RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"));
		AtomicReference<SourceGeometryLiteral> elsewhere = new AtomicReference<>();
		Thread thread = new Thread(() -> elsewhere.set(RecentSourceGeometryLiterals.of(wkt("POINT(1 1)"))));
		thread.start();
		thread.join();

		assertNotSame(here, elsewhere.get());
		assertSame(here, RecentSourceGeometryLiterals.of(wkt("POINT(1 1)")));
	}

	/**
	 * A function called with an unchanging geometry gives, for every relation and on either side, the result of
	 * evaluating the two source geometry literals on their own.
	 */
	@Test
	public void functionWithAnUnchangingGeometryGivesTheResultOfEachPairOnItsOwn() throws Exception {
		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			String function = GeoConstants.NS_GEOF + relation.getPredicateUri().getLocalName();
			for (String changing : CHANGING) {
				assertEquals(relation + " " + changing + " / unchanging",
						relation.evaluate(SourceGeometryLiteral.fromWkt(changing),
								SourceGeometryLiteral.fromWkt(CRS84_POLYGON)),
						((Literal) JenaFunctionEvaluator.evaluate(VALUES, function, wkt(changing),
								wkt(CRS84_POLYGON))).booleanValue());
				assertEquals(relation + " unchanging / " + changing,
						relation.evaluate(SourceGeometryLiteral.fromWkt(CRS84_POLYGON),
								SourceGeometryLiteral.fromWkt(changing)),
						((Literal) JenaFunctionEvaluator.evaluate(VALUES, function, wkt(CRS84_POLYGON),
								wkt(changing))).booleanValue());
			}
		}
	}

	private static Literal wkt(String lexicalForm) {
		return VALUES.createLiteral(lexicalForm, GeoConstants.GEO_WKT_LITERAL);
	}
}
