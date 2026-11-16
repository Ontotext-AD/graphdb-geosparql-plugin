package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.BoundEvaluationEnvelope;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Removes disjoint candidates that lie inside rectangles which Jena certifies within the bound polygon.
 *
 * <p>The proof holds in the CRS where Jena evaluates the bound against a source, for a source whose envelope there
 * is exact: a non-empty source S lies inside its envelope E. If E lies inside a rectangle R and Jena establishes R
 * within the bound B, then S lies inside B and cannot be disjoint from it. Closed containment includes touching
 * boundaries.
 *
 * <p>Certification is paid for by the candidates it can remove. Trying a cell takes up to three Jena checks against
 * the bound, each about the work of evaluating one candidate, so the number of cells tried grows with the number
 * of candidates and a lookup with few candidates is not certified at all.
 */
final class InteriorRectanglePruning {
	private static final Logger LOGGER = LoggerFactory.getLogger(InteriorRectanglePruning.class);
	/** The time of one Jena check grows faster than the bound's size, and certification delays the first candidate. */
	private static final int MAX_BOUND_POINTS = 1024;
	private static final int MAX_DEPTH = 4;
	private static final int MAX_CELLS = 256;
	private static final int MAX_RECTANGLES = 64;
	private static final int CANDIDATES_PER_CELL = 32;
	private static final int INSET_DIVISOR = 16;

	private final BoundEvaluationEnvelope bound;
	private final List<Envelope> rectangles = new ArrayList<>();
	private int remainingCells;

	private record Cell(Envelope envelope, int depth) {
	}

	private InteriorRectanglePruning(BoundEvaluationEnvelope bound, int cells) {
		this.bound = bound;
		this.remainingCells = cells;
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
			int eligibleCount = searcher.count(new BooleanQuery.Builder()
					.add(candidates, BooleanClause.Occur.FILTER)
					.add(sources, BooleanClause.Occur.FILTER).build());
			int cells = Math.min(MAX_CELLS, eligibleCount / CANDIDATES_PER_CELL);
			if (cells == 0) {
				return candidates;
			}
			InteriorRectanglePruning cover = new InteriorRectanglePruning(bound, cells);
			cover.certify();
			return cover.retainUncertain(candidates, eligibleCount, sources, envelopeWithin, searcher);
		} catch (FactoryException | TransformException | IOException | RuntimeException e) {
			// Pruning is optional. Unavailable topology or counting leaves the complete candidate query intact.
			LOGGER.debug("Keeping all disjoint candidates because interior pruning is unavailable", e);
			return candidates;
		}
	}

	/**
	 * Certifies cells of the bound's envelope, larger cells before their quarters, so that the cells and rectangles
	 * allowed cover as much of the bound as they can.
	 */
	private void certify() throws FactoryException, TransformException {
		Deque<Cell> cells = new ArrayDeque<>();
		cells.add(new Cell(bound.envelope(), MAX_DEPTH));
		while (!cells.isEmpty() && remainingCells > 0 && rectangles.size() < MAX_RECTANGLES) {
			Cell cell = cells.remove();
			remainingCells--;
			Envelope envelope = cell.envelope();
			if (bound.covers(envelope)) {
				rectangles.add(envelope);
				continue;
			}
			double x = envelope.getMinX(), maxX = envelope.getMaxX();
			double y = envelope.getMinY(), maxY = envelope.getMaxY();
			/*
			 * A bound whose sides are not parallel to the axes, such as a rectangle of another CRS, misses each cell
			 * along its boundary by a sliver. The cell drawn in by a sixteenth of its size on every side keeps most
			 * of its area and is often within the bound where the whole cell is not.
			 */
			Envelope inset = new Envelope(x + (maxX - x) / INSET_DIVISOR, maxX - (maxX - x) / INSET_DIVISOR,
					y + (maxY - y) / INSET_DIVISOR, maxY - (maxY - y) / INSET_DIVISOR);
			if (bound.covers(inset)) {
				rectangles.add(inset);
				continue;
			}
			if (cell.depth() == 0 || bound.isApartFrom(envelope)) {
				continue;
			}
			double midX = x + (maxX - x) / 2;
			double midY = y + (maxY - y) / 2;
			if (!(x < midX && midX < maxX && y < midY && midY < maxY)) {
				continue;
			}
			cells.add(new Cell(new Envelope(x, midX, y, midY), cell.depth() - 1));
			cells.add(new Cell(new Envelope(midX, maxX, y, midY), cell.depth() - 1));
			cells.add(new Cell(new Envelope(x, midX, midY, maxY), cell.depth() - 1));
			cells.add(new Cell(new Envelope(midX, maxX, midY, maxY), cell.depth() - 1));
		}
	}

	private Query retainUncertain(Query candidates, int eligibleCount, Query sources,
			Function<Envelope, Query> envelopeWithin, IndexSearcher searcher) throws IOException {
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
