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

import static org.junit.Assert.assertEquals;
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

	@Test
	public void sourceWkbPreservesCoordinateSequenceDimensionsAndOrdinates() {
		assertPointRoundTrip("POINT(1 2)", 2, 2, Double.NaN, Double.NaN);
		assertPointRoundTrip("POINT Z(1 2 3)", 3, 3, 3, Double.NaN);
		assertPointRoundTrip("POINT M(1 2 4)", 3, 2, Double.NaN, 4);
		assertPointRoundTrip("POINT ZM(1 2 3 4)", 4, 3, 3, 4);
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

	private void assertForceReindexMessage(PluginException exception) {
		assertTrue(exception.getMessage().contains("required schema v2 layout"));
		assertTrue(exception.getMessage().contains("force-reindex"));
	}
}
