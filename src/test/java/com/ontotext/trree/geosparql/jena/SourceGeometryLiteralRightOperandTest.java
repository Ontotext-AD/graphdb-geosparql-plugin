package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SourceGeometryLiteralRightOperandTest {
	private static final String MERCATOR = "<http://www.opengis.net/def/crs/EPSG/0/3857> ";
	private static final String UTM_33N = "<http://www.opengis.net/def/crs/EPSG/0/32633> ";
	// A CRS84 polygon around 15 E 60 N and Mercator sources inside it, outside it, across its edge and on it.
	private static final String CRS84_POLYGON = "POLYGON((15 60,16 60,16 61,15 61,15 60))";
	private static final List<String> MERCATOR_SOURCES = List.of(
			MERCATOR + "POINT(1725000 8500000)",
			MERCATOR + "POINT(1600000 8500000)",
			MERCATOR + "LINESTRING(1600000 8500000,1725000 8500000)",
			MERCATOR + "POLYGON((1700000 8450000,1750000 8450000,1750000 8550000,1700000 8550000,"
					+ "1700000 8450000))",
			MERCATOR + "POLYGON((1600000 8300000,1900000 8300000,1900000 8700000,1600000 8700000,"
					+ "1600000 8300000))",
			MERCATOR + "POINT(1669792.36 8399737.89)");

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void sourceInTheLeftCrsIsItsOwnRightOperand() throws Exception {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(CRS84_POLYGON);
		GeometryWrapper left = SourceGeometryLiteral.fromWkt("POINT(15.5 60.5)").asGeometryWrapper();

		assertSame(source.asGeometryWrapper(), source.asRightOperandFor(left));
	}

	@Test
	public void sourceInAnotherCrsIsTransformedOncePerLeftCrs() throws Exception {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(CRS84_POLYGON);
		GeometryWrapper mercator = SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(0)).asGeometryWrapper();
		GeometryWrapper otherMercator = SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(1)).asGeometryWrapper();
		GeometryWrapper utm = SourceGeometryLiteral.fromWkt(UTM_33N + "POINT(500000 6700000)").asGeometryWrapper();

		GeometryWrapper inMercator = source.asRightOperandFor(mercator);
		GeometryWrapper inUtm = source.asRightOperandFor(utm);

		assertEquals(mercator.getSrsURI(), inMercator.getSrsURI());
		assertEquals(utm.getSrsURI(), inUtm.getSrsURI());
		assertNotSame(inMercator, inUtm);
		assertEquals(mercator.checkTransformSRS(source.asGeometryWrapper()).getXYGeometry(),
				inMercator.getXYGeometry());
		assertEquals(utm.checkTransformSRS(source.asGeometryWrapper()).getXYGeometry(), inUtm.getXYGeometry());
		assertSame(inMercator, source.asRightOperandFor(otherMercator));
		assertSame(inMercator, source.asRightOperandFor(mercator));
		assertSame(inUtm, source.asRightOperandFor(utm));
	}

	/**
	 * A source keeps right operands up to a number of coordinates in all. Past that, it transforms again for each
	 * evaluation and gives the same operand.
	 */
	@Test
	public void sourceKeepsRightOperandsUpToItsCoordinateLimit() throws Exception {
		StringBuilder line = new StringBuilder("LINESTRING(15 60.5");
		for (int i = 1; i < 2 * SourceGeometryLiteral.MAX_KEPT_RIGHT_OPERAND_COORDINATES / 3; i++) {
			line.append(',').append(15 + i * 0.000005).append(" 60.5");
		}
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(line.append(')').toString());
		String mercator = "http://www.opengis.net/def/crs/EPSG/0/3857";
		String utm33 = "http://www.opengis.net/def/crs/EPSG/0/32633";

		GeometryWrapper inMercator = source.asRightOperandIn(mercator);
		GeometryWrapper inUtm = source.asRightOperandIn(utm33);
		GeometryWrapper inUtmAgain = source.asRightOperandIn(utm33);

		assertSame(inMercator, source.asRightOperandIn(mercator));
		assertNotSame(inUtm, inUtmAgain);
		assertEquals(inUtm.getXYGeometry(), inUtmAgain.getXYGeometry());
	}

	/**
	 * Jena keeps a latitude-first operand in both axis orders, and both count against the limit. A line of three
	 * fifths of the limit is kept in a CRS with east-north axes but not in a latitude-first one.
	 */
	@Test
	public void latitudeFirstRightOperandCountsBothAxisOrdersAgainstTheLimit() throws Exception {
		StringBuilder line = new StringBuilder("LINESTRING(15 60.5");
		for (int i = 1; i < 3 * SourceGeometryLiteral.MAX_KEPT_RIGHT_OPERAND_COORDINATES / 5; i++) {
			line.append(',').append(15 + i * 0.000005).append(" 60.5");
		}
		String wkt = line.append(')').toString();
		String mercator = "http://www.opengis.net/def/crs/EPSG/0/3857";
		String wgs84 = "http://www.opengis.net/def/crs/EPSG/0/4326";
		SourceGeometryLiteral eastNorthSource = SourceGeometryLiteral.fromWkt(wkt);
		SourceGeometryLiteral latitudeFirstSource = SourceGeometryLiteral.fromWkt(wkt);

		GeometryWrapper inMercator = eastNorthSource.asRightOperandIn(mercator);
		GeometryWrapper inWgs84 = latitudeFirstSource.asRightOperandIn(wgs84);

		assertSame(inMercator.getParsingGeometry(), inMercator.getXYGeometry());
		assertSame(inMercator, eastNorthSource.asRightOperandIn(mercator));
		assertNotSame(inWgs84.getParsingGeometry(), inWgs84.getXYGeometry());
		assertNotSame(inWgs84, latitudeFirstSource.asRightOperandIn(wgs84));
	}

	/**
	 * Each component of a geometry counts against the limit besides its coordinates, so a source of many small parts
	 * keeps no right operand once they take more than the limit, though its coordinates alone would fit.
	 */
	@Test
	public void sourceOfManyPartsKeepsNoRightOperandPastTheLimit() throws Exception {
		StringBuilder parts = new StringBuilder("MULTIPOLYGON(");
		for (int i = 0; i < 40_000; i++) {
			double x = 15 + i % 200 * 0.004;
			double y = 60 + i / 200 * 0.004;
			parts.append(i == 0 ? "((" : ",((").append(x).append(' ').append(y).append(',').append(x + 0.001)
					.append(' ').append(y).append(',').append(x).append(' ').append(y + 0.001).append(',').append(x)
					.append(' ').append(y).append("))");
		}
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(parts.append(')').toString());
		String mercator = "http://www.opengis.net/def/crs/EPSG/0/3857";

		GeometryWrapper inMercator = source.asRightOperandIn(mercator);

		assertTrue(inMercator.getXYGeometry().getNumPoints()
				< SourceGeometryLiteral.MAX_KEPT_RIGHT_OPERAND_COORDINATES);
		assertNotSame(inMercator, source.asRightOperandIn(mercator));
	}

	/** An empty member counts against the limit like any other component: a polygon and its ring. */
	@Test
	public void emptyMembersCountTowardsTheLimit() {
		GeometryFactory factory = new GeometryFactory();
		Geometry[] members = new Geometry[1_001];
		members[0] = factory.createPoint(new Coordinate(15, 60));
		for (int i = 1; i < members.length; i++) {
			members[i] = factory.createPolygon();
		}

		assertEquals(1 + SourceGeometryLiteral.COMPONENT_COORDINATES * (2 + 2 * 1_000L),
				SourceGeometryLiteral.keptCoordinates(factory.createGeometryCollection(members)));
	}

	/**
	 * A right operand evaluated against many left operands gives each relation the result of a source that Jena
	 * transforms for that evaluation alone.
	 */
	@Test
	public void keptRightOperandGivesTheResultOfTransformingForEachEvaluation() {
		SourceGeometryLiteral kept = SourceGeometryLiteral.fromWkt(CRS84_POLYGON);
		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			for (String left : MERCATOR_SOURCES) {
				assertEquals(relation + " " + left,
						relation.evaluate(SourceGeometryLiteral.fromWkt(left),
								SourceGeometryLiteral.fromWkt(CRS84_POLYGON)),
						relation.evaluate(SourceGeometryLiteral.fromWkt(left), kept));
			}
		}
		assertTrue(GeoSparqlPropertyRelation.SF_WITHIN.evaluate(
				SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(0)), kept));
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(1)), kept));
	}

	/** A generic collection is related by its own evaluation path, which uses the kept right operand as well. */
	@Test
	public void keptCollectionRightOperandGivesTheResultOfTransformingForEachEvaluation() {
		String collection = "GEOMETRYCOLLECTION(" + CRS84_POLYGON + ",POINT(14 59))";
		SourceGeometryLiteral kept = SourceGeometryLiteral.fromWkt(collection);
		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			for (String left : MERCATOR_SOURCES) {
				assertEquals(relation + " " + left,
						relation.evaluate(SourceGeometryLiteral.fromWkt(left),
								SourceGeometryLiteral.fromWkt(collection)),
						relation.evaluate(SourceGeometryLiteral.fromWkt(left), kept));
			}
		}
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(0)), kept));
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				SourceGeometryLiteral.fromWkt(MERCATOR_SOURCES.get(1)), kept));
	}
}
