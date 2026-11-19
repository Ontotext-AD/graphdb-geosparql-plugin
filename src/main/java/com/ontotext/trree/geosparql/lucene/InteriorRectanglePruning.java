package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.BoundEvaluationEnvelope;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Keeps the disjoint candidates of one CRS that reach a part of the bound's envelope which Jena does not certify
 * within the bound polygon.
 *
 * <p>The proof holds in the CRS where Jena evaluates the bound against a source, for a source whose envelope there
 * is exact. The cells of the bound's envelope are split until Jena certifies each within the bound or leaves it to
 * the remainder, so the certified rectangles and the remainder together tile the envelope. A non-empty source S lies
 * inside its envelope E. If E lies inside the bound's envelope and meets no cell of the remainder, E lies inside the
 * certified rectangles, so S lies inside the bound B and cannot be disjoint from it. Closed cells keep a source that
 * only touches the remainder.
 *
 * <p>Certification is paid for by the candidates it can remove. Trying a cell takes up to three Jena checks against
 * the bound, each about the work of evaluating one candidate, so the number of cells tried grows with the number
 * of candidates and a lookup with few candidates is not certified at all. Cells of the remainder that share a whole
 * side are joined, and each rectangle left becomes a clause of the candidate query. A clause is a range lookup over
 * every source that runs again for each page of candidates, and costs more than many candidates do, so the remainder
 * is kept small and cells are split only a few times.
 */
final class InteriorRectanglePruning {
	private static final Logger LOGGER = LoggerFactory.getLogger(InteriorRectanglePruning.class);
	/** The time of one Jena check grows faster than the bound's size, and certification delays the first candidate. */
	private static final int MAX_BOUND_POINTS = 1024;
	/** The cells of a deeper level along the bound's boundary each remove few candidates and add clauses. */
	private static final int MAX_DEPTH = 5;
	private static final int MAX_CELLS = 256;
	/** Cells along a boundary often share sides, so the remainder has fewer clauses than cells once joined. */
	private static final int MAX_REMAINDER = 128;
	private static final int CANDIDATES_PER_CELL = 32;
	private static final int INSET_DIVISOR = 16;
	private static final Comparator<Envelope> ROWS = Comparator.comparingDouble(Envelope::getMinY)
			.thenComparingDouble(Envelope::getMaxY).thenComparingDouble(Envelope::getMinX);
	private static final Comparator<Envelope> COLUMNS = Comparator.comparingDouble(Envelope::getMinX)
			.thenComparingDouble(Envelope::getMaxX).thenComparingDouble(Envelope::getMinY);

	private final BoundEvaluationEnvelope bound;
	private final List<Envelope> remainder = new ArrayList<>();
	private int remainingCells;
	private boolean certified;

	private record Cell(Envelope envelope, int depth) {
	}

	private InteriorRectanglePruning(BoundEvaluationEnvelope bound, int cells) {
		this.bound = bound;
		this.remainingCells = cells;
	}

	/**
	 * Keeps the sources of one CRS that may be disjoint from the bound as Jena evaluates it there.
	 *
	 * @param meeting the sources whose envelopes meet the bound's, of every CRS
	 * @param sourceCrs the sources whose exact envelopes are in the bound's evaluation CRS
	 * @return the sources of the CRS among {@code meeting} that the certified rectangles do not hold
	 */
	static Query prune(BoundEvaluationEnvelope bound, Query meeting, Query sourceCrs, IndexSearcher searcher) {
		try {
			if (!bound.isValidPolygonOfAtMost(MAX_BOUND_POINTS) || !IndexGeometry.hasNormalArea(bound.envelope())) {
				return meeting;
			}
			Query eligible = new BooleanQuery.Builder()
					.add(meeting, BooleanClause.Occur.FILTER)
					.add(sourceCrs, BooleanClause.Occur.FILTER).build();
			int eligibleCount = searcher.count(eligible);
			int cells = Math.min(MAX_CELLS, eligibleCount / CANDIDATES_PER_CELL);
			if (cells == 0) {
				return meeting;
			}
			InteriorRectanglePruning cover = new InteriorRectanglePruning(bound, cells);
			cover.certify();
			return cover.retainUncertain(meeting, eligible, eligibleCount, searcher);
		} catch (FactoryException | TransformException | IOException | RuntimeException e) {
			// Pruning is optional. Unavailable topology or counting leaves the complete candidate query intact.
			LOGGER.debug("Keeping all disjoint candidates because interior pruning is unavailable", e);
			return meeting;
		}
	}

	/**
	 * Certifies cells of the bound's envelope, larger cells before their quarters, so that the cells tried certify as
	 * much of the bound as they can. Every cell left uncertified, tried or not, is part of the remainder.
	 */
	private void certify() throws FactoryException, TransformException {
		Deque<Cell> open = new ArrayDeque<>();
		open.add(new Cell(bound.envelope(), MAX_DEPTH));
		// The remainder becomes the clauses of one query, which Lucene's clause limit also bounds.
		int maxRemainder = Math.min(MAX_REMAINDER, BooleanQuery.getMaxClauseCount() - 1);
		while (!open.isEmpty() && remainingCells > 0) {
			Cell cell = open.remove();
			remainingCells--;
			Envelope envelope = cell.envelope();
			if (bound.covers(envelope)) {
				certified = true;
				continue;
			}
			// Replacing a cell by four keeps the remainder within its limit only while there is room for three more.
			boolean roomToSplit = remainder.size() + open.size() + 4 <= maxRemainder;
			double x = envelope.getMinX(), maxX = envelope.getMaxX();
			double y = envelope.getMinY(), maxY = envelope.getMaxY();
			/*
			 * A bound whose sides are not parallel to the axes, such as a rectangle of another CRS, misses each cell
			 * along its boundary by a sliver. The cell drawn in by a sixteenth of its size on every side keeps most
			 * of its area and is often within the bound where the whole cell is not; the frame around it remains.
			 */
			Envelope inset = new Envelope(x + (maxX - x) / INSET_DIVISOR, maxX - (maxX - x) / INSET_DIVISOR,
					y + (maxY - y) / INSET_DIVISOR, maxY - (maxY - y) / INSET_DIVISOR);
			if (roomToSplit && bound.covers(inset)) {
				certified = true;
				remainder.add(new Envelope(x, inset.getMinX(), y, maxY));
				remainder.add(new Envelope(inset.getMaxX(), maxX, y, maxY));
				remainder.add(new Envelope(x, maxX, y, inset.getMinY()));
				remainder.add(new Envelope(x, maxX, inset.getMaxY(), maxY));
				continue;
			}
			double midX = x + (maxX - x) / 2;
			double midY = y + (maxY - y) / 2;
			if (!roomToSplit || cell.depth() == 0 || !(x < midX && midX < maxX && y < midY && midY < maxY)
					|| bound.isApartFrom(envelope)) {
				remainder.add(envelope);
				continue;
			}
			open.add(new Cell(new Envelope(x, midX, y, midY), cell.depth() - 1));
			open.add(new Cell(new Envelope(midX, maxX, y, midY), cell.depth() - 1));
			open.add(new Cell(new Envelope(x, midX, midY, maxY), cell.depth() - 1));
			open.add(new Cell(new Envelope(midX, maxX, midY, maxY), cell.depth() - 1));
		}
		for (Cell cell : open) {
			remainder.add(cell.envelope());
		}
	}

	private Query retainUncertain(Query meeting, Query eligible, int eligibleCount, IndexSearcher searcher)
			throws IOException {
		if (!certified) {
			return meeting;
		}
		Query uncertain = reachingTheRemainder(bound.envelope(), joined(remainder));
		// The remainder is worth a query of its own only when it leaves out most eligible sources.
		if (2L * searcher.count(new BooleanQuery.Builder()
				.add(eligible, BooleanClause.Occur.FILTER)
				.add(uncertain, BooleanClause.Occur.FILTER).build()) >= eligibleCount) {
			return meeting;
		}
		return new BooleanQuery.Builder()
				.add(meeting, BooleanClause.Occur.FILTER)
				.add(uncertain, BooleanClause.Occur.FILTER).build();
	}

	/**
	 * Joins cells that share a whole side into one rectangle, first along X and then along Y. Each rectangle covers
	 * exactly the cells it replaces, so a source meets it only where it meets one of them.
	 */
	static List<Envelope> joined(List<Envelope> cells) {
		return joinedAlong(joinedAlong(cells, true), false);
	}

	private static List<Envelope> joinedAlong(List<Envelope> cells, boolean alongX) {
		List<Envelope> sorted = new ArrayList<>(cells);
		sorted.sort(alongX ? ROWS : COLUMNS);
		List<Envelope> joined = new ArrayList<>();
		for (Envelope cell : sorted) {
			Envelope last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
			boolean sharesSide = last != null && (alongX
					? last.getMinY() == cell.getMinY() && last.getMaxY() == cell.getMaxY()
							&& last.getMaxX() == cell.getMinX()
					: last.getMinX() == cell.getMinX() && last.getMaxX() == cell.getMaxX()
							&& last.getMaxY() == cell.getMinY());
			if (sharesSide) {
				Envelope union = new Envelope(last);
				union.expandToInclude(cell);
				joined.set(joined.size() - 1, union);
			} else {
				joined.add(cell);
			}
		}
		return joined;
	}

	/** Sources whose envelopes reach beyond the bound's envelope or meet a cell of the remainder. */
	private static Query reachingTheRemainder(Envelope boundEnvelope, List<Envelope> remainder) {
		BooleanQuery.Builder reaching = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1)
				.add(LuceneGeoDocumentSchema.sourceEnvelopeBeyondQuery(boundEnvelope), BooleanClause.Occur.SHOULD);
		for (Envelope cell : remainder) {
			reaching.add(LuceneGeoDocumentSchema.sourceEnvelopeIntersectsQuery(cell), BooleanClause.Occur.SHOULD);
		}
		return reaching.build();
	}
}
