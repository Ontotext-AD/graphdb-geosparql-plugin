package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.BoundEvaluationEnvelope;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Looks one bound source up in the CRSs where Jena evaluates it against the sources of a reader.
 *
 * <p>Jena evaluates a pair in the CRS of its left operand, on that operand's own coordinates and on the right
 * operand transformed into that CRS. For the sources of one CRS this lookup therefore compares each source's own
 * envelope with the bound's envelope as Jena places it there. Both are exact for the two geometries of the
 * evaluation: envelopes that are apart prove the pair disjoint, and no other relation holds without envelopes that
 * meet. A source may also carry boxes that together contain it; a bound whose envelope meets none of them shares no
 * point with the source.
 *
 * <p>The bound is placed in every source CRS of the reader that it shares, or in which every source is the left
 * operand. A CRS in which Jena cannot place the bound relates none of its sources. Sources of every other CRS are
 * left to lookup by CRS84 index envelopes, which {@link #orElsewhere} restricts to them.
 *
 * <p>A lookup has one clause for each CRS it places the bound in, and Lucene limits the clauses of one query. In a
 * reader with more source CRSs than that limit allows, the sources of the remaining CRSs keep CRS84 lookup.
 */
final class EvaluationCrsLookup {
	private final Map<String, BoundEvaluationEnvelope> placements = new LinkedHashMap<>();
	private boolean leavesSourcesElsewhere;

	private EvaluationCrsLookup() {
	}

	static EvaluationCrsLookup of(IndexReader reader, IndexGeometry bound, boolean candidateIsSubject)
			throws IOException {
		EvaluationCrsLookup lookup = new EvaluationCrsLookup();
		// The bound is always placed among the sources of its own CRS, so a CRS84 lookup never selects those.
		String boundCrsUri = bound.sourceGeometryLiteral().effectiveCrsUri();
		lookup.placements.put(boundCrsUri, bound.evaluationEnvelope(boundCrsUri, candidateIsSubject).orElseThrow());
		for (String sourceCrsUri : LuceneGeoDocumentSchema.sourceCrsUris(reader)) {
			if (lookup.placements.containsKey(sourceCrsUri)) {
				continue;
			}
			// One clause is kept for the CRS84 lookup that selects the sources placed nowhere.
			boolean hasClauseLeft = lookup.placements.size() < BooleanQuery.getMaxClauseCount() - 1;
			Optional<BoundEvaluationEnvelope> placement = hasClauseLeft
					? bound.evaluationEnvelope(sourceCrsUri, candidateIsSubject) : Optional.empty();
			if (placement.isPresent()) {
				lookup.placements.put(sourceCrsUri, placement.get());
			} else {
				lookup.leavesSourcesElsewhere = true;
			}
		}
		return lookup;
	}

	/** Sources that may share a point with the bound. No other source is related to it except as disjoint. */
	Query intersecting() {
		return inEachCrs((sourceCrsUri, bound) -> mayMeet(bound));
	}

	/**
	 * Sources of one CRS that may share a point with the bound there: their envelopes meet the bound's, and where
	 * a source carries cover boxes, one of them does. Every lookup takes its sources from this one condition, so
	 * the sources that need exact evaluation and the sources that are apart never overlap and leave none out.
	 */
	private static Query mayMeet(BoundEvaluationEnvelope bound) {
		return new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.sourceEnvelopeIntersectsQuery(bound.envelope()), BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.sourceCoverApartQuery(bound.envelope()), BooleanClause.Occur.MUST_NOT)
				.build();
	}

	/** Sources whose envelopes equal the bound's, which geometries that are equal share. */
	Query withEqualEnvelope() {
		return inEachCrs((sourceCrsUri, bound) ->
				LuceneGeoDocumentSchema.sourceEnvelopeEqualsQuery(bound.envelope()));
	}

	/** Sources that share no point with the bound: each is disjoint from it. */
	Query apart() {
		return inEachCrs((sourceCrsUri, bound) -> new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.hasEnvelopeQuery(true), BooleanClause.Occur.FILTER)
				.add(mayMeet(bound), BooleanClause.Occur.MUST_NOT).build());
	}

	/**
	 * Sources that may share a point with the bound and that the bound is not known to cover. Where the bound
	 * fills its envelope, a source whose envelope lies inside it meets the bound and is not disjoint from it.
	 */
	Query possiblyDisjoint() {
		return inEachCrs((sourceCrsUri, bound) -> {
			Query meeting = mayMeet(bound);
			return !bound.fillsEnvelope() ? meeting : new BooleanQuery.Builder()
					.add(meeting, BooleanClause.Occur.FILTER)
					.add(LuceneGeoDocumentSchema.sourceEnvelopeWithinQuery(bound.envelope()),
							BooleanClause.Occur.MUST_NOT).build();
		});
	}

	/**
	 * Removes candidates that lie inside rectangles which Jena certifies within the bound, in each CRS where the
	 * bound is a polygon that does not fill its envelope.
	 */
	Query withoutSourcesInsideTheBound(Query possiblyDisjoint, IndexSearcher searcher) {
		Query remaining = possiblyDisjoint;
		for (Map.Entry<String, BoundEvaluationEnvelope> placement : placements.entrySet()) {
			if (placement.getValue().isRelated() && !placement.getValue().fillsEnvelope()) {
				remaining = InteriorRectanglePruning.prune(placement.getValue(), remaining,
						LuceneGeoDocumentSchema.sourceCrsQuery(placement.getKey()),
						LuceneGeoDocumentSchema::sourceEnvelopeWithinQuery, searcher);
			}
		}
		return remaining;
	}

	/**
	 * Adds the sources this lookup does not place the bound for, selected by a lookup on CRS84 index envelopes.
	 * That lookup is built only when the reader holds such a source.
	 */
	Query orElsewhere(Query placed, Crs84Lookup crs84Lookup) throws IOException {
		if (!leavesSourcesElsewhere) {
			return placed;
		}
		return new BooleanQuery.Builder()
				.add(placed, BooleanClause.Occur.SHOULD)
				.add(elsewhere(crs84Lookup.query()), BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1).build();
	}

	/** Restricts a lookup on CRS84 index envelopes to the sources this lookup does not place the bound for. */
	Query elsewhere(Query crs84Lookup) {
		if (!leavesSourcesElsewhere) {
			return new MatchNoDocsQuery("The bound is placed in every source CRS of the reader.");
		}
		BooleanQuery.Builder elsewhere = new BooleanQuery.Builder().add(crs84Lookup, BooleanClause.Occur.FILTER);
		for (String sourceCrsUri : placements.keySet()) {
			elsewhere.add(LuceneGeoDocumentSchema.sourceCrsQuery(sourceCrsUri), BooleanClause.Occur.MUST_NOT);
		}
		return elsewhere.build();
	}

	private Query inEachCrs(InCrs condition) {
		BooleanQuery.Builder sources = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
		boolean related = false;
		for (Map.Entry<String, BoundEvaluationEnvelope> placement : placements.entrySet()) {
			if (!placement.getValue().isRelated()) {
				continue;
			}
			related = true;
			sources.add(new BooleanQuery.Builder()
					.add(LuceneGeoDocumentSchema.sourceCrsQuery(placement.getKey()), BooleanClause.Occur.FILTER)
					.add(condition.query(placement.getKey(), placement.getValue()), BooleanClause.Occur.FILTER)
					.build(), BooleanClause.Occur.SHOULD);
		}
		return related ? sources.build() : new MatchNoDocsQuery("Jena relates no source to the bound.");
	}

	@FunctionalInterface
	private interface InCrs {
		Query query(String sourceCrsUri, BoundEvaluationEnvelope bound);
	}

	/** Builds a lookup on CRS84 index envelopes when the reader needs one. */
	@FunctionalInterface
	interface Crs84Lookup {
		Query query() throws IOException;
	}
}
