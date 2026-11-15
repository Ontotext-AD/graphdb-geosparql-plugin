package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.IndexSearcher;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Removes native CRS84 sources contained by rectangles that Jena certifies inside the bound polygon. */
final class NativeCrs84DisjointPruning {
	private static final Logger LOGGER = LoggerFactory.getLogger(NativeCrs84DisjointPruning.class);
	private static final int MAX_BOUND_POINTS = 256;
	private static final int MAX_DEPTH = 4;
	private static final int MAX_CELLS = 64;
	private static final int MAX_RECTANGLES = 16;

	private final GeometryWrapper bound;
	private final List<Envelope> rectangles = new ArrayList<>();
	private int remainingCells = MAX_CELLS;

	private NativeCrs84DisjointPruning(GeometryWrapper bound) {
		this.bound = bound;
	}

	static Query prune(IndexGeometry bound, Query candidates, IndexSearcher searcher) {
		if (bound.candidateBoundsKind() != CandidateBoundsKind.NATIVE_CRS84) {
			return candidates;
		}
		try {
			GeometryWrapper wrapper = bound.sourceGeometryLiteral().asGeometryWrapper();
			if (!(wrapper.getXYGeometry() instanceof Polygon polygon)
					|| polygon.isEmpty() || polygon.getNumPoints() > MAX_BOUND_POINTS || !polygon.isValid()) {
				return candidates;
			}
			NativeCrs84DisjointPruning cover = new NativeCrs84DisjointPruning(wrapper);
			cover.certify(bound.indexEnvelope(), MAX_DEPTH);
			return cover.retainUncertain(candidates, searcher);
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
		double x = cell.getMinX(), maxX = cell.getMaxX();
		double y = cell.getMinY(), maxY = cell.getMaxY();
		SourceGeometryLiteral rectangle = SourceGeometryLiteral.fromWkt("POLYGON(("
				+ x + " " + y + "," + maxX + " " + y + "," + maxX + " " + maxY + ","
				+ x + " " + maxY + "," + x + " " + y + "))");
		IndexGeometry indexRectangle = IndexGeometry.fromSourceGeometryLiteral(rectangle);
		if (indexRectangle.isEnvelopeCoveringRectangle()
				&& rectangle.asGeometryWrapper().within(bound)) {
			// Use the certified geometry's parsed coordinates for the numeric containment query.
			rectangles.add(indexRectangle.indexEnvelope());
			return;
		}
		if (depth == 0 || rectangle.asGeometryWrapper().disjoint(bound)) {
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

	private Query retainUncertain(Query candidates, IndexSearcher searcher) throws IOException {
		if (rectangles.isEmpty()) {
			return candidates;
		}
		Query nativeSources = LuceneGeoDocumentSchema.candidateBoundsKindQuery(CandidateBoundsKind.NATIVE_CRS84);
		BooleanQuery.Builder contained = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
		/*
		 * For a non-empty native CRS84 source S, S is contained by its envelope E. If E lies inside a certified
		 * rectangle R and Jena establishes R within the bound B, then S is contained by B and cannot be disjoint.
		 * Closed containment includes touching boundaries. Transformed envelopes do not participate in this proof.
		 */
		for (Envelope rectangle : rectangles) {
			contained.add(LuceneGeoDocumentSchema.envelopeWithinQuery(rectangle), BooleanClause.Occur.SHOULD);
		}
		Query containedNativeSources = new BooleanQuery.Builder()
				.add(nativeSources, BooleanClause.Occur.FILTER)
				.add(contained.build(), BooleanClause.Occur.FILTER).build();
		Query eligibleCandidates = new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(nativeSources, BooleanClause.Occur.FILTER).build();
		int eligibleCount = searcher.count(eligibleCandidates);
		if (eligibleCount == 0) {
			return candidates;
		}
		Query excludedCandidates = new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(containedNativeSources, BooleanClause.Occur.FILTER).build();
		// Avoid repeated containment filtering unless it removes most eligible native source documents.
		if (2L * searcher.count(excludedCandidates) <= eligibleCount) {
			return candidates;
		}
		return new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(containedNativeSources, BooleanClause.Occur.MUST_NOT).build();
	}
}
