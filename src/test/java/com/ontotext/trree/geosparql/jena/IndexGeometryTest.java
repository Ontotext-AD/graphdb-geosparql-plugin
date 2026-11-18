package com.ontotext.trree.geosparql.jena;

import org.junit.Test;
import org.locationtech.jts.geom.Envelope;

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
}
