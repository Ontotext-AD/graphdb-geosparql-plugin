package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.sis.geometry.DirectPosition2D;
import org.apache.sis.referencing.CRS;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Falsifies under-covering SIS candidate envelopes by transforming densified source geometries independently
 * of {@code Envelopes.transform}, then checking that every CRS84 image point lies in the candidate envelope.
 *
 * <p>Ordinary GDA2020 and MGA2020 geometries must remain {@link CandidateBoundsKind#TRANSFORMED}. The suite does not
 * treat world fallback as the policy for projected sources.
 */
public class ConservativeCrs84EnvelopeCoverageTest {
	private static final String GDA2020 = "http://www.opengis.net/def/crs/EPSG/0/7844";
	private static final String MGA55 = "http://www.opengis.net/def/crs/EPSG/0/7855";
	private static final String MGA56 = "http://www.opengis.net/def/crs/EPSG/0/7856";
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String UTM_32N = "http://www.opengis.net/def/crs/EPSG/0/32632";
	private static final String UTM_56S = "http://www.opengis.net/def/crs/EPSG/0/32756";
	private static final String UTM_1N = "http://www.opengis.net/def/crs/EPSG/0/32601";
	private static final String WEB_MERCATOR = "http://www.opengis.net/def/crs/EPSG/0/3857";
	private static final String LAMBERT_93 = "http://www.opengis.net/def/crs/EPSG/0/2154";
	private static final String POLAR_SOUTH = "http://www.opengis.net/def/crs/EPSG/0/3031";
	private static final String POLAR_NORTH = "http://www.opengis.net/def/crs/EPSG/0/3995";
	private static final String WGS84_3D = "http://www.opengis.net/def/crs/EPSG/0/4979";
	private static final String GDA94 = "http://www.opengis.net/def/crs/EPSG/0/4283";

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void transformedImageOfAdversarialGeometriesStaysInsideCandidateEnvelope() throws Exception {
		List<String> wkts = adversarialGeometries();
		assertTrue("coverage fixture must include GDA2020 and MGA2020 cases",
				wkts.stream().anyMatch(wkt -> wkt.contains("7844"))
						&& wkts.stream().anyMatch(wkt -> wkt.contains("7855"))
						&& wkts.stream().anyMatch(wkt -> wkt.contains("7856")));
		for (String wkt : wkts) {
			assertCoverage(wkt);
		}
	}

	@Test
	public void randomizedGeometriesInCrsValidityAreasDoNotEscapeCandidateEnvelope() throws Exception {
		Random random = new Random(0xC84L);
		int trials = 0;
		for (CrsArea area : crsAreas()) {
			for (int i = 0; i < 16; i++) {
				assertCoverage(area.randomGeometry(random));
				trials++;
			}
		}
		assertTrue(trials >= 100);
	}

	@Test
	public void ordinaryGda2020AndMga2020GeometriesStaySelective() {
		for (String wkt : List.of(
				crsWkt(GDA2020, "POLYGON((-27.6 152.9,-27.3 152.9,-27.3 153.2,-27.6 153.2,-27.6 152.9))"),
				crsWkt(MGA56, "POINT(502890 6959800)"),
				crsWkt(MGA55, "POLYGON((500000 5800000,500000 5810000,510000 5810000,510000 5800000,500000 5800000))"))) {
			IndexGeometry index = IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt));
			assertEquals(wkt, CandidateBoundsKind.TRANSFORMED, index.candidateBoundsKind());
			assertFalse(wkt, worldEnvelope().equals(index.indexEnvelope()));
		}
	}

	private static void assertCoverage(String wkt) throws Exception {
		Overflow overflow = coverageOverflow(wkt);
		if (overflow.degrees() > 0) {
			fail(overflow.detail());
		}
	}

	private static Overflow coverageOverflow(String wkt) throws Exception {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(wkt);
		IndexGeometry index = IndexGeometry.fromSourceGeometryLiteral(source);
		if (!index.isSpatialCandidate()) {
			assertEquals(wkt, CandidateBoundsKind.EMPTY, index.candidateBoundsKind());
			return Overflow.NONE;
		}
		Envelope candidate = index.indexEnvelope();
		if (index.candidateBoundsKind() == CandidateBoundsKind.WORLD_FALLBACK) {
			assertEquals(wkt, worldEnvelope(), candidate);
			return Overflow.NONE;
		}
		GeometryWrapper wrapper = source.asGeometryWrapper();
		CoordinateReferenceSystem sourceCrs = CRS.getHorizontalComponent(wrapper.getCRS());
		CoordinateOperation operation = CRS.findOperation(
				sourceCrs, SRSRegistry.getCRS(IndexGeometry.INDEX_CRS), null);
		MathTransform transform = operation.getMathTransform();
		double maxOverflow = 0;
		String detail = null;
		for (Coordinate coordinate : sampleCoordinates(wrapper)) {
			try {
				DirectPosition transformed = transform.transform(
						new DirectPosition2D(sourceCrs, coordinate.x, coordinate.y), null);
				double x = transformed.getOrdinate(0);
				double y = transformed.getOrdinate(1);
				double overflow = overflowDegrees(candidate, x, y);
				if (overflow > maxOverflow) {
					maxOverflow = overflow;
					detail = "Under-covering candidate envelope for " + wkt
							+ "\n  source coordinate: " + coordinate.x + ", " + coordinate.y
							+ "\n  transformed CRS84 image: (" + x + ", " + y + ")"
							+ "\n  SIS candidate: " + candidate
							+ "\n  overflow degrees: " + overflow
							+ "\n  kind: " + index.candidateBoundsKind();
				}
			} catch (TransformException ignored) {
				// A sample outside the operation domain is not an under-cover of the source geometry image.
			}
		}
		return new Overflow(maxOverflow, detail);
	}

	private static double overflowDegrees(Envelope candidate, double x, double y) {
		double overflow = 0;
		if (x < candidate.getMinX()) {
			overflow = Math.max(overflow, candidate.getMinX() - x);
		}
		if (x > candidate.getMaxX()) {
			overflow = Math.max(overflow, x - candidate.getMaxX());
		}
		if (y < candidate.getMinY()) {
			overflow = Math.max(overflow, candidate.getMinY() - y);
		}
		if (y > candidate.getMaxY()) {
			overflow = Math.max(overflow, y - candidate.getMaxY());
		}
		return overflow;
	}

	private record Overflow(double degrees, String detail) {
		private static final Overflow NONE = new Overflow(0, null);
	}

	private static List<Coordinate> sampleCoordinates(GeometryWrapper wrapper) {
		Geometry parsing = wrapper.getParsingGeometry();
		List<Coordinate> samples = new ArrayList<>();
		Envelope envelope = parsing.getEnvelopeInternal();
		addEnvelopeGrid(samples, envelope);
		addEnvelopeEdges(samples, envelope, 16);
		double width = Math.max(envelope.getWidth(), envelope.getHeight());
		double tolerance = width == 0 ? 1.0 : width / 8.0;
		Geometry densified = Densifier.densify(parsing, tolerance);
		for (Coordinate coordinate : densified.getCoordinates()) {
			samples.add(clampToEnvelope(envelope, coordinate));
		}
		return samples;
	}

	private static void addEnvelopeEdges(List<Coordinate> samples, Envelope envelope, int steps) {
		for (int i = 0; i <= steps; i++) {
			double t = i / (double) steps;
			double x = envelope.getMinX() + t * envelope.getWidth();
			double y = envelope.getMinY() + t * envelope.getHeight();
			samples.add(new Coordinate(envelope.getMinX(), y));
			samples.add(new Coordinate(envelope.getMaxX(), y));
			samples.add(new Coordinate(x, envelope.getMinY()));
			samples.add(new Coordinate(x, envelope.getMaxY()));
		}
	}

	private static Coordinate clampToEnvelope(Envelope envelope, Coordinate coordinate) {
		double x = Math.min(envelope.getMaxX(), Math.max(envelope.getMinX(), coordinate.x));
		double y = Math.min(envelope.getMaxY(), Math.max(envelope.getMinY(), coordinate.y));
		return new Coordinate(x, y);
	}

	private static void addEnvelopeGrid(List<Coordinate> samples, Envelope envelope) {
		double[] xs = {envelope.getMinX(), envelope.getMinX() + envelope.getWidth() / 2.0, envelope.getMaxX()};
		double[] ys = {envelope.getMinY(), envelope.getMinY() + envelope.getHeight() / 2.0, envelope.getMaxY()};
		for (double x : xs) {
			for (double y : ys) {
				samples.add(new Coordinate(x, y));
			}
		}
	}

	private static List<String> adversarialGeometries() {
		List<String> wkts = new ArrayList<>();
		wkts.add("LINESTRING EMPTY");
		wkts.add("LINESTRING(1 2,5 6)");
		wkts.add("POLYGON((0 0,0 10,10 10,10 0,0 0))");
		wkts.add("GEOMETRYCOLLECTION(POINT(153.03 -27.47),LINESTRING(153.0 -27.5,153.1 -27.4))");
		wkts.add(crsWkt(GDA2020, "POINT(-27.47 153.03)"));
		wkts.add(crsWkt(GDA2020, "POLYGON((-33.58028369 150.20055698,-33.58028369 150.84074934,-32.88150209 150.84074934,-32.88150209 150.20055698,-33.58028369 150.20055698))"));
		wkts.add(crsWkt(GDA2020, "LINESTRING(-37.8 144.9,-37.8 145.1)"));
		wkts.add(crsWkt(GDA2020, "GEOMETRYCOLLECTION(POINT(-27.47 153.03),POINT(-33.87 151.21))"));
		wkts.add(crsWkt(MGA56, "POINT(502890 6959800)"));
		wkts.add(crsWkt(MGA56, "LINESTRING(400000 6900000,700000 6900000)"));
		wkts.add(crsWkt(MGA56, "POINT Z(502890 6959800 80)"));
		wkts.add(crsWkt(MGA56, "POINT M(502890 6959800 4)"));
		wkts.add(crsWkt(MGA56, "POINT ZM(502890 6959800 80 4)"));
		wkts.add(crsWkt(MGA55, "POLYGON((500000 5800000,500000 5810000,510000 5810000,510000 5800000,500000 5800000))"));
		wkts.add(crsWkt(MGA55, "LINESTRING(166021 5800000,833978 5800000)"));
		wkts.add(crsWkt(EPSG_4326, "POINT(-27.47 153.03)"));
		wkts.add(crsWkt(EPSG_4326, "POLYGON((-28 152,-27 152,-27 154,-28 154,-28 152))"));
		wkts.add(crsWkt(EPSG_4326, "POINT(89.9 10)"));
		wkts.add(crsWkt(EPSG_4326, "POINT(-89.9 10)"));
		wkts.add(crsWkt(EPSG_4326, "POINT(0 179.9)"));
		wkts.add(crsWkt(GDA94, "POINT(-27.47 153.03)"));
		wkts.add(crsWkt(UTM_32N, "LINESTRING(200000 5000000,800000 5000000)"));
		wkts.add(crsWkt(UTM_32N, "POINT(500000 5000000)"));
		wkts.add(crsWkt(UTM_56S, "POINT(500000 7000000)"));
		wkts.add(crsWkt(UTM_1N, "POINT(166021.44 0)"));
		wkts.add(crsWkt(UTM_1N, "LINESTRING(166021 100000,200000 100000)"));
		wkts.add(crsWkt(WEB_MERCATOR, "POLYGON((17000000 -3200000,17000000 -3100000,17100000 -3100000,17100000 -3200000,17000000 -3200000))"));
		wkts.add(crsWkt(LAMBERT_93, "LINESTRING(200000 6000000,800000 6000000)"));
		wkts.add(crsWkt(POLAR_SOUTH, "POINT(0 0)"));
		wkts.add(crsWkt(POLAR_SOUTH, "LINESTRING(-500000 -500000,500000 500000)"));
		wkts.add(crsWkt(POLAR_NORTH, "POINT(100000 100000)"));
		wkts.add(crsWkt(WGS84_3D, "POINT Z(-27.47 153.03 55)"));
		wkts.add("<" + UTM_32N + "> GEOMETRYCOLLECTION(POINT(500000 5000000),LINESTRING(200000 5100000,800000 5100000))");
		return wkts;
	}

	private static List<CrsArea> crsAreas() {
		return List.of(
				new CrsArea(GDA2020, -38.0, -27.0, 144.0, 154.0),
				new CrsArea(MGA55, 300_000, 700_000, 5_700_000, 6_200_000),
				new CrsArea(MGA56, 300_000, 700_000, 6_900_000, 7_100_000),
				new CrsArea(EPSG_4326, -40.0, -10.0, 110.0, 155.0),
				new CrsArea(null, 110.0, 155.0, -40.0, -10.0),
				new CrsArea(UTM_32N, 300_000, 700_000, 5_000_000, 5_400_000),
				new CrsArea(UTM_56S, 300_000, 700_000, 6_900_000, 7_100_000),
				new CrsArea(UTM_1N, 200_000, 700_000, 0, 200_000),
				new CrsArea(WEB_MERCATOR, 15_500_000, 17_000_000, -4_400_000, -2_800_000),
				new CrsArea(LAMBERT_93, 200_000, 800_000, 6_000_000, 7_000_000),
				new CrsArea(POLAR_SOUTH, -400_000, 400_000, -400_000, 400_000),
				new CrsArea(GDA94, -38.0, -27.0, 144.0, 154.0));
	}

	private static String crsWkt(String crsUri, String body) {
		return "<" + crsUri + "> " + body;
	}

	private static Envelope worldEnvelope() {
		return ConservativeCrs84EnvelopeProjector.worldCrs84Envelope();
	}

	private record CrsArea(String crsUri, double min0, double max0, double min1, double max1) {
		String randomGeometry(Random random) {
			int kind = random.nextInt(5);
			double a0 = value(random, min0, max0);
			double a1 = value(random, min1, max1);
			return switch (kind) {
				case 0 -> geometry("POINT(" + ord(a0) + " " + ord(a1) + ")");
				case 1 -> {
					double b0 = value(random, min0, max0);
					double b1 = value(random, min1, max1);
					yield geometry("LINESTRING(" + ord(a0) + " " + ord(a1) + "," + ord(b0) + " " + ord(b1) + ")");
				}
				case 2 -> rectangle(random);
				case 3 -> geometry("POINT Z(" + ord(a0) + " " + ord(a1) + " 25)");
				default -> {
					double b0 = value(random, min0, max0);
					double b1 = value(random, min1, max1);
					yield geometry("GEOMETRYCOLLECTION(POINT(" + ord(a0) + " " + ord(a1) + "),POINT("
							+ ord(b0) + " " + ord(b1) + "))");
				}
			};
		}

		private String rectangle(Random random) {
			double width = (max0 - min0) * (0.01 + random.nextDouble() * 0.1);
			double height = (max1 - min1) * (0.01 + random.nextDouble() * 0.1);
			double x = value(random, min0, max0 - width);
			double y = value(random, min1, max1 - height);
			return geometry("POLYGON(("
					+ ord(x) + " " + ord(y) + ","
					+ ord(x) + " " + ord(y + height) + ","
					+ ord(x + width) + " " + ord(y + height) + ","
					+ ord(x + width) + " " + ord(y) + ","
					+ ord(x) + " " + ord(y) + "))");
		}

		private String geometry(String body) {
			return crsUri == null ? body : crsWkt(crsUri, body);
		}

		private static double value(Random random, double min, double max) {
			return min + random.nextDouble() * (max - min);
		}

		private static String ord(double value) {
			return String.format(Locale.ROOT, "%.8f", value);
		}
	}
}
