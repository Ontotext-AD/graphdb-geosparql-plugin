package com.ontotext.trree.geosparql.jena;

import org.locationtech.jts.geom.Envelope;

/**
 * XY envelope of a bound source in the CRS where Jena evaluates it against the sources of one CRS.
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
	public static final BoundEvaluationEnvelope UNRELATED = new BoundEvaluationEnvelope(null, false);

	private final Envelope envelope;
	private final boolean fillsEnvelope;

	BoundEvaluationEnvelope(Envelope envelope, boolean fillsEnvelope) {
		this.envelope = envelope;
		this.fillsEnvelope = fillsEnvelope;
	}

	/** Whether Jena can evaluate the bound against sources of the CRS. */
	public boolean isRelated() {
		return envelope != null;
	}

	public Envelope envelope() {
		return new Envelope(envelope);
	}

	/**
	 * Whether the bound, as Jena evaluates it, is a polygon that covers every point of its envelope. A source whose
	 * envelope lies inside that envelope then meets the bound and cannot be disjoint from it.
	 */
	public boolean fillsEnvelope() {
		return fillsEnvelope;
	}
}
