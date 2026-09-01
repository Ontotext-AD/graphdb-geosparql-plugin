package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.sis.geometry.DirectPosition2D;
import org.apache.sis.geometry.Envelopes;
import org.apache.sis.geometry.GeneralEnvelope;
import org.apache.sis.referencing.CRS;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies CRS84, empty, SIS, and world-fallback index-envelope behaviour, including that a representable SIS
 * rectangle is used without substituting the world CRS84 envelope.
 */
public class ConservativeCrs84EnvelopeProjectorTest {
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";
	private static final String EPSG_32601 = "http://www.opengis.net/def/crs/EPSG/0/32601";
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String WGS84_3D = "http://www.opengis.net/def/crs/EPSG/0/4979";
	private static final String GDA2020_3D = "http://www.opengis.net/def/crs/EPSG/0/7843";
	private static final String GDA94_3D = "http://www.opengis.net/def/crs/EPSG/0/4939";
	private static final String NAD83_CSRS_3D = "http://www.opengis.net/def/crs/EPSG/0/4955";
	private static final String PROJECTED_LINE_WKT =
			"<" + EPSG_32634 + "> LINESTRING(200000 7000000, 800000 7000000)";
	private static final String PROJECTED_POINT_WKT =
			"<" + EPSG_32634 + "> POINT(500000 7000000)";
	private static final ConservativeCrs84EnvelopeProjector PROJECTOR =
			new ConservativeCrs84EnvelopeProjector();

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void emptyGeometryProducesNullEnvelopeAndRemainsNonSpatial() {
		IndexGeometry sentinel = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("LINESTRING EMPTY"));

		assertTrue(sentinel.indexEnvelope().isNull());
		assertFalse(sentinel.isSpatialCandidate());
		assertEquals(CandidateBoundsKind.EMPTY, sentinel.candidateBoundsKind());
		assertTrue(sentinel.candidateBoundsFallbackReason().isEmpty());
		assertTrue(PROJECTOR.project(sentinel.sourceGeometryLiteral()).isNull());
	}

	@Test
	public void crs84GeometryUsesItsSourceEnvelopeRatherThanTheWorldFallback() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(
				"LINESTRING(1 2,5 6)");
		IndexGeometry index = IndexGeometry.fromSourceGeometryLiteral(source);

		assertEquals(new Envelope(1.0, 5.0, 2.0, 6.0), PROJECTOR.project(source));
		assertEquals(new Envelope(1.0, 5.0, 2.0, 6.0), index.indexEnvelope());
		assertEquals(CandidateBoundsKind.NATIVE_CRS84, index.candidateBoundsKind());
		assertTrue(index.candidateBoundsFallbackReason().isEmpty());
		assertTrue(index.isSpatialCandidate());
		assertFalse(worldEnvelope().equals(index.indexEnvelope()));
	}

	@Test
	public void gda2020PolygonUsesTransformedSelectiveBoundsRatherThanWorldFallback() {
		IndexGeometry brisbane = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(
						"<http://www.opengis.net/def/crs/EPSG/0/7844> "
								+ "POLYGON((-27.6 152.9,-27.3 152.9,-27.3 153.2,-27.6 153.2,-27.6 152.9))"));

		assertEquals(CandidateBoundsKind.TRANSFORMED, brisbane.candidateBoundsKind());
		assertTrue(brisbane.candidateBoundsFallbackReason().isEmpty());
		assertTrue(brisbane.isSpatialCandidate());
		assertFalse(worldEnvelope().equals(brisbane.indexEnvelope()));
		assertTrue(brisbane.indexEnvelope().getWidth() < 1.0);
		assertTrue(brisbane.indexEnvelope().getHeight() < 1.0);
		assertTrue(brisbane.indexEnvelope().contains(153.03, -27.47));
	}

	@Test
	public void projectedPointUsesASelectiveEnvelopeRatherThanTheWorldFallback() {
		IndexGeometry point = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(
						"<" + EPSG_32634 + "> POINT(799997.80 4589779.63)"));

		assertTrue(point.isSpatialCandidate());
		assertEquals(CandidateBoundsKind.TRANSFORMED, point.candidateBoundsKind());
		assertFalse(worldEnvelope().equals(point.indexEnvelope()));
		assertTrue(point.indexEnvelope().getWidth() < 1.0);
		assertTrue(point.indexEnvelope().getHeight() < 1.0);
	}

	@Test
	public void threeDimensionalSourcesCoverDirectAndHorizontalSourceToCrs84Operations() throws Exception {
		for (String wkt : List.of(
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 3000)",
				"<" + GDA2020_3D + "> POINT Z(-27.47 153.03 3000)",
				"<" + GDA94_3D + "> POINT Z(-27.47 153.03 3000)",
				"<" + NAD83_CSRS_3D + "> POINT Z(49.25 -123.1 300000)")) {
			SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(wkt);
			GeometryWrapper sourceWrapper = source.asGeometryWrapper();
			CoordinateReferenceSystem sourceCrs = sourceWrapper.getCRS();
			CoordinateReferenceSystem targetCrs = SRSRegistry.getCRS(IndexGeometry.INDEX_CRS);
			Coordinate coordinate = sourceWrapper.getParsingGeometry().getCoordinate();
			GeneralEnvelope sourceEnvelope = new GeneralEnvelope(sourceCrs);
			sourceEnvelope.setRange(0, coordinate.x, coordinate.x);
			sourceEnvelope.setRange(1, coordinate.y, coordinate.y);
			sourceEnvelope.setRange(2, coordinate.getZ(), coordinate.getZ());
			CoordinateOperation direct = CRS.findOperation(sourceCrs, targetCrs, null);
			Envelope directBounds = ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
					Envelopes.transform(direct, sourceEnvelope));

			CoordinateReferenceSystem horizontalCrs = CRS.getHorizontalComponent(sourceCrs);
			GeneralEnvelope horizontalEnvelope = new GeneralEnvelope(horizontalCrs);
			horizontalEnvelope.setRange(0, coordinate.x, coordinate.x);
			horizontalEnvelope.setRange(1, coordinate.y, coordinate.y);
			CoordinateOperation horizontal = CRS.findOperation(horizontalCrs, targetCrs, null);
			Envelope horizontalBounds = ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
					Envelopes.transform(horizontal, horizontalEnvelope));

			Envelope candidate = PROJECTOR.project(source);
			assertTrue(wkt + "\ndirect operation: " + direct.getName(), candidate.contains(directBounds));
			assertTrue(wkt + "\nhorizontal operation: " + horizontal.getName(),
					candidate.contains(horizontalBounds));
		}
	}

	@Test
	public void projectedLineEnvelopeContainsTheTransformedMidpointTheVertexEnvelopeOmits()
			throws Exception {
		IndexGeometry line = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(PROJECTED_LINE_WKT));
		Coordinate midpoint = sisTransformedParsingPoint(PROJECTED_POINT_WKT);
		Envelope indexEnvelope = line.indexEnvelope();
		Envelope vertexEnvelope = vertexTransformedEnvelope(line.sourceGeometryLiteral());

		assertTrue(line.isSpatialCandidate());
		assertFalse(worldEnvelope().equals(indexEnvelope));
		assertFalse(vertexEnvelope.contains(midpoint.x, midpoint.y));
		assertTrue(indexEnvelope.contains(midpoint.x, midpoint.y));
		assertTrue(indexEnvelope.getMaxY() > vertexEnvelope.getMaxY());
	}

	@Test
	public void projectedGeometriesCannotBeProvedDisjointFromTransformedVertexEnvelopes() {
		IndexGeometry line = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(PROJECTED_LINE_WKT));
		IndexGeometry point = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(PROJECTED_POINT_WKT));

		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				line.sourceGeometryLiteral(), point.sourceGeometryLiteral()));
		assertTrue(line.indexEnvelope().intersects(point.indexEnvelope()));
		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				line.sourceGeometryLiteral(), point.sourceGeometryLiteral()));
		assertFalse(GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
				line.sourceGeometryLiteral(), point.sourceGeometryLiteral()));
		assertFalse(line.isEnvelopeCoveringRectangle());
		assertFalse(point.isEnvelopeCoveringRectangle());
	}

	@Test
	public void nonCrs84GeographicCollectionUsesAxisOrderEnvelopeRatherThanWorld() {
		IndexGeometry envelope = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(
						"<" + EPSG_4326 + "> GEOMETRYCOLLECTION(POINT(50 10),POINT(51 11))"));

		assertTrue(envelope.isSpatialCandidate());
		assertFalse(worldEnvelope().equals(envelope.indexEnvelope()));
		assertTrue(envelope.indexEnvelope().getMinX() <= 10.0);
		assertTrue(envelope.indexEnvelope().getMaxX() >= 11.0);
		assertTrue(envelope.indexEnvelope().getMinY() <= 50.0);
		assertTrue(envelope.indexEnvelope().getMaxY() >= 51.0);
		assertTrue(envelope.indexEnvelope().getWidth() < 5.0);
	}

	@Test
	public void measuredAnd3dProjectedPointsUseOnlyHorizontalEnvelopeAxes() throws Exception {
		IndexGeometry xy = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32634 + "> POINT(500000 7000000)"));
		IndexGeometry xyz = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32634 + "> POINT Z(500000 7000000 120)"));
		IndexGeometry xym = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32634 + "> POINT M(500000 7000000 8)"));
		IndexGeometry xyzm = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32634 + "> POINT ZM(500000 7000000 120 8)"));
		Coordinate midpoint = sisTransformedParsingPoint(PROJECTED_POINT_WKT);

		for (IndexGeometry geometry : new IndexGeometry[]{xy, xyz, xym, xyzm}) {
			assertTrue(geometry.isSpatialCandidate());
			assertFalse(worldEnvelope().equals(geometry.indexEnvelope()));
			assertTrue(geometry.indexEnvelope().contains(midpoint.x, midpoint.y));
			assertTrue(geometry.indexEnvelope().getWidth() < 1.0);
			assertTrue(geometry.indexEnvelope().getHeight() < 1.0);
		}
	}

	@Test
	public void crs84RectangleContainmentAndSelectivityRemainIntact() {
		IndexGeometry rectangle = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POLYGON((0 0,0 10,10 10,10 0,0 0))"));
		IndexGeometry inside = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POINT(5 5)"));
		IndexGeometry outside = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POINT(20 20)"));

		assertEquals(new Envelope(0.0, 10.0, 0.0, 10.0), rectangle.indexEnvelope());
		assertTrue(rectangle.isEnvelopeCoveringRectangle());
		assertTrue(rectangle.indexEnvelope().intersects(inside.indexEnvelope()));
		assertFalse(rectangle.indexEnvelope().intersects(outside.indexEnvelope()));
	}

	@Test
	public void worldEnvelopeUsesTheConfiguredSpatial4jGeographicBounds() {
		assertEquals(worldEnvelope(), ConservativeCrs84EnvelopeProjector.worldCrs84Envelope());
		assertEquals(SpatialContext.GEO.getWorldBounds().getMinX(), worldEnvelope().getMinX(), 0.0);
		assertEquals(SpatialContext.GEO.getWorldBounds().getMaxX(), worldEnvelope().getMaxX(), 0.0);
		assertEquals(SpatialContext.GEO.getWorldBounds().getMinY(), worldEnvelope().getMinY(), 0.0);
		assertEquals(SpatialContext.GEO.getWorldBounds().getMaxY(), worldEnvelope().getMaxY(), 0.0);
	}

	@Test
	public void invertedOrOutOfRangeEnvelopesUseTheWorldFallback() {
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(range(170, -170, 10, 20)));
		assertEquals(CandidateBoundsKind.WORLD_FALLBACK,
				ConservativeCrs84EnvelopeProjector.toLuceneGeoBounds(range(170, -170, 10, 20)).kind());
		assertEquals(ConservativeCrs84EnvelopeProjector.FALLBACK_UNREPRESENTABLE_RECTANGLE,
				ConservativeCrs84EnvelopeProjector.toLuceneGeoBounds(range(170, -170, 10, 20)).fallbackReason());
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(range(170, 190, 10, 20)));
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(range(10, 20, -100, 80)));
	}

	@Test
	public void fullLongitudeWithLocalLatitudeKeepsLatitudeSelectivity() {
		Envelope envelope = ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
				range(-180, 180, 10, 20));
		Envelope world = worldEnvelope();

		assertEquals(world.getMinX(), envelope.getMinX(), 0.0);
		assertEquals(world.getMaxX(), envelope.getMaxX(), 0.0);
		assertTrue(envelope.getMinY() <= 10.0);
		assertTrue(envelope.getMaxY() >= 20.0);
		assertTrue(envelope.getMinY() > world.getMinY());
		assertTrue(envelope.getMaxY() < world.getMaxY());
		assertFalse(world.equals(envelope));
	}

	@Test
	public void nonFiniteTransformedOrdinatesUseTheWorldFallback() {
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
						range(Double.NaN, 10, 0, 1)));
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
						range(0, Double.POSITIVE_INFINITY, 0, 1)));
		assertEquals(worldEnvelope(),
				ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
						range(0, 1, Double.NEGATIVE_INFINITY, 10)));
	}

	@Test
	public void envelopeOnTheGeographicRimStaysInsideLuceneGeoBounds() {
		Envelope envelope = ConservativeCrs84EnvelopeProjector.toLuceneGeoEnvelope(
				range(180.0, 180.0, 90.0, 90.0));
		Envelope world = worldEnvelope();

		assertTrue(envelope.getMinX() >= world.getMinX());
		assertTrue(envelope.getMaxX() <= world.getMaxX());
		assertTrue(envelope.getMinY() >= world.getMinY());
		assertTrue(envelope.getMaxY() <= world.getMaxY());
		assertFalse(envelope.isNull());
	}

	@Test
	public void transformedEnvelopeNearTheAntimeridianDoesNotUnderCover() throws Exception {
		IndexGeometry geometry = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32601 + "> POINT(166021.44 0)"));
		Coordinate transformed = sisTransformedParsingPoint(
				"<" + EPSG_32601 + "> POINT(166021.44 0)");
		Envelope envelope = geometry.indexEnvelope();
		Envelope world = worldEnvelope();

		assertTrue(geometry.isSpatialCandidate());
		assertTrue(envelope.getMinX() >= world.getMinX());
		assertTrue(envelope.getMaxX() <= world.getMaxX());
		assertTrue(envelope.getMinY() >= world.getMinY());
		assertTrue(envelope.getMaxY() <= world.getMaxY());
		assertTrue(world.equals(envelope) || envelope.contains(transformed.x, transformed.y));
	}

	@Test
	public void geographicPointApproaching180UsesASafeLuceneEnvelope() throws Exception {
		IndexGeometry geometry = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("<" + EPSG_4326 + "> POINT(0 179.9)"));
		Coordinate transformed = sisTransformedParsingPoint(
				"<" + EPSG_4326 + "> POINT(0 179.9)");
		Envelope envelope = geometry.indexEnvelope();
		Envelope world = worldEnvelope();

		assertTrue(geometry.isSpatialCandidate());
		assertTrue(envelope.getMinX() >= world.getMinX());
		assertTrue(envelope.getMaxX() <= world.getMaxX());
		assertTrue(world.equals(envelope) || envelope.contains(transformed.x, transformed.y));
	}

	private static Coordinate sisTransformedParsingPoint(String wkt) throws Exception {
		GeometryWrapper wrapper = SourceGeometryLiteral.fromWkt(wkt).asGeometryWrapper();
		Coordinate parsing = wrapper.getParsingGeometry().getCoordinate();
		CoordinateReferenceSystem sourceCrs = CRS.getHorizontalComponent(wrapper.getCRS());
		CoordinateOperation operation = CRS.findOperation(
				sourceCrs, SRSRegistry.getCRS(IndexGeometry.INDEX_CRS), null);
		DirectPosition transformed = operation.getMathTransform().transform(
				new DirectPosition2D(sourceCrs, parsing.x, parsing.y), null);
		return new Coordinate(transformed.getOrdinate(0), transformed.getOrdinate(1));
	}

	private static GeneralEnvelope range(double minX, double maxX, double minY, double maxY) {
		GeneralEnvelope envelope = new GeneralEnvelope(2);
		envelope.setRange(0, minX, maxX);
		envelope.setRange(1, minY, maxY);
		return envelope;
	}

	private static Envelope vertexTransformedEnvelope(SourceGeometryLiteral source) throws Exception {
		GeometryWrapper transformed = source.asGeometryWrapper().transform(IndexGeometry.INDEX_CRS);
		return transformed.getXYGeometry().getEnvelopeInternal();
	}

	private static Envelope worldEnvelope() {
		return ConservativeCrs84EnvelopeProjector.worldCrs84Envelope();
	}
}
