package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.geom.Dimension;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GeoSparqlPropertyRelationTest {
	private static final String UTM_32N = "http://www.opengis.net/def/crs/EPSG/0/32632";
	private static final String MGA56 = "http://www.opengis.net/def/crs/EPSG/0/7856";
	private static final String EPSG_4979 = "http://www.opengis.net/def/crs/EPSG/0/4979";
	private static final int[] NON_EMPTY_TOPOLOGICAL_DIMENSIONS = {
			Dimension.P, Dimension.L, Dimension.A
	};
	private static final Set<GeoSparqlPropertyRelation> PARTITIONED_DISJOINT_RELATIONS = Set.of(
			GeoSparqlPropertyRelation.SF_DISJOINT,
			GeoSparqlPropertyRelation.EH_DISJOINT,
			GeoSparqlPropertyRelation.RCC8_DC);

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void disjointRelationsUsePartitionedLookupAndAllOthersUseEnvelopeIntersection() {
		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			CandidateLookupPolicy expected = PARTITIONED_DISJOINT_RELATIONS.contains(relation)
					? CandidateLookupPolicy.DISJOINT_PARTITIONED
					: CandidateLookupPolicy.ENVELOPE_INTERSECTS;
			assertEquals(relation + " candidate policy", expected, relation.getCandidateLookupPolicy());
		}
	}

	@Test
	public void envelopeIntersectionPolicyHasNoDisjointPartitionCapability() {
		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			if (relation.getCandidateLookupPolicy() != CandidateLookupPolicy.ENVELOPE_INTERSECTS) {
				continue;
			}
			for (int candidateDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
				for (int boundDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
					assertFalse(relation + " definite envelope capability",
							relation.envelopeDisjointIsDefiniteMatch(candidateDimension, boundDimension));
				}
			}
			for (int boundDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
				assertFalse(relation + " bound envelope capability",
						relation.boundSourceCanParticipateInDisjointPartition(boundDimension));
			}
		}
	}

	@Test
	public void sfAndEhAcceptEveryNonEmptyDimensionWhileRcc8RequiresTwoAreas() {
		for (int candidateDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
			for (int boundDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
				assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT
						.envelopeDisjointIsDefiniteMatch(candidateDimension, boundDimension));
				assertTrue(GeoSparqlPropertyRelation.EH_DISJOINT
						.envelopeDisjointIsDefiniteMatch(candidateDimension, boundDimension));
				assertEquals(candidateDimension == Dimension.A && boundDimension == Dimension.A,
						GeoSparqlPropertyRelation.RCC8_DC
								.envelopeDisjointIsDefiniteMatch(candidateDimension, boundDimension));
			}
		}
		for (int boundDimension : NON_EMPTY_TOPOLOGICAL_DIMENSIONS) {
			assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT
					.boundSourceCanParticipateInDisjointPartition(boundDimension));
			assertTrue(GeoSparqlPropertyRelation.EH_DISJOINT
					.boundSourceCanParticipateInDisjointPartition(boundDimension));
			assertEquals(boundDimension == Dimension.A,
					GeoSparqlPropertyRelation.RCC8_DC
							.boundSourceCanParticipateInDisjointPartition(boundDimension));
		}
	}

	@Test
	public void allUnevaluableSourcePairsDoNotEstablishThePropertyRelation() {
		SourceGeometryLiteral utm32n = source("<" + UTM_32N + "> POINT(500000 5200000)");
		SourceGeometryLiteral otherUtm32n = source("<" + UTM_32N + "> POINT(400000 5100000)");
		SourceGeometryLiteral epsg4979 = source("<" + EPSG_4979 + "> POINT Z(-27.47 153.03 55)");

		assertThrows(JenaGeoSparqlException.class,
				() -> GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(utm32n, epsg4979));
		assertThrows(JenaGeoSparqlException.class,
				() -> GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(otherUtm32n, epsg4979));
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				List.of(utm32n, otherUtm32n), List.of(epsg4979)));
	}

	@Test
	public void unevaluablePairThenFalsePairDoesNotHold() {
		SourceGeometryLiteral utm32n = source("<" + UTM_32N + "> POINT(500000 5200000)");
		SourceGeometryLiteral mga56 = source("<" + MGA56 + "> POINT(502890 6959800)");
		SourceGeometryLiteral epsg4979 = source("<" + EPSG_4979 + "> POINT Z(-27.47 153.03 55)");

		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(utm32n, epsg4979);
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (JenaGeoSparqlException expected) {
			// Jena cannot reduce EPSG:4979 to this projected CRS for this pair.
		}
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(mga56, epsg4979));
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(List.of(utm32n, mga56), List.of(epsg4979)));
	}

	private static SourceGeometryLiteral source(String wkt) {
		return SourceGeometryLiteral.fromWkt(wkt);
	}
}
