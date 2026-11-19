package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;

import java.util.List;
import java.util.function.Function;

/**
 * Plugin-internal seam between GraphDB repository handling and GeoSPARQL candidate indexing.
 *
 * <p>Writes store derived index geometries. Candidate reads return entity groups with matching source geometry literal
 * snapshots, while bound-entity reads return stored source geometry literal snapshots directly. Public visibility
 * supports the Lucene adapter in a sibling package.
 */
public interface GeoSparqlIndexer {
	/**
	 * Initializes the indexer.
	 */
	void initialize() throws Exception;

	/**
	 * Replaces all indexed geometry documents for an entity during an incremental update.
	 *
	 * @param subject  id of the subject
	 * @param geometries geometries that corresponds to asWKT/asGML's object
	 */
	void indexGeometryList(long subject, Function<Long, String> subjectMapper, List<IndexGeometry> geometries);

	/**
	 * Returns the candidates that an exact evaluation against one bound source can relate by any relation other
	 * than a disjoint one.
	 *
	 * <p>Jena evaluates a pair in the CRS of its left operand, the subject, so a lookup needs to know which side
	 * of the pair the candidates are on. An adapter may leave out a source that it can show to share no point with
	 * the bound. Omission from this result does not itself prove disjointness; partitioned disjoint lookup has its
	 * own proofs.
	 *
	 * @param boundSourceIndexGeometry index geometry of one bound source geometry literal
	 * @param candidateIsSubject whether each candidate is the subject and the bound source the object
	 * @return a closeable iterator over entity groups and their matching source geometry literal snapshots
	 */
	CloseableIterator<CandidateEntity> getEnvelopeIntersections(IndexGeometry boundSourceIndexGeometry,
			boolean candidateIsSubject);

	/**
	 * Returns uncertain equality candidates. Adapters may require equal envelopes where both envelopes are exact
	 * for the pair and both geometries are valid, and retain intersection lookup otherwise: an invalid geometry can
	 * have a part that Jena's equality leaves out but that still widens its envelope. Every candidate needs exact
	 * evaluation.
	 *
	 * @param candidateIsSubject whether each candidate is the subject and the bound source the object
	 */
	default CloseableIterator<CandidateEntity> getEnvelopeEqualityCandidates(IndexGeometry boundSourceIndexGeometry,
			boolean candidateIsSubject) {
		return getEnvelopeIntersections(boundSourceIndexGeometry, candidateIsSubject);
	}

	/**
	 * Returns mixed-CRS source documents that a partitioned disjoint traversal must exact-evaluate because coordinate
	 * cleanup in the target CRS prevents a safe definite envelope classification.
	 */
	default CloseableIterator<CandidateEntity> getDisjointTransformCleanupCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		return getAllEntities();
	}

	/**
	 * Returns uncertain candidates for one bound source in a partitioned disjoint traversal.
	 *
	 * <p>Adapters may remove candidates that they can prove not to be disjoint from the bound. The returned
	 * candidates carry complete source payloads. Callers own and must close the iterator. Implementations without
	 * such a proof retain ordinary intersection candidates.
	 *
	 * @param boundSourceIndexGeometry index geometry of one bound source geometry literal
	 * @param candidateIsSubject whether each candidate is the subject and the bound source the object
	 * @return a closeable iterator over uncertain entity groups and their matching source geometry literal snapshots
	 */
	default CloseableIterator<CandidateEntity> getEnvelopeDisjointUncertainCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
		return getEnvelopeIntersections(boundSourceIndexGeometry, candidateIsSubject);
	}

	/**
	 * Returns one lightweight result per spatial source document that is proven disjoint from the supplied bound
	 * source without exact evaluation. Together with the uncertain and cleanup candidates of the same bound and
	 * direction, these are all the spatial source documents that can be disjoint from it.
	 *
	 * @param boundSourceIndexGeometry index geometry of one non-empty bound source geometry literal
	 * @param candidateIsSubject whether each candidate is the subject and the bound source the object
	 * @return source-document metadata for proven disjoint candidates
	 */
	CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
			IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject);

	/** Returns entities represented by non-spatial empty-sentinel source documents for exact evaluation. */
	CloseableIterator<CandidateEntity> getNonSpatialCandidates();

	/**
	 * Opens the candidate lookups of one partitioned disjoint traversal. These lookups give what the separate
	 * disjoint lookups of this indexer give; an adapter that can read every lookup of the traversal from one
	 * snapshot of the index does so.
	 *
	 * @param candidateIsSubject whether each candidate is the subject and the bound source the object
	 */
	default DisjointCandidateLookup openDisjointCandidateLookup(boolean candidateIsSubject) {
		return new DisjointCandidateLookup() {
			@Override
			public CloseableIterator<EnvelopeDisjointCandidate> definiteMatches(IndexGeometry bound) {
				return getEnvelopeDisjointCandidates(bound, candidateIsSubject);
			}

			@Override
			public CloseableIterator<CandidateEntity> uncertainCandidates(IndexGeometry bound) {
				return getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject);
			}

			@Override
			public CloseableIterator<CandidateEntity> transformCleanupCandidates(IndexGeometry bound) {
				return getDisjointTransformCleanupCandidates(bound, candidateIsSubject);
			}

			@Override
			public CloseableIterator<CandidateEntity> nonSpatialCandidates() {
				return getNonSpatialCandidates();
			}

			@Override
			public void close() {
			}
		};
	}

	/** Returns every indexed entity, including entities represented only by non-spatial empty sentinels. */
	CloseableIterator<CandidateEntity> getAllEntities();

	/**
	 * Streams all stored source geometry literal snapshots for one entity. Id 0 selects every stored document for
	 * internal schema and lifecycle reads. A negative id, which GraphDB gives a value the repository does not hold,
	 * selects none.
	 *
	 * @param subject subject id of the entity
	 * @return a closeable iterator over source geometry literal snapshots
	 */
	CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsFor(long subject);

	void initSettings();

	void begin() throws Exception;

	/** Returns whether this indexer is retaining transaction outcome state. */
	default boolean isTransactionActive() {
		return false;
	}

	void commit() throws Exception;

	/** Discards writer changes while retaining state needed to finalize the GraphDB transaction outcome. */
	default void discardUncommittedChanges() throws Exception {
		throw new UnsupportedOperationException(
				"Discarding index changes while retaining transaction outcome state is not supported.");
	}

	/**
	 * Makes the most recently committed index transaction final after GraphDB completes the RDF transaction.
	 */
	default void complete() throws Exception {
	}

	void rollback() throws Exception;

	/**
	 * Restores the pre-transaction index while retaining fail-closed recovery state when another durable plugin state
	 * could not be restored.
	 */
	default void rollback(boolean recoveryRequired) throws Exception {
		if (recoveryRequired) {
			throw new UnsupportedOperationException("Retaining index recovery state is not supported.");
		}
		rollback();
	}

	/** Appends the index geometry derived from one source geometry literal during a streaming full index build. */
	void appendGeometry(long subject, Function<Long, String> subjectMapper, IndexGeometry geometry);

	void freshIndex() throws Exception;
}
