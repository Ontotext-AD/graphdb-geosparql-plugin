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
import java.util.List;
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
 * <p>The bound is placed in every CRS of the reader's live spatial sources that it shares, or in which every source
 * is the left operand. It always has a placement in its own CRS, which adds a clause only where the reader holds
 * sources of that CRS. A CRS in which Jena cannot place the bound relates none of its sources. Sources of every other
 * CRS are left to lookup by CRS84 index envelopes, which {@link #orElsewhere} restricts to them.
 *
 * <p>A lookup has one clause for each CRS it places the bound in, and Lucene limits the clauses of one query. In a
 * reader with more source CRSs than that limit allows, the remaining CRSs take no placement. Only subjects take a
 * placement outside the bound's CRS, so the sources of those CRSs are subjects that Jena evaluates against the bound
 * transformed there, and CRS84 index envelopes do not show where its edges run. {@link #orRelatableElsewhere} leaves
 * them to exact evaluation, as disjoint lookup does for every subject placed nowhere.
 */
final class EvaluationCrsLookup {
	private final Map<String, BoundEvaluationEnvelope> placements = new LinkedHashMap<>();
	private String boundCrsUri;
	private boolean boundCrsHasSources;
	private boolean leavesSourcesElsewhere;
	private boolean leavesCrssPastTheClauseLimit;
	private boolean hasSourceCovers;

	private EvaluationCrsLookup() {
	}

	static EvaluationCrsLookup of(IndexReader reader, IndexGeometry bound, boolean candidateIsSubject)
			throws IOException {
		EvaluationCrsLookup lookup = new EvaluationCrsLookup();
		lookup.hasSourceCovers = LuceneGeoDocumentSchema.hasSourceCovers(reader);
		// The bound is always placed among the sources of its own CRS, so a CRS84 lookup never selects those.
		String boundCrsUri = bound.sourceGeometryLiteral().effectiveCrsUri();
		List<String> sourceCrsUris = LuceneGeoDocumentSchema.sourceCrsUris(reader);
		lookup.boundCrsUri = boundCrsUri;
		lookup.boundCrsHasSources = sourceCrsUris.contains(boundCrsUri);
		lookup.placements.put(boundCrsUri, bound.evaluationEnvelope(boundCrsUri, candidateIsSubject).orElseThrow());
		for (String sourceCrsUri : sourceCrsUris) {
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
				lookup.leavesCrssPastTheClauseLimit |= !hasClauseLeft;
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
	private Query mayMeet(BoundEvaluationEnvelope bound) {
		Query meeting = LuceneGeoDocumentSchema.sourceEnvelopeIntersectsQuery(bound.envelope());
		/*
		 * A source apart from the bound by its cover boxes becomes a definite disjoint match without exact
		 * evaluation, although the two envelopes meet. Jena may relate such a pair otherwise when the bound is
		 * invalid or has coordinates near 10⁻¹⁶², so cover boxes decide nothing for a bound that is not provable.
		 */
		if (!hasSourceCovers || !bound.isProvable()) {
			return meeting;
		}
		return new BooleanQuery.Builder()
				.add(meeting, BooleanClause.Occur.FILTER)
				.add(LuceneGeoDocumentSchema.sourceCoverApartQuery(bound.envelope()), BooleanClause.Occur.MUST_NOT)
				.build();
	}

	/**
	 * Sources that may equal the bound. Equal point sets have the same least and greatest X and Y, and the envelope
	 * of a valid geometry is exactly those of its points, so two valid geometries that are equal have equal
	 * envelopes. Jena's equality can disagree with the point sets in two ways: it leaves out a part of an invalid
	 * geometry, such as a line whose ends coincide, though the part still widens the envelope, and it finds a
	 * slightly bent line straight at coordinates near 10⁻¹⁶². A source that is not
	 * {@link IndexGeometry#isProvable provable}, and every source against such a bound, is therefore kept wherever
	 * the envelopes meet.
	 */
	Query withEqualEnvelope() {
		return inEachCrs((sourceCrsUri, bound) -> {
			Query meeting = mayMeet(bound);
			if (!bound.isProvable()) {
				return meeting;
			}
			return new BooleanQuery.Builder()
					.add(LuceneGeoDocumentSchema.sourceEnvelopeEqualsQuery(bound.envelope()), BooleanClause.Occur.SHOULD)
					.add(new BooleanQuery.Builder()
							.add(meeting, BooleanClause.Occur.FILTER)
							.add(LuceneGeoDocumentSchema.unprovableSourceQuery(), BooleanClause.Occur.FILTER)
							.build(), BooleanClause.Occur.SHOULD)
					.setMinimumNumberShouldMatch(1)
					.build();
		});
	}

	/**
	 * Whether the bound is a CRS84 polygon that fills its envelope, which lookup by CRS84 index envelopes uses to
	 * show a source inside the bound. The placement in the bound's own CRS makes and keeps this check.
	 */
	boolean boundIsCrs84AndFillsItsEnvelope() {
		return IndexGeometry.INDEX_CRS.equals(boundCrsUri) && placements.get(boundCrsUri).fillsEnvelope();
	}

	/** Sources that share no point with the bound: each is disjoint from it. */
	Query apart() {
		return inEachCrs((sourceCrsUri, bound) -> new BooleanQuery.Builder()
				.add(LuceneGeoDocumentSchema.hasEnvelopeQuery(true), BooleanClause.Occur.FILTER)
				.add(mayMeet(bound), BooleanClause.Occur.MUST_NOT).build());
	}

	/**
	 * Sources that may share a point with the bound and that the bound is not known to cover. Where the bound
	 * fills its envelope, a source whose envelope lies inside it meets the bound and is not disjoint from it, so
	 * only sources reaching beyond the envelope remain. Where it does not, {@link InteriorRectanglePruning} keeps the
	 * sources that reach a part of the envelope which Jena does not certify within the bound.
	 */
	Query possiblyDisjoint(IndexSearcher searcher) {
		return inEachCrs((sourceCrsUri, bound) -> {
			Query meeting = mayMeet(bound);
			if (!bound.fillsEnvelope()) {
				return InteriorRectanglePruning.prune(bound, meeting,
						LuceneGeoDocumentSchema.sourceCrsQuery(sourceCrsUri), searcher);
			}
			return new BooleanQuery.Builder()
					.add(meeting, BooleanClause.Occur.FILTER)
					.add(LuceneGeoDocumentSchema.sourceEnvelopeCrossingQuery(bound.envelope()),
							BooleanClause.Occur.FILTER).build();
		});
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

	/**
	 * Adds the sources this lookup does not place the bound for, as candidates for a relation other than a disjoint
	 * one. Where some CRSs are past the clause limit, those sources are subjects that Jena evaluates against the
	 * bound transformed into their CRS. Its edges there can run far outside its CRS84 index envelope and the prefix
	 * cells of that envelope, so every such source is left to exact evaluation. Otherwise the CRS84 lookup selects
	 * them.
	 */
	Query orRelatableElsewhere(Query placed, Crs84Lookup crs84Lookup) throws IOException {
		return orElsewhere(placed, leavesCrssPastTheClauseLimit
				? () -> LuceneGeoDocumentSchema.hasEnvelopeQuery(true) : crs84Lookup);
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
			// A CRS without sources selects none, and its checks of the bound would be wasted.
			if (!placement.getValue().isRelated()
					|| placement.getKey().equals(boundCrsUri) && !boundCrsHasSources) {
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
