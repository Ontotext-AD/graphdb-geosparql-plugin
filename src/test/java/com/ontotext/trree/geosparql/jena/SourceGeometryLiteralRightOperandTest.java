package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
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
