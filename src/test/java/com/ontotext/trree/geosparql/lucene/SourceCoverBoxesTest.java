package com.ontotext.trree.geosparql.lucene;

import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceCoverBoxesTest {
	private static final GeometryFactory FACTORY = new GeometryFactory();

	/** A ring around 8 8 with outer radius 4 and a hole of radius 2, of 1,000 points. */
	private static final String RING = ring(8, 8, 4, 2, 500);
	/** A line of 400 points that climbs from 0 0 to 40 40 in steps. */
	private static final String STAIRS = stairs(400);

	@Test
	public void everyPointOfAGeometryLiesInOneOfItsBoxes() throws Exception {
		for (String wkt : List.of(RING, STAIRS,
				"MULTIPOLYGON(" + RING.substring("POLYGON".length()) + ","
						+ ring(30, 30, 3, 1, 200).substring("POLYGON".length()) + ")",
				"GEOMETRYCOLLECTION(" + RING + "," + STAIRS + ",POINT(50 3))")) {
			Geometry geometry = read(wkt);
			List<Envelope> boxes = SourceCoverBoxes.of(geometry);

			assertFalse(wkt.substring(0, 20), boxes.isEmpty());
			for (Coordinate vertex : geometry.getCoordinates()) {
				assertTrue("vertex outside every box: " + vertex, inSomeBox(boxes, vertex));
			}
			Random random = new Random(40);
			Envelope envelope = geometry.getEnvelopeInternal();
			for (int i = 0; i < 20_000; i++) {
				Coordinate point = new Coordinate(
						envelope.getMinX() + random.nextDouble() * envelope.getWidth(),
						envelope.getMinY() + random.nextDouble() * envelope.getHeight());
				if (geometry.intersects(FACTORY.createPoint(point))) {
					assertTrue("point of the geometry outside every box: " + point, inSomeBox(boxes, point));
				}
			}
			for (Envelope box : boxes) {
				assertTrue("box without a point of the geometry: " + box, geometry.intersects(FACTORY.toGeometry(box)));
			}
		}
	}

	@Test
	public void boxesLeaveAHoleAndTheEmptyCornersOfTheEnvelopeUncovered() throws Exception {
		List<Envelope> boxes = SourceCoverBoxes.of(read(RING));

		assertFalse("middle of the hole", inSomeBox(boxes, new Coordinate(7.8, 7.8)));
		for (Coordinate corner : List.of(new Coordinate(4.2, 4.2), new Coordinate(11.8, 4.2),
				new Coordinate(4.2, 11.8), new Coordinate(11.8, 11.8))) {
			assertFalse("corner " + corner, inSomeBox(boxes, corner));
		}
		assertTrue("on the ring", inSomeBox(boxes, new Coordinate(11, 8)));
	}

	@Test
	public void boxesFollowALineThroughItsEnvelope() throws Exception {
		List<Envelope> boxes = SourceCoverBoxes.of(read(STAIRS));

		assertFalse(inSomeBox(boxes, new Coordinate(35, 5)));
		assertFalse(inSomeBox(boxes, new Coordinate(5, 35)));
		assertTrue(inSomeBox(boxes, new Coordinate(20, 20)));
	}

	@Test
	public void numberOfBoxesFollowsTheSizeOfTheGeometry() throws Exception {
		assertEquals(List.of(), SourceCoverBoxes.of(read("POLYGON((0 0,8 0,0 8,0 0))")));
		assertEquals(List.of(), SourceCoverBoxes.of(read(stairs(100))));
		int medium = SourceCoverBoxes.of(read(stairs(400))).size();
		int large = SourceCoverBoxes.of(read(stairs(4000))).size();

		assertTrue(String.valueOf(medium), medium >= 2 && medium <= 100);
		assertTrue(String.valueOf(large), large > medium && large <= 256);
	}

	@Test
	public void geometryThatItsEnvelopeDescribesWellHasNoBoxes() throws Exception {
		// A square with many points on its sides fills its envelope.
		StringBuilder square = new StringBuilder("POLYGON((");
		for (int i = 0; i < 200; i++) {
			square.append(i * 0.05).append(" 0,");
		}
		square.append("10 0,10 10,0 10,0 0))");
		// A line along one axis has an envelope without area.
		StringBuilder flat = new StringBuilder("LINESTRING(0 5");
		for (int i = 1; i < 200; i++) {
			flat.append(',').append(i).append(" 5");
		}
		flat.append(')');

		assertEquals(List.of(), SourceCoverBoxes.of(read(square.toString())));
		assertEquals(List.of(), SourceCoverBoxes.of(read(flat.toString())));
	}

	/** The checks Jena uses to tell inside from outside can disagree on an invalid geometry. */
	@Test
	public void invalidGeometryHasNoBoxes() throws Exception {
		// The hole of this ring lies outside its shell.
		String invalid = "POLYGON(" + ring(8, 8, 4, 2, 500).substring("POLYGON(".length(),
				ring(8, 8, 4, 2, 500).indexOf("),(") + 1) + ",(30 30,31 30,31 31,30 31,30 30))";

		assertFalse(read(invalid).isValid());
		assertEquals(List.of(), SourceCoverBoxes.of(read(invalid)));
	}

	private static boolean inSomeBox(List<Envelope> boxes, Coordinate point) {
		return boxes.stream().anyMatch(box -> box.contains(point));
	}

	private static Geometry read(String wkt) throws ParseException {
		return new WKTReader(FACTORY).read(wkt);
	}

	static String ring(double x, double y, double outer, double inner, int pointsPerCircle) {
		return "POLYGON((" + circle(x, y, outer, pointsPerCircle) + "),(" + circle(x, y, inner, pointsPerCircle) + "))";
	}

	private static String circle(double x, double y, double radius, int points) {
		StringBuilder circle = new StringBuilder();
		String first = null;
		for (int i = 0; i < points; i++) {
			double angle = 2 * Math.PI * i / points;
			String vertex = String.format(Locale.ROOT, "%.9f %.9f", x + radius * Math.cos(angle),
					y + radius * Math.sin(angle));
			first = first == null ? vertex : first;
			circle.append(vertex).append(',');
		}
		return circle.append(first).toString();
	}

	private static String stairs(int points) {
		StringBuilder line = new StringBuilder("LINESTRING(0 0");
		for (int i = 1; i < points; i++) {
			line.append(',').append((i + 1) / 2 * 0.2).append(' ').append(i / 2 * 0.2);
		}
		return line.append(')').toString();
	}
}
