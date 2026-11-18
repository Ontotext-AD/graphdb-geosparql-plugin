package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.jena.JenaFunctionEvaluator;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import com.ontotext.trree.sdk.PluginException;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SourceGeometryWkbCodecTest {
	@Test
	public void truncatedWkbRequiresForceReindex() {
		assertForceReindexMessage(assertThrows(PluginException.class,
				() -> SourceGeometryWkbCodec.decode(new byte[]{1, 2, 3})));
	}

	@Test
	public void javaSerializedGeometryRequiresForceReindex() throws Exception {
		Geometry geometry = SourceGeometryLiteral.fromWkt("POINT(1 2)")
				.asGeometryWrapper().getXYGeometry();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(geometry);
		}

		assertForceReindexMessage(assertThrows(PluginException.class,
				() -> SourceGeometryWkbCodec.decode(bytes.toByteArray())));
	}

	/**
	 * The WKB of a source reproduces it when it reads back as the geometry Jena parses, which ordinary geometries do,
	 * heights and measures included. It does not reproduce a collection with an empty member, which reading WKB back
	 * drops; a collection whose members differ in coordinate dimension, which WKB writes in one; or a geometry with
	 * heights or measures in a latitude-first CRS, whose XY geometry reads back with ordinates Jena's parse leaves out.
	 */
	@Test
	public void sourceWkbReproducesOnlyWhatReadsBackAsJenaParsesIt() {
		for (String wkt : List.of("POINT(153.03 -27.47)", "POLYGON ZM((0 0 1 2,1 0 3 4,1 1 5 6,0 0 1 2))",
				"<http://www.opengis.net/def/crs/EPSG/0/3857> MULTIPOLYGON Z(((0 0 1,10 0 2,10 10 3,0 0 1)))",
				"<http://www.opengis.net/def/crs/EPSG/0/4326> POLYGON((-27 153,-27 154,-26 154,-27 153))",
				"GEOMETRYCOLLECTION(POINT(1 2),LINESTRING(0 0,1 1))")) {
			SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(wkt);
			assertTrue(wkt, SourceGeometryWkbCodec.reproduces(source, SourceGeometryWkbCodec.encode(source)));
		}
		for (String wkt : List.of("GEOMETRYCOLLECTION(POINT(1 2),POLYGON EMPTY)",
				"GEOMETRYCOLLECTION Z(POINT(1 2),POINT Z(1 2 3))",
				"<http://www.opengis.net/def/crs/EPSG/0/7844> POLYGON ZM((-27 153 1 2,-27 154 3 4,-26 154 5 6,-27 153 1 2))",
				"<http://www.opengis.net/def/crs/EPSG/0/4979> POINT Z(-27.47 153.03 55)")) {
			SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(wkt);
			assertFalse(wkt, SourceGeometryWkbCodec.reproduces(source, SourceGeometryWkbCodec.encode(source)));
		}
	}

	@Test
	public void sourceWkbPreservesCoordinateSequenceDimensionsAndOrdinates() {
		assertPointRoundTrip("POINT(1 2)", 2, 2, Double.NaN, Double.NaN);
		assertPointRoundTrip("POINT Z(1 2 3)", 3, 3, 3, Double.NaN);
		assertPointRoundTrip("POINT M(1 2 4)", 3, 2, Double.NaN, 4);
		assertPointRoundTrip("POINT ZM(1 2 3 4)", 4, 3, 3, 4);
	}

	@Test
	public void sourceWkbPreservesNativePrecisionAcrossGeometryTypes() {
		for (String lexical : List.of(
				"POINT(-0.0 0.12345678901234567)",
				"LINESTRING(0.12345678901234567 1.2345678901234567,2.345678901234567 3.456789012345678)",
				"LINESTRING Z(0.12345678901234567 1.2345678901234567 4.123456789012345,2 3 5)",
				"POLYGON((0 0,4.123456789012345 0,4.123456789012345 4,0 4,0 0),"
						+ "(1 1,1 2,2 2,2 1,1 1))",
				"MULTIPOINT((0.12345678901234567 1),(2 3))",
				"MULTILINESTRING((0 0,1.1234567890123457 1),(2 2,3 3))",
				"MULTIPOLYGON(((0 0,1 0,1 1,0 0)),((2 2,3 2,3 3,2 2)))",
				"GEOMETRYCOLLECTION(POINT(0.12345678901234567 1),"
						+ "POLYGON((0 0,4 0,4 4,0 4,0 0),(1 1,1 2,2 2,2 1,1 1)))")) {
			assertStableSourceWkb(lexical);
		}
	}

	@Test
	public void sourceWkbPreservesMeasuredComponentsAndEmptyGeometries() {
		for (String lexical : List.of(
				"LINESTRING M(0 0 4.123456789012345,1 1 5)",
				"LINESTRING ZM(0 0 3.123456789012345 4.123456789012345,1 1 5 6)",
				"MULTIPOINT ZM((1 2 3 4),(5 6 7 8))",
				"MULTILINESTRING M((0 0 4,1 1 5),(2 2 6,3 3 7))",
				"GEOMETRYCOLLECTION ZM(POINT ZM(1 2 3 4),LINESTRING ZM(0 0 1 2,1 1 3 4))",
				"POINT EMPTY", "POINT M EMPTY", "LINESTRING EMPTY", "POLYGON EMPTY",
				"MULTIPOINT EMPTY", "MULTIPOLYGON EMPTY", "GEOMETRYCOLLECTION EMPTY")) {
			assertStableSourceWkb(lexical);
		}
	}

	@Test
	public void sourceWkbPreservesCompleteCollectionTopologyAndEmptiness() {
		for (String lexical : List.of(
				"GEOMETRYCOLLECTION(POINT(1 2),LINESTRING(0 0,2 2))",
				"GEOMETRYCOLLECTION EMPTY")) {
			SourceGeometryLiteral original = SourceGeometryLiteral.fromWkt(lexical);
			SourceGeometryLiteral restored = roundTrip(original);
			Geometry expected = original.asGeometryWrapper().getXYGeometry();
			Geometry actual = restored.asGeometryWrapper().getXYGeometry();

			assertEquals(expected.getGeometryType(), actual.getGeometryType());
			assertEquals(expected.getNumGeometries(), actual.getNumGeometries());
			assertEquals(expected.isEmpty(), actual.isEmpty());
			assertTrue(expected.equalsExact(actual));
			assertEquals(original.asGeometryWrapper().getDimensionInfo(),
					restored.asGeometryWrapper().getDimensionInfo());
		}
	}

	@Test
	public void sourceWkbCacheRetainsAuthoritativeGmlIdentity() {
		String lexical = "<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\" "
				+ "srsName=\"http://www.opengis.net/def/crs/OGC/1.3/CRS84\">"
				+ "<gml:pos>1 2</gml:pos></gml:Point>";
		SourceGeometryLiteral original = SourceGeometryLiteral.fromLiteral(
				SimpleValueFactory.getInstance().createLiteral(lexical, GeoConstants.GEO_GML_LITERAL));
		SourceGeometryLiteral restored = roundTrip(original);

		assertEquals(lexical, restored.lexicalForm());
		assertEquals(GeoConstants.GEO_GML_LITERAL, restored.datatype());
		assertEquals(original.effectiveCrsUri(), restored.effectiveCrsUri());
		assertTrue(original.asGeometryWrapper().getXYGeometry()
				.equalsExact(restored.asGeometryWrapper().getXYGeometry()));
	}

	@Test
	public void sourceWkbReconstructsAxisOrderSensitiveParsingAndXyGeometry() {
		SourceGeometryLiteral original = SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/7843> POINT Z(-27.47 153.03 55)");
		SourceGeometryLiteral restored = roundTrip(original);

		assertTrue(original.asGeometryWrapper().getXYGeometry()
				.equalsExact(restored.asGeometryWrapper().getXYGeometry()));
		assertTrue(original.asGeometryWrapper().getParsingGeometry()
				.equalsExact(restored.asGeometryWrapper().getParsingGeometry()));
		assertEquals(55,
				restored.asGeometryWrapper().getXYGeometry().getCoordinate().getZ(), 0);
		assertEquals(55,
				restored.asGeometryWrapper().getParsingGeometry().getCoordinate().getZ(), 0);
	}

	@Test
	public void reconstructedSourceRemainsEquivalentForExactEvaluation() throws Exception {
		String lexical = "POLYGON((0 0,0 2,2 2,2 0,0 0))";
		SourceGeometryLiteral original = SourceGeometryLiteral.fromWkt(lexical);
		SourceGeometryLiteral restored = roundTrip(original);

		assertTrue(JenaFunctionEvaluator.evaluateTopological(
				GeoConstants.GEOF_SF_CONTAINS.stringValue(), restored, original));
	}

	private void assertPointRoundTrip(String lexical, int coordinateDimension, int spatialDimension,
			double z, double m) {
		SourceGeometryLiteral original = SourceGeometryLiteral.fromWkt(lexical);
		Point originalPoint = (Point) original.asGeometryWrapper().getXYGeometry();
		assertEquals("source Z", z, originalPoint.getCoordinateSequence().getZ(0), 0);
		assertEquals("source M", m, originalPoint.getCoordinateSequence().getM(0), 0);
		byte[] encoded = SourceGeometryWkbCodec.encode(original);
		if (!Double.isNaN(m) && Double.isNaN(z)) {
			assertEquals(0x40000001, ByteBuffer.wrap(encoded, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
			assertEquals(m, ByteBuffer.wrap(encoded, 21, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble(), 0);
		}
		Geometry decoded = SourceGeometryWkbCodec.decode(encoded);
		Point decodedPoint = (Point) decoded;
		assertEquals("decoded Z", z, decodedPoint.getCoordinateSequence().getZ(0), 0);
		assertEquals("decoded M", m, decodedPoint.getCoordinateSequence().getM(0), 0);
		SourceGeometryLiteral restored = SourceGeometryLiteral.fromStoredGeometry(original.lexicalForm(),
				original.datatype(), original.effectiveCrsUri(), decoded,
				original.asGeometryWrapper().getDimensionInfo());
		DimensionInfo dimensions = restored.asGeometryWrapper().getDimensionInfo();
		Point point = (Point) restored.asGeometryWrapper().getXYGeometry();

		assertEquals(coordinateDimension, dimensions.getCoordinate());
		assertEquals(spatialDimension, dimensions.getSpatial());
		assertEquals(z, point.getCoordinateSequence().getZ(0), 0);
		assertEquals(m, point.getCoordinateSequence().getM(0), 0);
	}

	private SourceGeometryLiteral roundTrip(SourceGeometryLiteral original) {
		return SourceGeometryLiteral.fromStoredGeometry(original.lexicalForm(), original.datatype(),
				original.effectiveCrsUri(), SourceGeometryWkbCodec.decode(SourceGeometryWkbCodec.encode(original)),
				original.asGeometryWrapper().getDimensionInfo());
	}

	private void assertStableSourceWkb(String lexical) {
		SourceGeometryLiteral original = SourceGeometryLiteral.fromWkt(lexical);
		SourceGeometryLiteral restored = roundTrip(original);
		assertEquals(lexical, restored.lexicalForm());
		assertEquals(original.datatype(), restored.datatype());
		assertEquals(original.effectiveCrsUri(), restored.effectiveCrsUri());
		assertEquals(original.asGeometryWrapper().getDimensionInfo(),
				restored.asGeometryWrapper().getDimensionInfo());
		assertEquals(original.asGeometryWrapper().getXYGeometry().getGeometryType(),
				restored.asGeometryWrapper().getXYGeometry().getGeometryType());
		assertArrayEquals(lexical, SourceGeometryWkbCodec.encode(original), SourceGeometryWkbCodec.encode(restored));
	}

	private void assertForceReindexMessage(PluginException exception) {
		assertTrue(exception.getMessage().contains("required schema v2 layout"));
		assertTrue(exception.getMessage().contains("force-reindex"));
	}
}
