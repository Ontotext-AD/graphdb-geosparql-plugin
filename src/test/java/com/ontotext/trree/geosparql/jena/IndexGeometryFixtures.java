package com.ontotext.trree.geosparql.jena;

import org.locationtech.jts.geom.Envelope;

/**
 * Test helpers for associating a source geometry literal with a chosen index envelope.
 */
public final class IndexGeometryFixtures {
	private IndexGeometryFixtures() {
	}

	/**
	 * Whether the geometry, as a bound, is a CRS84 polygon that fills its envelope, which lookup by CRS84 index
	 * envelopes uses to show a source inside the bound.
	 */
	public static boolean fillsCrs84Envelope(IndexGeometry geometry) {
		return IndexGeometry.INDEX_CRS.equals(geometry.sourceGeometryLiteral().effectiveCrsUri())
				&& geometry.evaluationEnvelope(IndexGeometry.INDEX_CRS, false)
						.map(BoundEvaluationEnvelope::fillsEnvelope).orElse(false);
	}

	public static IndexGeometry withIndexEnvelope(SourceGeometryLiteral source, Envelope indexEnvelope) {
		return withIndexEnvelope(source, indexEnvelope, CandidateBoundsKind.TRANSFORMED, null);
	}

	public static IndexGeometry withIndexEnvelope(SourceGeometryLiteral source, Envelope indexEnvelope,
			CandidateBoundsKind kind, String fallbackReason) {
		int topologicalDimension = source.asGeometryWrapper().getDimensionInfo().getTopological();
		return new IndexGeometry(source, indexEnvelope, topologicalDimension, kind, fallbackReason);
	}
}
