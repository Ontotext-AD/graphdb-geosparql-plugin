package com.ontotext.trree.geosparql;

/**
 * Lightweight result for one indexed source document whose CRS84 index envelope is disjoint from a bound envelope.
 *
 * <p>The topological dimension is retained so relation evaluation can apply named-relation eligibility, notably the
 * area/area restriction for RCC8. The source geometry literal is deliberately absent: envelope separation already
 * proves geometric disjointness for relation families whose semantic preconditions are satisfied.
 *
 * @param entityId positive GraphDB entity ID
 * @param sourceTopologicalDimension topological dimension of the indexed source geometry
 */
public record EnvelopeDisjointCandidate(long entityId, int sourceTopologicalDimension) {
	public EnvelopeDisjointCandidate {
		if (entityId <= 0) {
			throw new IllegalArgumentException("Envelope-disjoint candidate entity id must be positive.");
		}
	}
}
