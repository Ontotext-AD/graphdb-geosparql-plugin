package com.ontotext.trree.geosparql.jena;

import org.locationtech.jts.geom.Envelope;

/**
 * CRS84 candidate envelope together with how it was selected.
 *
 * <p>{@link CandidateBoundsKind#WORLD_FALLBACK} carries a diagnostic reason. Other kinds have no reason.
 */
record ProjectedCandidateBounds(Envelope envelope, CandidateBoundsKind kind, String fallbackReason) {
	static ProjectedCandidateBounds empty() {
		return new ProjectedCandidateBounds(new Envelope(), CandidateBoundsKind.EMPTY, null);
	}

	static ProjectedCandidateBounds nativeCrs84(Envelope envelope) {
		return new ProjectedCandidateBounds(envelope, CandidateBoundsKind.NATIVE_CRS84, null);
	}

	static ProjectedCandidateBounds transformed(Envelope envelope) {
		return new ProjectedCandidateBounds(envelope, CandidateBoundsKind.TRANSFORMED, null);
	}

	static ProjectedCandidateBounds worldFallback(String fallbackReason) {
		return new ProjectedCandidateBounds(ConservativeCrs84EnvelopeProjector.worldCrs84Envelope(),
				CandidateBoundsKind.WORLD_FALLBACK, fallbackReason);
	}
}
