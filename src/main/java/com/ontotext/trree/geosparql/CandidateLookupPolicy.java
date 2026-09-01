package com.ontotext.trree.geosparql;

/**
 * Lucene lookup policy used to find uncertain candidates or prove definite disjoint matches.
 */
public enum CandidateLookupPolicy {
	/** Return source documents whose envelopes may support a relation and require exact evaluation. */
	ENVELOPE_INTERSECTS,
	/** Partition spatial source documents into definite disjoint matches and uncertain intersections. */
	DISJOINT_PARTITIONED
}
