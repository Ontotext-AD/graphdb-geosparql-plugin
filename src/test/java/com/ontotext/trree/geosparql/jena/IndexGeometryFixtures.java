package com.ontotext.trree.geosparql.jena;

import org.locationtech.jts.geom.Envelope;

/**
 * Test helpers for associating a source geometry literal with a chosen index envelope.
 */
public final class IndexGeometryFixtures {
	private IndexGeometryFixtures() {
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
