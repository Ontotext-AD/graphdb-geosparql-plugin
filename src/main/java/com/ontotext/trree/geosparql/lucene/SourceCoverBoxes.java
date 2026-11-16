package com.ontotext.trree.geosparql.lucene;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.operation.relateng.RelateNG;
import org.locationtech.jts.operation.relateng.RelatePredicate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Boxes that together contain a source geometry, in the coordinates of the geometry itself.
 *
 * <p>The envelope of a large geometry can cover far more than the geometry does. A bound that meets the envelope
 * and none of these boxes shares no point with the geometry, in any CRS: the boxes are built from the same
 * coordinates Jena evaluates the source on, without a transformation.
 *
 * <p>The boxes are cells of the envelope, quartered repeatedly. A cell is discarded only when an exact predicate
 * finds no point of the geometry in it, so every point of the geometry lies in a kept cell: the closed cells of one
 * level tile the envelope, and their shared edges are computed once. A cell is never shrunk to the part of the
 * geometry inside it, which would need computed intersection points. Every kept box contains a point of the
 * geometry.
 *
 * <p>The number of boxes follows the size of the geometry, because a box is worth storing for the cost of loading
 * and evaluating the geometry it spares. A geometry too small for enough boxes to follow its outline gets none and
 * is described by its envelope, as is a geometry that is invalid, since the ways Jena decides inside and outside
 * can disagree on one.
 */
final class SourceCoverBoxes {
	private static final int MIN_POINTS = 128;
	private static final int POINTS_PER_BOX = 4;
	private static final int MAX_BOXES = 256;
	private static final int MAX_DEPTH = 6;
	/** Boxes that cover this share of the envelope or more exclude too little to be worth storing. */
	private static final double USEFUL_SHARE = 0.9;

	private record Cell(Envelope envelope, int depth) {
	}

	private SourceCoverBoxes() {
	}

	/** Returns boxes whose union contains the geometry, or none when its envelope describes it well enough. */
	static List<Envelope> of(Geometry geometry) {
		int points = geometry.getNumPoints();
		Envelope envelope = geometry.getEnvelopeInternal();
		if (points < MIN_POINTS || !(envelope.getWidth() > 0 && envelope.getHeight() > 0) || !geometry.isValid()) {
			return List.of();
		}
		int budget = Math.min(MAX_BOXES, points / POINTS_PER_BOX);
		RelateNG prepared = RelateNG.prepare(geometry);
		GeometryFactory factory = geometry.getFactory();
		boolean hasArea = geometry.getDimension() == 2;
		List<Envelope> boxes = new ArrayList<>();
		// Larger cells are quartered before smaller ones, so the boxes allowed are spread over the whole geometry.
		Deque<Cell> open = new ArrayDeque<>();
		open.add(new Cell(envelope, MAX_DEPTH));
		while (!open.isEmpty()) {
			Cell cell = open.remove();
			Envelope whole = cell.envelope();
			double midX = whole.getMinX() + whole.getWidth() / 2;
			double midY = whole.getMinY() + whole.getHeight() / 2;
			boolean quartered = cell.depth() > 0 && boxes.size() + open.size() + 4 <= budget
					&& whole.getMinX() < midX && midX < whole.getMaxX()
					&& whole.getMinY() < midY && midY < whole.getMaxY();
			if (!quartered) {
				boxes.add(whole);
				continue;
			}
			for (Envelope quarter : List.of(
					new Envelope(whole.getMinX(), midX, whole.getMinY(), midY),
					new Envelope(midX, whole.getMaxX(), whole.getMinY(), midY),
					new Envelope(whole.getMinX(), midX, midY, whole.getMaxY()),
					new Envelope(midX, whole.getMaxX(), midY, whole.getMaxY()))) {
				Geometry rectangle = factory.toGeometry(quarter);
				if (!prepared.evaluate(rectangle, RelatePredicate.intersects())) {
					continue;
				}
				if (hasArea && prepared.evaluate(rectangle, RelatePredicate.covers())) {
					// The geometry fills this cell; quartering it would exclude nothing.
					boxes.add(quarter);
				} else {
					open.add(new Cell(quarter, cell.depth() - 1));
				}
			}
		}
		double covered = 0;
		for (Envelope box : boxes) {
			covered += box.getArea();
		}
		return boxes.size() < 2 || covered >= USEFUL_SHARE * envelope.getArea() ? List.of() : boxes;
	}
}
