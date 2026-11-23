package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;

import java.io.Closeable;

/**
 * The candidate lookups of one partitioned disjoint traversal, for one binding direction.
 *
 * <p>The traversal reads the index in phases, and which phase selects a source can depend on what the index holds.
 * An adapter whose lookups read one snapshot of the index keeps every source in exactly one phase when another
 * transaction commits during the traversal. The caller closes the lookup and every iterator it returns; an iterator
 * stays readable after the lookup that returned it is closed.
 *
 * <p>This type is plugin-internal despite its cross-package public visibility.
 */
public interface DisjointCandidateLookup extends Closeable {
	/** The definite matches for one bound source; see {@link GeoSparqlIndexer#getEnvelopeDisjointCandidates}. */
	CloseableIterator<EnvelopeDisjointCandidate> definiteMatches(IndexGeometry boundSourceIndexGeometry);

	/**
	 * The uncertain candidates for one bound source; see
	 * {@link GeoSparqlIndexer#getEnvelopeDisjointUncertainCandidates}.
	 */
	CloseableIterator<CandidateEntity> uncertainCandidates(IndexGeometry boundSourceIndexGeometry);

	/**
	 * The transform-cleanup candidates for one bound source; see
	 * {@link GeoSparqlIndexer#getDisjointTransformCleanupCandidates}.
	 */
	CloseableIterator<CandidateEntity> transformCleanupCandidates(IndexGeometry boundSourceIndexGeometry);

	/** Entities represented by non-spatial empty sentinels; see {@link GeoSparqlIndexer#getNonSpatialCandidates}. */
	CloseableIterator<CandidateEntity> nonSpatialCandidates();
}
