package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.JenaFunctionEvaluator;
import org.eclipse.rdf4j.model.IRI;
import org.locationtech.jts.geom.Dimension;

import java.util.Collection;
import java.util.List;

import static com.ontotext.trree.geosparql.vocabulary.GeoConstants.*;

/**
 * GeoSPARQL property relations exposed by GraphDB.
 *
 * <p>Non-disjoint relations use CRS84 index-envelope intersection for candidate lookup in both binding directions.
 * Disjoint relations partition source documents into envelope-proven matches and uncertain candidates. Mixed-CRS
 * pairs whose cleanup occurs in another CRS are retained for disjoint exact evaluation because CRS84 envelopes cannot
 * safely classify them. Jena exact evaluation always preserves subject/object argument order.
 */
public enum GeoSparqlPropertyRelation {
	// Simple Features
	SF_EQUALS(GEO_SF_EQUALS, GEOF_SF_EQUALS),
	SF_DISJOINT(GEO_SF_DISJOINT, GEOF_SF_DISJOINT, CandidateLookupPolicy.DISJOINT_PARTITIONED),
	SF_INTERSECTS(GEO_SF_INTERSECTS, GEOF_SF_INTERSECTS),
	SF_TOUCHES(GEO_SF_TOUCHES, GEOF_SF_TOUCHES),
	SF_WITHIN(GEO_SF_WITHIN, GEOF_SF_WITHIN),
	SF_CONTAINS(GEO_SF_CONTAINS, GEOF_SF_CONTAINS),
	SF_OVERLAPS(GEO_SF_OVERLAPS, GEOF_SF_OVERLAPS),
	SF_CROSSES(GEO_SF_CROSSES, GEOF_SF_CROSSES),

	// Egenhofer
	EH_EQUALS(GEO_EH_EQUALS, GEOF_EH_EQUALS),
	EH_DISJOINT(GEO_EH_DISJOINT, GEOF_EH_DISJOINT, CandidateLookupPolicy.DISJOINT_PARTITIONED),
	EH_MEET(GEO_EH_MEET, GEOF_EH_MEET),
	EH_OVERLAP(GEO_EH_OVERLAP, GEOF_EH_OVERLAP),
	EH_COVERS(GEO_EH_COVERS, GEOF_EH_COVERS),
	EH_COVERED_BY(GEO_EH_COVERED_BY, GEOF_EH_COVERED_BY),
	EH_INSIDE(GEO_EH_INSIDE, GEOF_EH_INSIDE),
	EH_CONTAINS(GEO_EH_CONTAINS, GEOF_EH_CONTAINS),

	// RCC8
	RCC8_EQ(GEO_RCC8_EQ, GEOF_RCC8_EQ),
	RCC8_DC(GEO_RCC8_DC, GEOF_RCC8_DC, CandidateLookupPolicy.DISJOINT_PARTITIONED),
	RCC8_EC(GEO_RCC8_EC, GEOF_RCC8_EC),
	RCC8_PO(GEO_RCC8_PO, GEOF_RCC8_PO),
	RCC8_TPPI(GEO_RCC8_TPPI, GEOF_RCC8_TPPI),
	RCC8_TPP(GEO_RCC8_TPP, GEOF_RCC8_TPP),
	RCC8_NTPP(GEO_RCC8_NTPP, GEOF_RCC8_NTPP),
	RCC8_NTPPI(GEO_RCC8_NTPPI, GEOF_RCC8_NTPPI);

	private final IRI predicateUri;
	private final IRI filterFunctionUri;
	private final CandidateLookupPolicy candidateLookupPolicy;

	GeoSparqlPropertyRelation(IRI predicateUri, IRI filterFunctionUri) {
		this(predicateUri, filterFunctionUri, CandidateLookupPolicy.ENVELOPE_INTERSECTS);
	}

	GeoSparqlPropertyRelation(IRI predicateUri, IRI filterFunctionUri,
							   CandidateLookupPolicy candidateLookupPolicy) {
		this.predicateUri = predicateUri;
		this.filterFunctionUri = filterFunctionUri;
		this.candidateLookupPolicy = candidateLookupPolicy;
	}

	public IRI getPredicateUri() {
		return predicateUri;
	}

	public CandidateLookupPolicy getCandidateLookupPolicy() {
		return candidateLookupPolicy;
	}

	/**
	 * Returns whether two separated, non-empty index envelopes prove this relation for the supplied source dimensions.
	 *
	 * <p>Simple Features and Egenhofer disjoint have no dimensional precondition. RCC8 relations apply only to
	 * area/area pairs, so envelope separation is a definite RCC8 disconnected match only for two area sources.
	 */
	boolean envelopeDisjointIsDefiniteMatch(int candidateTopologicalDimension,
			int boundTopologicalDimension) {
		if (candidateLookupPolicy != CandidateLookupPolicy.DISJOINT_PARTITIONED) {
			return false;
		}
		return this != RCC8_DC
				|| candidateTopologicalDimension == Dimension.A
				&& boundTopologicalDimension == Dimension.A;
	}

	/**
	 * Returns whether a non-empty bound source can participate in this relation's disjoint envelope partition.
	 *
	 * <p>RCC8 disconnected applies only to area/area pairs. A non-area bound source therefore cannot match any
	 * candidate and does not need either a definite or uncertain Lucene lookup.
	 */
	boolean boundSourceCanParticipateInDisjointPartition(int boundTopologicalDimension) {
		return candidateLookupPolicy == CandidateLookupPolicy.DISJOINT_PARTITIONED
				&& (this != RCC8_DC || boundTopologicalDimension == Dimension.A);
	}

	public boolean evaluate(SourceGeometryLiteral argument1, SourceGeometryLiteral argument2) {
		try {
			return JenaFunctionEvaluator.evaluateTopological(filterFunctionUri.stringValue(), argument1, argument2);
		} catch (JenaGeoSparqlException e) {
			throw e;
		} catch (Exception e) {
			throw new JenaGeoSparqlException("Unable to evaluate GeoSPARQL relation " + predicateUri, e);
		}
	}

	/**
	 * Returns whether any subject/object source pair satisfies this relation.
	 *
	 * <p>Property relations use existential entity semantics. An unevaluable source pair cannot establish the relation
	 * and does not prevent another source pair or entity from matching.
	 */
	public boolean evaluate(Collection<SourceGeometryLiteral> subjectGeometries,
			Collection<SourceGeometryLiteral> objectGeometries) {
		if (subjectGeometries.isEmpty() || objectGeometries.isEmpty()) {
			return false;
		}
		for (SourceGeometryLiteral subjectGeometry : subjectGeometries) {
			for (SourceGeometryLiteral objectGeometry : objectGeometries) {
				try {
					if (evaluate(subjectGeometry, objectGeometry)) {
						return true;
					}
				} catch (JenaGeoSparqlException ignored) {
					// This source pair cannot establish the property relation.
				}
			}
		}
		return false;
	}

	public boolean evaluate(Collection<SourceGeometryLiteral> subjectGeometries,
			SourceGeometryLiteral objectGeometry) {
		return evaluate(subjectGeometries, List.of(objectGeometry));
	}

	public boolean evaluate(SourceGeometryLiteral subjectGeometry,
			Collection<SourceGeometryLiteral> objectGeometries) {
		return evaluate(List.of(subjectGeometry), objectGeometries);
	}
}
