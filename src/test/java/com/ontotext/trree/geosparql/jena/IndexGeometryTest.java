package com.ontotext.trree.geosparql.jena;

import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IndexGeometryTest {
	/** An envelope proves nothing once its area underflows or overflows, as products of coordinate differences do. */
	@Test
	public void onlyAnEnvelopeWithAFiniteAreaThatDoesNotUnderflowHasANormalArea() {
		assertTrue(IndexGeometry.hasNormalArea(new Envelope(0, 1, 0, 1)));
		assertTrue(IndexGeometry.hasNormalArea(new Envelope(-1e150, 1e150, -1e150, 1e150)));
		assertFalse(IndexGeometry.hasNormalArea(new Envelope(0, 1e-160, 0, 1e-160)));
		assertFalse(IndexGeometry.hasNormalArea(new Envelope(0, 1, 0, 0)));
		assertFalse(IndexGeometry.hasNormalArea(new Envelope(-1e200, 1e200, -1e200, 1e200)));
		assertFalse(IndexGeometry.hasNormalArea(IndexGeometry.unboundedEnvelope()));
	}

	/**
	 * Lookup relies on JTS proofs only while products of coordinate differences stay normal doubles and Jena's
	 * equality follows the point sets: every coordinate is zero or of moderate magnitude, and the geometry is valid.
	 */
	@Test
	public void onlyAValidGeometryWithZeroOrModerateCoordinatesIsProvable() throws ParseException {
		assertTrue(IndexGeometry.isProvable(read("POLYGON((0 0,1 0,1 1,0 1,0 0))")));
		assertTrue(IndexGeometry.isProvable(read("LINESTRING(-1e-100 0,1e100 -0.0)")));
		assertFalse(IndexGeometry.isProvable(read("LINESTRING(0 2e-163,3e-163 1e-163,0 1e-163)")));
		assertFalse(IndexGeometry.isProvable(read("POLYGON((0 0,1 0,1 1,0 1e-101,0 0))")));
		assertFalse(IndexGeometry.isProvable(read("POINT(1e101 5)")));
		assertFalse(IndexGeometry.isProvable(read("POLYGON((0 0,1 1,1 0,0 1,0 0))")));
	}

	private static org.locationtech.jts.geom.Geometry read(String wkt) throws ParseException {
		return new WKTReader().read(wkt);
	}
}
