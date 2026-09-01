package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Traverses candidate entities for one bound side of a GeoSPARQL property relation.
 *
 * <p>Envelope-intersection traversal returns uncertain source pairs for exact evaluation and ends after the selective
 * index-envelope phase. Full-scan traversal returns complete candidate source sets. Under the candidate-envelope
 * containment contract, partitioned disjoint traversal first returns source documents whose envelopes prove a match,
 * then exact-evaluates uncertain envelope intersections after removing eligible envelope-contained definite
 * non-matches. It then exact-evaluates mixed-CRS pairs whose cleanup target prevents a safe envelope classification,
 * and finally evaluates non-spatial empty sentinels.
 *
 * <p>The traversal owns its active Lucene iterator. It closes every phase before opening the next and closes the
 * active iterator when traversal ends early.
 */
final class RelationCandidateTraversal implements CloseableIterator<RelationCandidateTraversal.Candidate> {
	enum MatchCertainty {
		DEFINITE_MATCH,
		REQUIRES_EXACT_EVALUATION
	}

	private enum TraversalPhase {
		FULL_SCAN,
		ENVELOPE_INTERSECTIONS,
		DEFINITE_MATCHES,
		UNCERTAIN_CANDIDATES,
		DISJOINT_TRANSFORM_CLEANUP_CANDIDATES,
		EMPTY_SENTINELS,
		DONE
	}

	private final GeoSparqlIndexer indexer;
	private final GeoSparqlPropertyRelation relation;
	private final List<IndexGeometry> boundIndexGeometries;
	private final boolean boundSubject;
	private final Logger logger;

	private TraversalPhase phase;
	private int boundIndex;
	private IndexGeometry currentBoundIndexGeometry;
	private CloseableIterator<EnvelopeDisjointCandidate> currentDefiniteCandidates;
	private CloseableIterator<CandidateEntity> currentExactCandidates;
	private Candidate next;
	private boolean closed;

	RelationCandidateTraversal(GeoSparqlIndexer indexer, GeoSparqlPropertyRelation relation,
			Collection<IndexGeometry> boundIndexGeometries, Logger logger) {
		this(indexer, relation, boundIndexGeometries, true, logger);
	}

	RelationCandidateTraversal(GeoSparqlIndexer indexer, GeoSparqlPropertyRelation relation,
			Collection<IndexGeometry> boundIndexGeometries, boolean boundSubject, Logger logger) {
		this.indexer = indexer;
		this.relation = relation;
		this.boundIndexGeometries = List.copyOf(boundIndexGeometries);
		this.boundSubject = boundSubject;
		this.logger = logger;
		this.phase = initialPhase();
	}

	@Override
	public boolean hasNext() {
		if (closed) {
			return false;
		}
		if (next != null) {
			return true;
		}
		try {
			next = loadNextCandidate();
			return next != null;
		} catch (RuntimeException e) {
			close();
			throw e;
		}
	}

	@Override
	public Candidate next() {
		if (!hasNext()) {
			throw new NoSuchElementException("No more relation candidates.");
		}
		Candidate result = next;
		next = null;
		return result;
	}

	@Override
	public void close() {
		closed = true;
		closeActiveIterator();
		next = null;
		phase = TraversalPhase.DONE;
	}

	private TraversalPhase initialPhase() {
		// No bound source exists to satisfy the relation. This is distinct from an actual empty geometry literal.
		if (boundIndexGeometries.isEmpty()) {
			return TraversalPhase.DONE;
		}
		return switch (relation.getCandidateLookupPolicy()) {
			case ENVELOPE_INTERSECTS -> TraversalPhase.ENVELOPE_INTERSECTIONS;
			case DISJOINT_PARTITIONED -> initialDisjointPhase();
		};
	}

	private TraversalPhase initialDisjointPhase() {
		/*
		 * An empty bound source has no envelope to partition. Exact evaluation is also semantically necessary
		 * because different empty geometry types do not all produce the same disjoint result. Falling back once
		 * for the complete bound source set also keeps mixed empty/non-empty entities correct.
		 */
		boolean hasEmptyBoundSource = boundIndexGeometries.stream()
				.anyMatch(indexGeometry -> !indexGeometry.isSpatialCandidate());
		if (hasEmptyBoundSource) {
			return TraversalPhase.FULL_SCAN;
		}
		boolean hasEligibleBoundSource = boundIndexGeometries.stream()
				.anyMatch(this::boundSourceCanParticipateInDisjointPartition);
		return hasEligibleBoundSource ? TraversalPhase.DEFINITE_MATCHES : TraversalPhase.DONE;
	}

	private Candidate loadNextCandidate() {
		while (phase != TraversalPhase.DONE) {
			Candidate candidate = switch (phase) {
				case FULL_SCAN -> loadNextFullScanCandidate();
				case ENVELOPE_INTERSECTIONS -> loadNextEnvelopeIntersection();
				case DEFINITE_MATCHES -> loadNextDefiniteMatch();
				case UNCERTAIN_CANDIDATES -> loadNextUncertainCandidate();
				case DISJOINT_TRANSFORM_CLEANUP_CANDIDATES -> loadNextDisjointTransformCleanupCandidate();
				case EMPTY_SENTINELS -> loadNextEmptySentinel();
				case DONE -> null;
			};
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	private Candidate loadNextFullScanCandidate() {
		if (currentExactCandidates == null) {
			currentExactCandidates = indexer.getAllEntities();
		}
		if (currentExactCandidates.hasNext()) {
			return Candidate.exactAgainstCompleteBoundSet(currentExactCandidates.next());
		}
		finishTraversal();
		return null;
	}

	private Candidate loadNextEnvelopeIntersection() {
		while (true) {
			if (currentExactCandidates != null && currentExactCandidates.hasNext()) {
				return Candidate.exactForBoundSource(currentExactCandidates.next(),
						currentBoundIndexGeometry.sourceGeometryLiteral());
			}
			closeActiveIterator();
			if (!openNextEnvelopeIntersection()) {
				finishTraversal();
				return null;
			}
		}
	}

	private Candidate loadNextDefiniteMatch() {
		while (true) {
			if (currentDefiniteCandidates != null && currentDefiniteCandidates.hasNext()) {
				EnvelopeDisjointCandidate candidate = currentDefiniteCandidates.next();
				/*
				 * Candidate classification stays at source-document level. An entity may contain another geometry
				 * whose envelope intersects the bound, but one eligible separated source pair is enough for the
				 * relation's existential semantics.
				 */
				if (relation.envelopeDisjointIsDefiniteMatch(candidate.sourceTopologicalDimension(),
						currentBoundIndexGeometry.sourceTopologicalDimension())) {
					return Candidate.definiteMatch(candidate.entityId());
				}
				continue;
			}
			closeActiveIterator();
			if (openNextEnvelopeDisjointPartition()) {
				continue;
			}
			beginPhase(TraversalPhase.UNCERTAIN_CANDIDATES);
			return null;
		}
	}

	private Candidate loadNextUncertainCandidate() {
		while (true) {
			if (currentExactCandidates != null && currentExactCandidates.hasNext()) {
				return Candidate.exactForBoundSource(currentExactCandidates.next(),
						currentBoundIndexGeometry.sourceGeometryLiteral());
			}
			closeActiveIterator();
			if (openNextEnvelopeIntersection()) {
				continue;
			}
			beginPhase(TraversalPhase.DISJOINT_TRANSFORM_CLEANUP_CANDIDATES);
			return null;
		}
	}

	private Candidate loadNextDisjointTransformCleanupCandidate() {
		while (true) {
			if (currentExactCandidates != null && currentExactCandidates.hasNext()) {
				return Candidate.exactForBoundSource(currentExactCandidates.next(),
						currentBoundIndexGeometry.sourceGeometryLiteral());
			}
			closeActiveIterator();
			if (openNextDisjointTransformCleanupCandidates()) {
				continue;
			}
			beginPhase(TraversalPhase.EMPTY_SENTINELS);
			return null;
		}
	}

	private boolean openNextDisjointTransformCleanupCandidates() {
		while (boundIndex < boundIndexGeometries.size()) {
			currentBoundIndexGeometry = boundIndexGeometries.get(boundIndex++);
			if (!currentBoundIndexGeometry.isSpatialCandidate()) {
				continue;
			}
			if (!boundSourceCanParticipateInDisjointPartition(currentBoundIndexGeometry)) {
				continue;
			}
			currentExactCandidates = indexer.getDisjointTransformCleanupCandidates(
					currentBoundIndexGeometry, !boundSubject);
			return true;
		}
		return false;
	}

	private Candidate loadNextEmptySentinel() {
		if (currentExactCandidates == null) {
			currentExactCandidates = indexer.getNonSpatialCandidates();
		}
		if (currentExactCandidates.hasNext()) {
			/*
			 * Empty sentinels have no spatial field, so they cannot participate in either envelope partition.
			 * Exact evaluation against the complete non-empty bound set preserves empty-geometry semantics.
			 */
			return Candidate.exactAgainstCompleteBoundSet(currentExactCandidates.next());
		}
		finishTraversal();
		return null;
	}

	private boolean openNextEnvelopeDisjointPartition() {
		while (boundIndex < boundIndexGeometries.size()) {
			currentBoundIndexGeometry = boundIndexGeometries.get(boundIndex++);
			if (!boundSourceCanParticipateInDisjointPartition(currentBoundIndexGeometry)) {
				continue;
			}
			currentDefiniteCandidates = indexer.getEnvelopeDisjointCandidates(
					currentBoundIndexGeometry, !boundSubject);
			return true;
		}
		return false;
	}

	private boolean openNextEnvelopeIntersection() {
		while (boundIndex < boundIndexGeometries.size()) {
			currentBoundIndexGeometry = boundIndexGeometries.get(boundIndex++);
			if (!currentBoundIndexGeometry.isSpatialCandidate()) {
				continue;
			}
			if (phase == TraversalPhase.UNCERTAIN_CANDIDATES
					&& !boundSourceCanParticipateInDisjointPartition(currentBoundIndexGeometry)) {
				continue;
			}
			currentExactCandidates = phase == TraversalPhase.UNCERTAIN_CANDIDATES
					? indexer.getEnvelopeDisjointUncertainCandidates(currentBoundIndexGeometry)
					: indexer.getEnvelopeIntersections(currentBoundIndexGeometry);
			return true;
		}
		return false;
	}

	private boolean boundSourceCanParticipateInDisjointPartition(IndexGeometry boundSource) {
		return relation.boundSourceCanParticipateInDisjointPartition(
				boundSource.sourceTopologicalDimension());
	}

	private void beginPhase(TraversalPhase nextPhase) {
		closeActiveIterator();
		phase = nextPhase;
		boundIndex = 0;
	}

	private void finishTraversal() {
		closeActiveIterator();
		phase = TraversalPhase.DONE;
	}

	private void closeActiveIterator() {
		closeIterator(currentDefiniteCandidates);
		closeIterator(currentExactCandidates);
		currentDefiniteCandidates = null;
		currentExactCandidates = null;
		currentBoundIndexGeometry = null;
	}

	private void closeIterator(CloseableIterator<?> iterator) {
		if (iterator == null) {
			return;
		}
		try {
			iterator.close();
		} catch (IOException e) {
			logger.warn("Unable to close GeoSPARQL iterator.", e);
		}
	}

	/** Candidate entity classified by whether exact evaluation remains necessary. */
	static final class Candidate {
		private final long entityId;
		private final MatchCertainty matchCertainty;
		private final CandidateEntity candidateEntity;
		private final SourceGeometryLiteral boundSourceGeometryLiteral;

		private Candidate(long entityId, MatchCertainty matchCertainty, CandidateEntity candidateEntity,
				SourceGeometryLiteral boundSourceGeometryLiteral) {
			this.entityId = entityId;
			this.matchCertainty = matchCertainty;
			this.candidateEntity = candidateEntity;
			this.boundSourceGeometryLiteral = boundSourceGeometryLiteral;
		}

		private static Candidate definiteMatch(long entityId) {
			return new Candidate(entityId, MatchCertainty.DEFINITE_MATCH, null, null);
		}

		private static Candidate exactAgainstCompleteBoundSet(CandidateEntity candidateEntity) {
			return exactCandidate(candidateEntity, null);
		}

		private static Candidate exactForBoundSource(CandidateEntity candidateEntity,
				SourceGeometryLiteral boundSourceGeometryLiteral) {
			if (boundSourceGeometryLiteral == null) {
				throw new IllegalArgumentException("Exact source-pair candidate requires a bound source.");
			}
			return exactCandidate(candidateEntity, boundSourceGeometryLiteral);
		}

		private static Candidate exactCandidate(CandidateEntity candidateEntity,
				SourceGeometryLiteral boundSourceGeometryLiteral) {
			if (candidateEntity == null) {
				throw new IllegalArgumentException("Exact relation candidate requires candidate source geometry.");
			}
			return new Candidate(candidateEntity.entityId(), MatchCertainty.REQUIRES_EXACT_EVALUATION,
					candidateEntity, boundSourceGeometryLiteral);
		}

		long entityId() {
			return entityId;
		}

		MatchCertainty matchCertainty() {
			return matchCertainty;
		}

		CandidateEntity exactCandidateEntity() {
			if (matchCertainty != MatchCertainty.REQUIRES_EXACT_EVALUATION || candidateEntity == null) {
				throw new IllegalStateException("Only exact-evaluation candidates carry source geometry.");
			}
			return candidateEntity;
		}

		Optional<SourceGeometryLiteral> boundSourceGeometryLiteral() {
			return Optional.ofNullable(boundSourceGeometryLiteral);
		}
	}
}
