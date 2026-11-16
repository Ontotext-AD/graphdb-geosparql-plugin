package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.relateng.RelateNG;
import org.locationtech.jts.operation.relateng.RelatePredicate;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;

/**
 * A bound source in the CRS where Jena evaluates it against the sources of one CRS.
 *
 * <p>Jena evaluates a pair in the CRS of its left operand. It uses the left operand's own coordinates and the
 * right operand transformed into that CRS and cleaned up. When the bound and a source share a CRS, or when the
 * source is the left operand, both geometries of the evaluation are known without transforming the source: the
 * source itself and this bound as Jena places it. Their envelopes are then exact for the pair, so envelopes that are
 * apart prove the pair disjoint and no other relation can hold.
 *
 * <p>{@link #UNRELATED} describes a CRS in which Jena cannot place the bound. Every exact evaluation against a
 * source of that CRS fails, so no relation, disjoint included, holds for such a pair.
 */
public final class BoundEvaluationEnvelope {
	public static final BoundEvaluationEnvelope UNRELATED = new BoundEvaluationEnvelope(null);

	private final GeometryWrapper bound;
	private final boolean fillsEnvelope;

	/** @param bound the bound as Jena evaluates it in its CRS, or null when Jena cannot place it */
	BoundEvaluationEnvelope(GeometryWrapper bound) {
		this.bound = bound;
		this.fillsEnvelope = bound != null && fillsEnvelope(bound.getXYGeometry());
	}

	/** Whether Jena can evaluate the bound against sources of the CRS. */
	public boolean isRelated() {
		return bound != null;
	}

	public Envelope envelope() {
		return new Envelope(bound.getXYGeometry().getEnvelopeInternal());
	}

	/**
	 * Whether the bound, as Jena evaluates it, is a polygon that covers every point of its envelope. A source whose
	 * envelope lies inside that envelope then meets the bound and cannot be disjoint from it.
	 */
	public boolean fillsEnvelope() {
		return fillsEnvelope;
	}

	/** Whether the bound, as Jena evaluates it, is a valid polygon of at most the given number of points. */
	public boolean isValidPolygonOfAtMost(int points) {
		return bound.getXYGeometry() instanceof Polygon polygon && !polygon.isEmpty()
				&& polygon.getNumPoints() <= points && polygon.isValid();
	}

	/**
	 * Whether Jena finds a rectangle of the evaluation CRS within the bound. A source whose envelope lies inside
	 * such a rectangle lies inside the bound and cannot be disjoint from it.
	 */
	public boolean covers(Envelope rectangle) throws FactoryException, TransformException {
		return rectangle.getWidth() > 0 && rectangle.getHeight() > 0 && rectangle(rectangle).within(bound);
	}

	/** Whether Jena finds a rectangle of the evaluation CRS disjoint from the bound. */
	public boolean isApartFrom(Envelope rectangle) throws FactoryException, TransformException {
		return rectangle(rectangle).disjoint(bound);
	}

	private GeometryWrapper rectangle(Envelope xy) {
		// Coordinates are written in the axis order of the CRS and read back unchanged.
		boolean axisXY = SRSRegistry.getAxisXY(bound.getSrsURI());
		String low = coordinate(xy.getMinX(), xy.getMinY(), axisXY);
		String lowHigh = coordinate(xy.getMinX(), xy.getMaxY(), axisXY);
		String high = coordinate(xy.getMaxX(), xy.getMaxY(), axisXY);
		String highLow = coordinate(xy.getMaxX(), xy.getMinY(), axisXY);
		return SourceGeometryLiteral.fromWkt("<" + bound.getSrsURI() + "> POLYGON((" + low + "," + highLow + ","
				+ high + "," + lowHigh + "," + low + "))").asGeometryWrapper();
	}

	private static String coordinate(double x, double y, boolean axisXY) {
		return axisXY ? x + " " + y : y + " " + x;
	}

	private static boolean fillsEnvelope(Geometry geometry) {
		if (geometry.isEmpty() || !(geometry instanceof Polygon)) {
			return false;
		}
		try {
			return RelateNG.relate(geometry, geometry.getEnvelope(), RelatePredicate.equalsTopo());
		} catch (RuntimeException e) {
			// Rectangle containment is an optional proof. Invalid topology must disable it, not fail the lookup.
			return false;
		}
	}
}
