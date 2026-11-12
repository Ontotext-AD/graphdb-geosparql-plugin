package com.ontotext.trree.geosparql;

/**
 * Lucene lookup policy used to find uncertain candidates or prove definite disjoint matches.
 */
public enum CandidateLookupPolicy {
	/** Return source documents whose envelopes may support a relation and require exact evaluation. */
	ENVELOPE_INTERSECTS,
	/** Require equal envelopes for native CRS84 equality pairs and retain transformed intersection candidates. */
	ENVELOPE_EQUALS,
	/** Partition spatial source documents into definite disjoint matches and uncertain intersections. */
	DISJOINT_PARTITIONED
}
