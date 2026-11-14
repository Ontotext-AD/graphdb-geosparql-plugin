package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;

import java.util.ArrayList;
import java.util.List;

/** Removes native CRS84 sources contained by rectangles that Jena certifies inside the bound polygon. */
final class NativeCrs84DisjointPruning {
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

	static Query prune(IndexGeometry bound, Query candidates) {
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
			return cover.retainUncertain(candidates);
		} catch (FactoryException | TransformException | RuntimeException e) {
			// Certification is optional. Unavailable topology leaves the complete candidate query intact.
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

	private Query retainUncertain(Query candidates) {
		if (rectangles.isEmpty()) {
			return candidates;
		}
		Query nativeSources = LuceneGeoDocumentSchema.candidateBoundsKindQuery(CandidateBoundsKind.NATIVE_CRS84);
		BooleanQuery.Builder nativeRemainder = new BooleanQuery.Builder()
				.add(nativeSources, BooleanClause.Occur.FILTER);
		/*
		 * For a non-empty native CRS84 source S, S is contained by its envelope E. If E lies inside a certified
		 * rectangle R and Jena establishes R within the bound B, then S is contained by B and cannot be disjoint.
		 * Closed containment includes touching boundaries. Transformed envelopes do not participate in this proof.
		 */
		for (Envelope rectangle : rectangles) {
			nativeRemainder.add(LuceneGeoDocumentSchema.envelopeNotWithinQuery(rectangle), BooleanClause.Occur.FILTER);
		}
		Query nonNative = new BooleanQuery.Builder()
				.add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
				.add(nativeSources, BooleanClause.Occur.MUST_NOT).build();
		Query remainder = new BooleanQuery.Builder()
				.add(nativeRemainder.build(), BooleanClause.Occur.SHOULD)
				.add(nonNative, BooleanClause.Occur.SHOULD).setMinimumNumberShouldMatch(1).build();
		return new BooleanQuery.Builder()
				.add(candidates, BooleanClause.Occur.FILTER)
				.add(remainder, BooleanClause.Occur.FILTER).build();
	}
}
