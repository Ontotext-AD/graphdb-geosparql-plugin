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
 *
 * <p>This keeps what it derives from the placed bound, not the placed bound itself. The bound source keeps its
 * placement within a limit and otherwise places it again where the geometry is needed, so a lookup in many CRSs
 * holds no more transformed copies of a large bound than its source keeps.
 */
public final class BoundEvaluationEnvelope {
	public static final BoundEvaluationEnvelope UNRELATED = new BoundEvaluationEnvelope();

	private final SourceGeometryLiteral source;
	private final String crsUri;
	private final Envelope envelope;
	/** Points of the placed bound when it is a non-empty polygon, otherwise -1. */
	private final int polygonPoints;
	private Boolean fillsEnvelope;
	private Boolean valid;

	private BoundEvaluationEnvelope() {
		this.source = null;
		this.crsUri = null;
		this.envelope = null;
		this.polygonPoints = -1;
	}

	/**
	 * @param source the bound source
	 * @param crsUri the CRS of the sources the bound is evaluated against
	 * @param bound the bound as Jena evaluates it in that CRS
	 */
	BoundEvaluationEnvelope(SourceGeometryLiteral source, String crsUri, GeometryWrapper bound) {
		this.source = source;
		this.crsUri = crsUri;
		Geometry geometry = bound.getXYGeometry();
		this.envelope = new Envelope(geometry.getEnvelopeInternal());
		this.polygonPoints = geometry instanceof Polygon && !geometry.isEmpty() ? geometry.getNumPoints() : -1;
	}

	/** Whether Jena can evaluate the bound against sources of the CRS. */
	public boolean isRelated() {
		return source != null;
	}

	public Envelope envelope() {
		return new Envelope(envelope);
	}

	/**
	 * Whether the bound, as Jena evaluates it, is a polygon that covers every point of its envelope. A source whose
	 * envelope lies inside that envelope then meets the bound and cannot be disjoint from it.
	 *
	 * <p>The proof compares the whole polygon with its envelope, and only disjoint pruning uses it, so it is made on
	 * first use. A bound that cannot be placed again proves nothing.
	 */
	public boolean fillsEnvelope() {
		if (fillsEnvelope == null) {
			try {
				fillsEnvelope = polygonPoints >= 0 && fillsEnvelope(bound().getXYGeometry());
			} catch (FactoryException | TransformException e) {
				fillsEnvelope = false;
			}
		}
		return fillsEnvelope;
	}

	/** Whether the bound, as Jena evaluates it, is a valid polygon of at most the given number of points. */
	public boolean isValidPolygonOfAtMost(int points) {
		return polygonPoints >= 0 && polygonPoints <= points && isValid();
	}

	/**
	 * Whether the bound, as Jena evaluates it, is a valid geometry. Jena may fail to relate an invalid geometry to
	 * a source whose envelope meets it, and a pair that fails is no match.
	 */
	public boolean isValid() {
		if (valid == null) {
			try {
				valid = bound().getXYGeometry().isValid();
			} catch (FactoryException | TransformException | RuntimeException e) {
				valid = false;
			}
		}
		return valid;
	}

	/**
	 * Whether Jena finds a rectangle of the evaluation CRS within the bound. A source whose envelope lies inside
	 * such a rectangle lies inside the bound and cannot be disjoint from it.
	 */
	public boolean covers(Envelope rectangle) throws FactoryException, TransformException {
		return rectangle.getWidth() > 0 && rectangle.getHeight() > 0 && rectangle(rectangle).within(bound());
	}

	/** Whether Jena finds a rectangle of the evaluation CRS disjoint from the bound. */
	public boolean isApartFrom(Envelope rectangle) throws FactoryException, TransformException {
		return rectangle(rectangle).disjoint(bound());
	}

	/** The bound as Jena evaluates it in the CRS, which its source keeps or places again. */
	private GeometryWrapper bound() throws FactoryException, TransformException {
		return source.asRightOperandIn(crsUri);
	}

	private GeometryWrapper rectangle(Envelope xy) {
		// Coordinates are written in the axis order of the CRS and read back unchanged.
		boolean axisXY = SRSRegistry.getAxisXY(crsUri);
		String low = coordinate(xy.getMinX(), xy.getMinY(), axisXY);
		String lowHigh = coordinate(xy.getMinX(), xy.getMaxY(), axisXY);
		String high = coordinate(xy.getMaxX(), xy.getMaxY(), axisXY);
		String highLow = coordinate(xy.getMaxX(), xy.getMinY(), axisXY);
		return SourceGeometryLiteral.fromWkt("<" + crsUri + "> POLYGON((" + low + "," + highLow + ","
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
