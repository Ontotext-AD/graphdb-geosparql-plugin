package com.ontotext.trree.geosparql;

/**
 * Lucene lookup policy used to find uncertain candidates or prove definite disjoint matches.
 */
public enum CandidateLookupPolicy {
	/** Return source documents whose envelopes may support a relation and require exact evaluation. */
	ENVELOPE_INTERSECTS,
	/**
	 * Require equal envelopes where a pair of {@link com.ontotext.trree.geosparql.jena.IndexGeometry#isProvable
	 * provable} geometries is looked up in its evaluation CRS; retain intersection candidates otherwise.
	 */
	ENVELOPE_EQUALS,
	/** Partition spatial source documents into definite disjoint matches and uncertain intersections. */
	DISJOINT_PARTITIONED
}
