package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;

import java.util.List;
import java.util.Set;

/**
 * Immutable result for one entity returned by a Lucene candidate lookup.
 *
 * <p>The source list contains the complete source geometry literals represented by the Lucene documents matched by
 * that lookup. It is the complete entity source set for a full scan, but may be a lookup-specific subset for a
 * spatial query.
 *
 * <p>This type is plugin-internal; public visibility allows the Lucene adapter to return it across packages.
 */
public final class CandidateEntity {
	private final long entityId;
	private final List<SourceGeometryLiteral> matchingSourceGeometryLiterals;

	/**
	 * Creates a candidate group with a positive GraphDB entity id and at least one matching geometry.
	 * The supplied unique source set is defensively copied.
	 *
	 * @param entityId positive GraphDB entity id shared by the matching documents
	 * @param matchingSourceGeometryLiterals non-empty unique source values decoded from those documents
	 */
	public CandidateEntity(long entityId, Set<SourceGeometryLiteral> matchingSourceGeometryLiterals) {
		if (entityId <= 0) {
			throw new IllegalArgumentException("Candidate entity id must be positive.");
		}
		this.matchingSourceGeometryLiterals = List.copyOf(matchingSourceGeometryLiterals);
		if (this.matchingSourceGeometryLiterals.isEmpty()) {
			throw new IllegalArgumentException("Candidate entity must contain at least one matching source geometry.");
		}
		this.entityId = entityId;
	}

	/** Returns the GraphDB entity id shared by the matching Lucene documents. */
	public long entityId() {
		return entityId;
	}

	/** Returns the immutable matching source geometry literal snapshots for this lookup. */
	public List<SourceGeometryLiteral> matchingSourceGeometryLiterals() {
		return matchingSourceGeometryLiterals;
	}
}
