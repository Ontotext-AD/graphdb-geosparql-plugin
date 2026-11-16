package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.BoundEvaluationEnvelope;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.locationtech.jts.geom.Envelope;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Removes disjoint candidates that lie inside rectangles which Jena certifies within the bound polygon.
 *
 * <p>The proof holds in the CRS where Jena evaluates the bound against a source, for a source whose envelope there
 * is exact: a non-empty source S lies inside its envelope E. If E lies inside a rectangle R and Jena establishes R
 * within the bound B, then S lies inside B and cannot be disjoint from it. Closed containment includes touching
 * boundaries.
 */
final class InteriorRectanglePruning {
	private static final Logger LOGGER = LoggerFactory.getLogger(InteriorRectanglePruning.class);
	private static final int MAX_BOUND_POINTS = 256;
	private static final int MAX_DEPTH = 4;
	private static final int MAX_CELLS = 64;
	private static final int MAX_RECTANGLES = 16;
	private static final int INSET_DIVISOR = 16;

	private final BoundEvaluationEnvelope bound;
	private final List<Envelope> rectangles = new ArrayList<>();
	private int remainingCells = MAX_CELLS;

	private InteriorRectanglePruning(BoundEvaluationEnvelope bound) {
		this.bound = bound;
	}

	/**
	 * Prunes the native CRS84 sources of a native CRS84 bound by their CRS84 index envelopes, which are their own
	 * envelopes. Transformed and world-fallback index envelopes do not take part in the proof.
	 */
	static Query pruneByIndexEnvelopes(IndexGeometry bound, Query candidates, IndexSearcher searcher) {
		if (bound.candidateBoundsKind() != CandidateBoundsKind.NATIVE_CRS84) {
			return candidates;
		}
		return prune(bound.evaluationEnvelope(IndexGeometry.INDEX_CRS, true).orElseThrow(), candidates,
				LuceneGeoDocumentSchema.candidateBoundsKindQuery(CandidateBoundsKind.NATIVE_CRS84),
				LuceneGeoDocumentSchema::envelopeWithinQuery, searcher);
	}

	/**
	 * Prunes the candidates among the sources that Jena evaluates against the bound as given.
	 *
	 * @param sources the sources whose exact envelopes are in the bound's evaluation CRS
	 * @param envelopeWithin matches those sources whose envelopes lie inside a closed rectangle
	 */
	static Query prune(BoundEvaluationEnvelope bound, Query candidates, Query sources,
			Function<Envelope, Query> envelopeWithin, IndexSearcher searcher) {
		try {
			if (!bound.isValidPolygonOfAtMost(MAX_BOUND_POINTS)) {
				return candidates;
			}
			InteriorRectanglePruning cover = new InteriorRectanglePruning(bound);
			cover.certify(bound.envelope(), MAX_DEPTH);
			return cover.retainUncertain(candidates, sources, envelopeWithin, searcher);
		} catch (FactoryException | TransformException | IOException | RuntimeException e) {
			// Pruning is optional. Unavailable topology or counting leaves the complete candidate query intact.
			LOGGER.debug("Keeping all disjoint candidates because interior pruning is unavailable", e);
			return candidates;
		}
	}

	private void certify(Envelope cell, int depth) throws FactoryException, TransformException {
		if (remainingCells == 0 || rectangles.size() == MAX_RECTANGLES) {
			return;
		}
		remainingCells--;
		if (bound.covers(cell)) {
			rectangles.add(cell);
			return;
		}
		double x = cell.getMinX(), maxX = cell.getMaxX();
		double y = cell.getMinY(), maxY = cell.getMaxY();
		/*
		 * A bound whose sides are not parallel to the axes, such as a rectangle of another CRS, misses each cell
		 * along its boundary by a sliver. The cell drawn in by a sixteenth of its size on every side keeps most of
		 * its area and is often within the bound where the whole cell is not.
		 */
		Envelope inset = new Envelope(x + (maxX - x) / INSET_DIVISOR, maxX - (maxX - x) / INSET_DIVISOR,
				y + (maxY - y) / INSET_DIVISOR, maxY - (maxY - y) / INSET_DIVISOR);
		if (bound.covers(inset)) {
			rectangles.add(inset);
			return;
		}
		if (depth == 0 || bound.isApartFrom(cell)) {
			return;
		}
		double midX = x + (maxX - x) / 2;
		double midY = y + (maxY - y) / 2;
		if (!(x < midX && midX < maxX && y < midY && midY < maxY)) {
			return;
		}
		certify(new Envelope(x, midX, y, midY), depth - 1);
		certify(new Envelope(midX, maxX, y, midY), depth - 1);
		certify(new Envelope(x, midX, midY, maxY), depth - 1);
		certify(new Envelope(midX, maxX, midY, maxY), depth - 1);
	}

	private Query retainUncertain(Query candidates, Query sources, Function<Envelope, Query> envelopeWithin,
			IndexSearcher searcher) throws IOException {
		if (rectangles.isEmpty()) {
			return candidates;
		}
		BooleanQuery.Builder contained = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
		for (Envelope rectangle : rectangles) {
			contained.add(envelopeWithin.apply(rectangle), BooleanClause.Occur.SHOULD);
		}
		Query containedSources = new BooleanQuery.Builder()
				.add(sources, BooleanClause.Occur.FILTER)
				.add(contained.build(), BooleanClause.Occur.FILTER).build();
		Query eligibleCandidates = new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(sources, BooleanClause.Occur.FILTER).build();
		int eligibleCount = searcher.count(eligibleCandidates);
		if (eligibleCount == 0) {
			return candidates;
		}
		Query excludedCandidates = new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(containedSources, BooleanClause.Occur.FILTER).build();
		// Avoid repeated containment filtering unless it removes most eligible source documents.
		if (2L * searcher.count(excludedCandidates) <= eligibleCount) {
			return candidates;
		}
		return new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(containedSources, BooleanClause.Occur.MUST_NOT).build();
	}
}
