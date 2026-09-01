package com.ontotext.trree.geosparql;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.sdk.Entities;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.configuration.GeoSPARQLOperations;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.sis.geometry.DirectPosition2D;
import org.apache.sis.geometry.Envelopes;
import org.apache.sis.geometry.GeneralDirectPosition;
import org.apache.sis.geometry.GeneralEnvelope;
import org.apache.sis.referencing.CRS;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Dimension;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.MathTransform;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Compares index-backed GeoSPARQL property relations with direct Jena/JTS evaluation.
 *
 * <p>The candidate invariant is {@code exact = true ⇒ index-backed = true} for every GeoSPARQL property relation
 * family. That is the contract the selective SIS candidate envelope must satisfy. Disjoint envelope proofs are also
 * checked against exact evaluation. Direct-operation cases construct the exact target from the precision-cleaned
 * geometry produced by Jena's source-to-target transformation, independently of candidate envelope projection.
 * Three-dimensional candidate envelopes and exact evaluation both use a direct source-to-CRS84 operation. These
 * differential cases provide regression evidence for the envelope assumption, not a mathematical proof. A GDA94 3D
 * to GDA2020 3D datum pair additionally checks that dropping ellipsoidal height in a horizontal-only operation does
 * not omit an exact-true match when the full 3D operation moves the coordinates.
 */
public class GeoSparqlCandidateEnvelopeDifferentialTest {
	private static final long PREDICATE = 200L;
	private static final String GDA2020 = "http://www.opengis.net/def/crs/EPSG/0/7844";
	private static final String MGA56 = "http://www.opengis.net/def/crs/EPSG/0/7856";
	private static final String MGA55 = "http://www.opengis.net/def/crs/EPSG/0/7855";
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String UTM_32N = "http://www.opengis.net/def/crs/EPSG/0/32632";
	private static final String UTM_33N = "http://www.opengis.net/def/crs/EPSG/0/32633";
	private static final String UTM_34N = "http://www.opengis.net/def/crs/EPSG/0/32634";
	private static final String WEB_MERCATOR = "http://www.opengis.net/def/crs/EPSG/0/3857";
	private static final String LAMBERT_93 = "http://www.opengis.net/def/crs/EPSG/0/2154";
	private static final String WGS84_3D = "http://www.opengis.net/def/crs/EPSG/0/4979";
	private static final String GDA2020_3D = "http://www.opengis.net/def/crs/EPSG/0/7843";
	private static final String GDA94_3D = "http://www.opengis.net/def/crs/EPSG/0/4939";
	private static final String NAD83_CSRS_3D = "http://www.opengis.net/def/crs/EPSG/0/4955";
	private static final double GDA94_LAT = -27.47;
	private static final double GDA94_LON = 153.03;
	private static final double HEIGHT_DEPENDENT_SHIFT_DEGREES = 1e-12;
	private static final Set<GeoSparqlPropertyRelation> DISJOINT_RELATIONS = EnumSet.of(
			GeoSparqlPropertyRelation.SF_DISJOINT,
			GeoSparqlPropertyRelation.EH_DISJOINT,
			GeoSparqlPropertyRelation.RCC8_DC);

	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Before
	public void initializeAdapter() {
		com.ontotext.trree.geosparql.jena.JenaGeometryAdapter.initialize();
	}

	@Test
	public void indexBackedRelationsAgreeWithEntityLevelReferenceAcrossFamiliesAndCrs()
			throws Exception {
		Fixture fixture = createFixture("candidate-envelope-differential", representativeGeometries());

		for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
			for (long boundEntityId : fixture.sources.keySet()) {
				assertIndexedEqualsReference(fixture, relation, 0, boundEntityId);
				assertIndexedEqualsReference(fixture, relation, boundEntityId, 0);
			}
		}
	}

	@Test
	public void definiteDisjointEnvelopeMatchesAreExactDisjoint() throws Exception {
		Fixture fixture = createFixture("definite-disjoint-proofs", representativeGeometries());
		for (GeoSparqlPropertyRelation relation : DISJOINT_RELATIONS) {
			for (Map.Entry<Long, List<IndexGeometry>> boundEntry : fixture.sources.entrySet()) {
				assertDefiniteMatchesAreExact(fixture, relation, boundEntry.getKey(), boundEntry.getValue());
			}
		}
	}

	@Test
	public void threeDimensionalGeographicCrsMatchesCrs84And4326ThroughTheIndex() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, geometries("<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)"));
		sources.put(2L, geometries("POINT(153.03 -27.47)"));
		sources.put(3L, geometries("<" + EPSG_4326 + "> POLYGON((-28 152,-27 152,-27 154,-28 154,-28 152))"));
		Fixture fixture = createFixture("wgs84-3d-crs-differential", sources);

		assertEquals(CandidateBoundsKind.TRANSFORMED, sources.get(1L).get(0).candidateBoundsKind());
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(0).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(0).sourceGeometryLiteral(),
				sources.get(3L).get(0).sourceGeometryLiteral()));
		// Bind the 3D literal as the relation subject so Jena evaluates in the 3D CRS. Reducing EPSG:4979
		// to CRS84 or EPSG:4326 produces a NaN ordinate that Jena precision cleanup rejects.
		assertEquals(Set.of(1L, 2L, 3L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0));
		assertEquals(Set.of(1L, 2L, 3L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0));
	}

	@Test
	public void gda2020ThreeDimensionalCrsMatchesGda2020ThroughTheIndex() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, geometries("<" + GDA2020_3D + "> POINT Z(-27.47 153.03 55)"));
		sources.put(2L, geometries(
				"<" + GDA2020 + "> POLYGON((-27.6 152.9,-27.3 152.9,-27.3 153.2,-27.6 153.2,-27.6 152.9))"));
		Fixture fixture = createFixture("gda2020-3d-crs-differential", sources);

		assertEquals(CandidateBoundsKind.TRANSFORMED, sources.get(1L).get(0).candidateBoundsKind());
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(0).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
		assertEquals(Set.of(1L, 2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0));
		assertEquals(Set.of(1L, 2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0));
	}

	@Test
	public void threeDimensionalSourceToCrs84CandidateBoundsCoverTheDirectOperation() throws Exception {
		for (DirectOperationComparison comparison : threeDimensionalDirectOperationComparisons()) {
			assertEquals(comparison.dump(), 3,
					comparison.directOperation.getMathTransform().getSourceDimensions());
			assertEquals(comparison.dump(), 2,
					comparison.directOperation.getMathTransform().getTargetDimensions());
			assertDirectCoincidentRelations(comparison);
			assertDirectOperationEnvelopeAndJenaGeometryAreCovered(comparison);
		}
	}

	@Test
	public void crs84SubjectAndThreeDimensionalObjectRemainCorrectThroughLucene() throws Exception {
		for (DirectOperationComparison comparison : threeDimensionalDirectOperationComparisons()) {
			for (IndexSettings setting : maximumPrecisionIndexSettings()) {
				assertDirectOperationPairThroughIndex(comparison, setting,
						"CRS84 subject / 3D object\n" + comparison.dump());
			}
		}
	}

	@Test
	public void sameCrsThreeDimensionalHeightDoesNotChangeTwoDimensionalCandidateTopology() throws Exception {
		SourceGeometryLiteral low = SourceGeometryLiteral.fromWkt(
				"<" + NAD83_CSRS_3D + "> POINT Z(49.25 -123.1 0)");
		SourceGeometryLiteral high = SourceGeometryLiteral.fromWkt(
				"<" + NAD83_CSRS_3D + "> POINT Z(49.25 -123.1 300000)");
		IndexGeometry lowIndex = IndexGeometry.fromSourceGeometryLiteral(low);
		IndexGeometry highIndex = IndexGeometry.fromSourceGeometryLiteral(high);

		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(low, high));
		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(low, high));
		assertFalse(GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(low, high));

		for (IndexSettings setting : maximumPrecisionIndexSettings()) {
			Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
			sources.put(1L, List.of(lowIndex));
			sources.put(2L, List.of(highIndex));
			String settingName = setting.prefixTree.name().toLowerCase(Locale.ROOT) + "-" + setting.precision;
			Fixture fixture = createFixture("same-crs-3d-height-" + settingName,
					sources, setting.prefixTree, setting.precision);
			String diagnostics = settingName + "\nlow=" + lowIndex.indexEnvelope()
					+ "\nhigh=" + highIndex.indexEnvelope();

			assertTrue(diagnostics,
					runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0).contains(2L));
			assertTrue(diagnostics,
					runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L).contains(1L));
			for (GeoSparqlPropertyRelation disjoint : List.of(
					GeoSparqlPropertyRelation.SF_DISJOINT,
					GeoSparqlPropertyRelation.EH_DISJOINT)) {
				assertFalse(diagnostics + "\n" + disjoint,
						runIndexed(fixture, disjoint, 1L, 0).contains(2L));
				assertFalse(diagnostics + "\n" + disjoint,
						runIndexed(fixture, disjoint, 0, 2L).contains(1L));
			}
		}
	}

	@Test
	public void directJenaOperationCrsMatrixRemainsIndexCandidates() throws Exception {
		List<DirectOperationComparison> comparisons = List.of(
				directOperationComparison("epsg4326-to-crs84", IndexGeometry.INDEX_CRS, EPSG_4326,
						"POINT(48.85 2.35)", true),
				directOperationComparison("utm32-to-utm33", UTM_33N, UTM_32N,
						"POINT(500000 5200000)", false),
				directOperationComparison("lambert93-to-web-mercator", WEB_MERCATOR, LAMBERT_93,
						"POINT(650000 6860000)", false),
				directOperationComparison("mga56-to-gda2020-area", GDA2020, MGA56,
						"POLYGON((502800 6959700,502800 6959900,503000 6959900,503000 6959700,502800 6959700))",
						true));

		for (DirectOperationComparison comparison : comparisons) {
			assertDirectCoincidentRelations(comparison);
			for (IndexSettings setting : maximumPrecisionIndexSettings()) {
				assertDirectOperationPairThroughIndex(comparison, setting, comparison.dump());
			}
		}
	}

	@Test
	public void jenaPrecisionCleanedTransformationDoesNotEscapeCandidateTraversal() throws Exception {
		SourceGeometryLiteral projected = SourceGeometryLiteral.fromWkt(
				"<" + UTM_32N + "> POINT(500000 5200000)");
		GeometryWrapper projectedWrapper = projected.asGeometryWrapper();
		GeometryWrapper jenaTransformed = projectedWrapper.transform(IndexGeometry.INDEX_CRS);
		Coordinate cleanedCoordinate = jenaTransformed.getXYGeometry().getCoordinate();
		SourceGeometryLiteral crs84AtCleanedCoordinate = SourceGeometryLiteral.fromWkt(
				"POINT(" + cleanedCoordinate.x + " " + cleanedCoordinate.y + ")");

		Coordinate parsingCoordinate = projectedWrapper.getParsingGeometry().getCoordinate();
		CoordinateReferenceSystem projectedCrs = CRS.getHorizontalComponent(projectedWrapper.getCRS());
		CoordinateOperation rawOperation = CRS.findOperation(
				projectedCrs, SRSRegistry.getCRS(IndexGeometry.INDEX_CRS), null);
		DirectPosition rawPosition = rawOperation.getMathTransform().transform(
				new DirectPosition2D(projectedCrs, parsingCoordinate.x, parsingCoordinate.y), null);
		double rawX = rawPosition.getOrdinate(0);
		double rawY = rawPosition.getOrdinate(1);
		String mismatch = "precision=" + GeoSPARQLConfig.DECIMAL_PLACES_PRECISION
				+ " raw=(" + rawX + ", " + rawY + ")"
				+ " cleaned=(" + cleanedCoordinate.x + ", " + cleanedCoordinate.y + ")";
		assertEquals(mismatch, GeoSPARQLOperations.cleanUpPrecision(rawX), cleanedCoordinate.x, 0.0);
		assertEquals(mismatch, GeoSPARQLOperations.cleanUpPrecision(rawY), cleanedCoordinate.y, 0.0);
		assertTrue(mismatch, rawX != cleanedCoordinate.x || rawY != cleanedCoordinate.y);
		assertTrue(mismatch,
				Math.abs(rawX - cleanedCoordinate.x) > Math.ulp(rawX)
						|| Math.abs(rawY - cleanedCoordinate.y) > Math.ulp(rawY));

		assertTrue(mismatch, GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				crs84AtCleanedCoordinate, projected));
		assertFalse(mismatch, GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				crs84AtCleanedCoordinate, projected));
		assertFalse(mismatch, GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
				crs84AtCleanedCoordinate, projected));
		assertFalse(mismatch, GeoSparqlPropertyRelation.RCC8_DC.evaluate(
				crs84AtCleanedCoordinate, projected));

		for (IndexSettings setting : maximumPrecisionIndexSettings()) {
			Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
			sources.put(1L, List.of(IndexGeometry.fromSourceGeometryLiteral(crs84AtCleanedCoordinate)));
			sources.put(2L, List.of(IndexGeometry.fromSourceGeometryLiteral(projected)));
			String settingName = setting.prefixTree.name().toLowerCase(Locale.ROOT) + "-" + setting.precision;
			Fixture fixture = createFixture("jena-cleanup-" + settingName,
					sources, setting.prefixTree, setting.precision);

			assertTrue(settingName + " subject-bound sfIntersects\n" + mismatch,
					runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 1L, 0).contains(2L));
			assertTrue(settingName + " object-bound sfIntersects\n" + mismatch,
					runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L).contains(1L));
			for (GeoSparqlPropertyRelation disjoint : DISJOINT_RELATIONS) {
				assertFalse(settingName + " subject-bound " + disjoint + "\n" + mismatch,
						runIndexed(fixture, disjoint, 1L, 0).contains(2L));
				assertFalse(settingName + " object-bound " + disjoint + "\n" + mismatch,
						runIndexed(fixture, disjoint, 0, 2L).contains(1L));
			}
		}
	}

	@Test
	public void precisionCleanedProjectedToProjectedPairUsesExactTraversal() throws Exception {
		SourceGeometryLiteral utm32 = SourceGeometryLiteral.fromWkt(
				"<" + UTM_32N + "> POINT(500000 5200000)");
		Coordinate cleanedUtm33 = utm32.asGeometryWrapper().transform(UTM_33N)
				.getXYGeometry().getCoordinate();
		SourceGeometryLiteral utm33AtCleanedCoordinate = SourceGeometryLiteral.fromWkt(
				"<" + UTM_33N + "> POINT(" + cleanedUtm33.x + " " + cleanedUtm33.y + ")");

		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(utm33AtCleanedCoordinate, utm32));
		for (GeoSparqlPropertyRelation disjoint : DISJOINT_RELATIONS) {
			assertFalse(disjoint.toString(), disjoint.evaluate(utm33AtCleanedCoordinate, utm32));
		}

		for (IndexSettings setting : maximumPrecisionIndexSettings()) {
			Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
			sources.put(1L, List.of(IndexGeometry.fromSourceGeometryLiteral(utm33AtCleanedCoordinate)));
			sources.put(2L, List.of(IndexGeometry.fromSourceGeometryLiteral(utm32)));
			String settingName = setting.prefixTree.name().toLowerCase(Locale.ROOT) + "-" + setting.precision;
			Fixture fixture = createFixture("projected-cleanup-" + settingName,
					sources, setting.prefixTree, setting.precision);
			assertTrue(settingName + " UTM 33N bound envelope must retain UTM 32N source",
					envelopeHits(fixture, sources.get(1L).get(0)).contains(2L));
			assertTrue(settingName + " UTM 32N bound envelope must retain UTM 33N source",
					envelopeHits(fixture, sources.get(2L).get(0)).contains(1L));

			for (GeoSparqlPropertyRelation relation : EnumSet.of(
					GeoSparqlPropertyRelation.SF_INTERSECTS,
					GeoSparqlPropertyRelation.SF_DISJOINT,
					GeoSparqlPropertyRelation.EH_DISJOINT,
					GeoSparqlPropertyRelation.RCC8_DC)) {
				assertEquals(settingName + " subject-bound " + relation,
						runReference(fixture, relation, 1L, 0), runIndexed(fixture, relation, 1L, 0));
				assertEquals(settingName + " object-bound " + relation,
						runReference(fixture, relation, 0, 2L), runIndexed(fixture, relation, 0, 2L));
			}
		}
	}

	/**
	 * GDA94 3D (EPSG:4939) to GDA2020 3D (EPSG:7843) is a geog3D coordinate-frame rotation whose horizontal
	 * output depends on ellipsoidal height. At 3000 m, reducing the source CRS to its horizontal component before
	 * the datum operation disagrees with exact evaluation. Candidate envelopes and supported Lucene configurations
	 * must retain the exact-true pair. Exact {@code sfIntersects} holds with GDA94 3D as the relation subject;
	 * {@code sfEquals} does not hold, and the reverse argument order does not.
	 */
	@Test
	public void heightDependentGda94ToGda2020DatumDoesNotOmitExactMatches() throws Exception {
		Gda94ToGda2020DatumOperations operations = Gda94ToGda2020DatumOperations.resolve();
		assertEquals("full GDA94 3D → GDA2020 3D operation must consume three dimensions",
				3, operations.full3d.getMathTransform().getSourceDimensions());
		assertEquals("full GDA94 3D → GDA2020 3D operation must produce three dimensions",
				3, operations.full3d.getMathTransform().getTargetDimensions());

		DatumComparison at0 = operations.compare(0);
		DatumComparison at3000 = operations.compare(3000);
		assertTrue("full 3D transform must move horizontal coordinates when only height changes\n" + at3000.dump(),
				at3000.fullLat != at0.fullLat || at3000.fullLon != at0.fullLon);
		assertTrue(at3000.dump(), at3000.hypotCrs84Degrees() > HEIGHT_DEPENDENT_SHIFT_DEGREES);

		for (DatumComparison comparison : List.of(at0, at3000)) {
			assertTrue(comparison.dump(), GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					comparison.sourceLiteral, comparison.targetLiteral));
			assertFalse(comparison.dump(), GeoSparqlPropertyRelation.SF_EQUALS.evaluate(
					comparison.sourceLiteral, comparison.targetLiteral));
			assertFalse(comparison.dump(), GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					comparison.targetLiteral, comparison.sourceLiteral));
			assertEquals(comparison.dump(), CandidateBoundsKind.TRANSFORMED,
					comparison.sourceIndex.candidateBoundsKind());
			assertEquals(comparison.dump(), CandidateBoundsKind.TRANSFORMED,
					comparison.targetIndex.candidateBoundsKind());
			assertTrue("exact-true pair must have intersecting candidate envelopes\n" + comparison.dump(),
					comparison.sourceEnvelope.intersects(comparison.targetEnvelope));

			for (IndexSettings setting : heightDependentIndexSettings()) {
				Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
				sources.put(1L, List.of(comparison.sourceIndex));
				sources.put(2L, List.of(comparison.targetIndex));
				String settingName = setting.prefixTree.name().toLowerCase(Locale.ROOT) + "-" + setting.precision;
				Fixture fixture = createFixture(
						"gda94-gda2020-3d-h" + (int) comparison.sourceHeight + "-" + settingName,
						sources, setting.prefixTree, setting.precision);
				assertTrue(settingName + "\n" + comparison.dump(),
						envelopeHits(fixture, comparison.sourceIndex).contains(2L));
				assertTrue(settingName + "\n" + comparison.dump(),
						envelopeHits(fixture, comparison.targetIndex).contains(1L));

				assertIndexedEqualsReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, comparison, settingName);
				for (GeoSparqlPropertyRelation relation : DISJOINT_RELATIONS) {
					assertIndexedEqualsReference(fixture, relation, comparison, settingName);
				}
			}
		}
	}

	@Test
	public void multiGeometryEntityMatchesWhenAnySourcePairHolds() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, geometries("POINT(-40 -10)", "POINT(5 5)"));
		sources.put(2L, geometries("POLYGON((0 0,0 10,10 10,10 0,0 0))"));
		sources.put(3L, geometries("POINT(20 20)"));
		Fixture fixture = createFixture("multi-geometry-entity-differential", sources);

		assertEquals(Set.of(1L, 2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
		assertEquals(Set.of(1L, 2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(0).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(1).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
	}

	@Test
	public void multiGeometryEntityMatchesWhenAnUnevaluablePairPrecedesATruePair() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, geometries(
				"<" + UTM_32N + "> POINT(500000 5200000)",
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)"));
		sources.put(2L, geometries("<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)"));
		Fixture fixture = createFixture("unevaluable-then-true-differential", sources);

		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					sources.get(1L).get(0).sourceGeometryLiteral(),
					sources.get(2L).get(0).sourceGeometryLiteral());
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (com.ontotext.trree.geosparql.jena.JenaGeoSparqlException expected) {
			// The first listed source pair is unevaluable; the later 3D pair holds.
		}
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(1).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
		assertEquals(Set.of(1L, 2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
		assertEquals(Set.of(1L, 2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
	}

	@Test
	public void objectBoundEntityEvaluatesCompleteSourceSetsAfterAnUnevaluablePair() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, List.of(worldFallbackGeometry(
				"<" + UTM_32N + "> POINT(500000 5200000)")));
		sources.put(2L, geometries(
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)",
				"<" + UTM_32N + "> POINT(500000 5200000)"));
		Fixture fixture = createFixture("complete-object-bound-source-set", sources);

		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					sources.get(1L).get(0).sourceGeometryLiteral(),
					sources.get(2L).get(0).sourceGeometryLiteral());
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (com.ontotext.trree.geosparql.jena.JenaGeoSparqlException expected) {
			// The first bound source pair is unevaluable; the later same-CRS pair holds.
		}
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				List.of(sources.get(1L).get(0).sourceGeometryLiteral()),
				sources.get(2L).stream().map(IndexGeometry::sourceGeometryLiteral).toList()));
		assertEquals(Set.of(1L, 2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
		assertEquals(Set.of(1L, 2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
	}

	@Test
	public void subjectBoundEntityEvaluatesCompleteSourceSetsAfterAnUnevaluablePair() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, List.of(worldFallbackGeometry(
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)")));
		sources.put(2L, geometries(
				"<" + UTM_32N + "> POINT(500000 5200000)",
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)"));
		Fixture fixture = createFixture("complete-subject-bound-source-set", sources);

		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					sources.get(2L).get(0).sourceGeometryLiteral(),
					sources.get(1L).get(0).sourceGeometryLiteral());
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (com.ontotext.trree.geosparql.jena.JenaGeoSparqlException expected) {
			// The first bound source pair is unevaluable; the later same-CRS pair holds.
		}
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(2L).stream().map(IndexGeometry::sourceGeometryLiteral).toList(),
				List.of(sources.get(1L).get(0).sourceGeometryLiteral())));
		assertEquals(Set.of(1L, 2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 2L, 0));
		assertEquals(Set.of(1L, 2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 2L, 0));
	}

	@Test
	public void evaluatedFalsePairPreventsALaterUnevaluablePairFromAbortingTraversal() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, List.of(worldFallbackGeometry(
				"<" + UTM_32N + "> POINT(500000 5200000)")));
		sources.put(2L, geometries(
				"<" + UTM_32N + "> POINT(400000 5100000)",
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)"));
		Fixture fixture = createFixture("false-before-unevaluable-source-pair", sources);

		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				sources.get(1L).get(0).sourceGeometryLiteral(),
				sources.get(2L).get(0).sourceGeometryLiteral()));
		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					sources.get(1L).get(0).sourceGeometryLiteral(),
					sources.get(2L).get(1).sourceGeometryLiteral());
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (com.ontotext.trree.geosparql.jena.JenaGeoSparqlException expected) {
			// The evaluated false pair determines the complete source-set result.
		}
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				List.of(sources.get(1L).get(0).sourceGeometryLiteral()),
				sources.get(2L).stream().map(IndexGeometry::sourceGeometryLiteral).toList()));
		assertEquals(Set.of(2L),
				runReference(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
		assertEquals(Set.of(2L),
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));
	}

	@Test
	public void unevaluableCandidatesDoNotMatchAcrossNonDisjointEnvelopeAndDisjointCleanupPhases() throws Exception {
		IndexGeometry utm32 = geometries(
				"<" + UTM_32N + "> POINT(-100000000 0)").get(0);
		// The UTM point projects to the 99°E transform-domain edge. The nearby 3D point remains inside its widened
		// candidate envelope, while exact evaluation cannot reduce either 3D point to UTM 32N.
		IndexGeometry nearbyEpsg4979 = geometries(
				"<" + WGS84_3D + "> POINT Z(0 99.0000001 55)").get(0);
		IndexGeometry distantEpsg4979 = geometries(
				"<" + WGS84_3D + "> POINT Z(-27.47 153.03 55)").get(0);

		assertThrows(JenaGeoSparqlException.class, () -> GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				utm32.sourceGeometryLiteral(), nearbyEpsg4979.sourceGeometryLiteral()));
		assertThrows(JenaGeoSparqlException.class, () -> GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				utm32.sourceGeometryLiteral(), distantEpsg4979.sourceGeometryLiteral()));
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				List.of(utm32.sourceGeometryLiteral()),
				List.of(nearbyEpsg4979.sourceGeometryLiteral())));
		assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				List.of(utm32.sourceGeometryLiteral()),
				List.of(distantEpsg4979.sourceGeometryLiteral())));
		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				List.of(utm32.sourceGeometryLiteral()),
				List.of(distantEpsg4979.sourceGeometryLiteral())));

		Fixture envelopeFixture = createFixture("unevaluable-envelope-candidate",
				Map.of(1L, List.of(utm32), 2L, List.of(nearbyEpsg4979)));
		assertTrue(envelopeHits(envelopeFixture, nearbyEpsg4979).contains(1L));
		assertEquals(Set.of(2L),
				runIndexed(envelopeFixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, 2L));

		Fixture cleanupFixture = createFixture("unevaluable-disjoint-cleanup-candidate",
				Map.of(1L, List.of(utm32), 2L, List.of(distantEpsg4979)));
		assertFalse(envelopeHits(cleanupFixture, distantEpsg4979).contains(1L));
		assertTrue(disjointCleanupHits(cleanupFixture, distantEpsg4979, true).contains(1L));
		assertEquals(Set.of(),
				runIndexed(cleanupFixture, GeoSparqlPropertyRelation.SF_DISJOINT, 0, 2L));
	}

	@Test
	public void envelopeContainedNonMatchesAreNotExactDisjoint() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(100L, geometries("POLYGON((0 0,0 10,10 10,10 0,0 0))"));
		sources.put(1L, geometries("POINT(5 5)"));
		sources.put(2L, geometries("POLYGON((2 2,2 4,4 4,4 2,2 2))"));
		sources.put(3L, geometries("POINT(20 20)"));
		Fixture fixture = createFixture("definite-non-match-proofs", sources);
		IndexGeometry bound = sources.get(100L).get(0);

		assertTrue(bound.isEnvelopeCoveringRectangle());
		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				bound.sourceGeometryLiteral(), sources.get(1L).get(0).sourceGeometryLiteral()));
		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				bound.sourceGeometryLiteral(), sources.get(2L).get(0).sourceGeometryLiteral()));
		assertEquals(Set.of(3L), runIndexed(fixture, GeoSparqlPropertyRelation.SF_DISJOINT, 0, 100L));
		assertEquals(Set.of(3L), runIndexed(fixture, GeoSparqlPropertyRelation.EH_DISJOINT, 0, 100L));
		assertEquals(Set.of(), runIndexed(fixture, GeoSparqlPropertyRelation.RCC8_DC, 0, 100L));
	}

	private void assertDefiniteMatchesAreExact(Fixture fixture, GeoSparqlPropertyRelation relation,
			long boundEntityId, List<IndexGeometry> boundGeometries) {
		try (RelationCandidateTraversal traversal = new RelationCandidateTraversal(
				fixture.plugin.indexer, relation, boundGeometries, LoggerFactory.getLogger(getClass()))) {
			while (traversal.hasNext()) {
				RelationCandidateTraversal.Candidate candidate = traversal.next();
				if (candidate.matchCertainty() != RelationCandidateTraversal.MatchCertainty.DEFINITE_MATCH) {
					continue;
				}
				List<SourceGeometryLiteral> candidateSources = fixture.sources.get(candidate.entityId())
						.stream()
						.map(IndexGeometry::sourceGeometryLiteral)
						.toList();
				List<SourceGeometryLiteral> boundSources = boundGeometries.stream()
						.map(IndexGeometry::sourceGeometryLiteral)
						.toList();
				Boolean exact = exactRelationOrUnknown(relation, candidateSources, boundSources);
				if (exact == null) {
					exact = exactRelationOrUnknown(relation, boundSources, candidateSources);
				}
				if (Boolean.FALSE.equals(exact)) {
					fail(relation + " definite match " + candidate.entityId() + " vs " + boundEntityId
							+ " is not exact disjoint");
				}
			}
		}
	}

	private Map<Long, List<IndexGeometry>> representativeGeometries() {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, geometries(
				"<" + GDA2020 + "> POLYGON((-27.6 152.9,-27.3 152.9,-27.3 153.2,-27.6 153.2,-27.6 152.9))"));
		sources.put(2L, geometries("POINT(153.03 -27.47)"));
		sources.put(3L, geometries("<" + MGA56 + "> POINT(502890 6959800)"));
		sources.put(4L, geometries("<" + MGA55 + "> POINT(500000 5800000)"));
		sources.put(5L, geometries("<" + EPSG_4326 + "> POLYGON((-28 152,-27 152,-27 154,-28 154,-28 152))"));
		sources.put(6L, geometries("<" + UTM_34N + "> LINESTRING(200000 7000000, 800000 7000000)"));
		sources.put(7L, geometries("<" + UTM_34N + "> POINT(500000 7000000)"));
		sources.put(8L, geometries("<" + UTM_32N + "> POINT(500000 5200000)"));
		sources.put(9L, geometries(
				"<" + WEB_MERCATOR + "> POLYGON((17000000 -3200000,17000000 -3100000,17100000 -3100000,17100000 -3200000,17000000 -3200000))"));
		sources.put(10L, geometries("<" + LAMBERT_93 + "> POINT(650000 6860000)"));
		sources.put(11L, geometries("POINT(-40 -10)"));
		sources.put(12L, geometries("POLYGON((0 0,0 10,10 10,10 0,0 0))"));
		sources.put(13L, geometries("POINT(5 5)"));
		sources.put(14L, geometries("POINT(20 20)"));
		sources.put(15L, geometries("GEOMETRYCOLLECTION(POINT(153.03 -27.47),LINESTRING(152.9 -27.6,153.2 -27.3))"));
		sources.put(16L, geometries("<" + MGA56 + "> POINT Z(502890 6959800 40)"));
		sources.put(17L, geometries("GEOMETRYCOLLECTION EMPTY"));
		sources.put(18L, geometries("POINT(-40 -10)", "<" + GDA2020 + "> POINT(-27.47 153.03)"));
		return sources;
	}

	private Fixture createFixture(String directoryName, Map<Long, List<IndexGeometry>> sources)
			throws Exception {
		return createFixture(directoryName, sources,
				GeoSparqlConfig.PREFIXTREE_DEFAULT, GeoSparqlConfig.PRECISION_DEFAULT);
	}

	private Fixture createFixture(String directoryName, Map<Long, List<IndexGeometry>> sources,
			GeoSparqlConfig.PrefixTree prefixTree, int precision) throws Exception {
		Path dataDir = tmpFolder.getRoot().toPath().resolve(directoryName);
		Files.createDirectories(dataDir);

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setPrefixTree(prefixTree);
		config.setPrecision(precision);
		config.updateCurrentSettings();
		plugin.setConfig(config);
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlCandidateEnvelopeDifferentialTest.class));
		plugin.setDataDir(dataDir.toFile());

		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		indexer.begin();
		for (Map.Entry<Long, List<IndexGeometry>> entry : sources.entrySet()) {
			indexer.indexGeometryList(entry.getKey(), id -> "entity-" + id, entry.getValue());
		}
		indexer.commit();
		indexer.complete();

		FakeEntities entities = new FakeEntities();
		for (Long entityId : sources.keySet()) {
			entities.add(entityId, SimpleValueFactory.getInstance()
					.createIRI("http://example.com/entity/" + entityId));
		}
		return new Fixture(plugin, entities, Map.copyOf(sources));
	}

	private Set<Long> runIndexed(Fixture fixture, GeoSparqlPropertyRelation relation,
			long subject, long object) {
		List<Long> rows = collectCandidateIds(new GeoSparqlRelationIterator(fixture.plugin, relation,
				subject, PREDICATE, object, fixture.entities), subject);
		return new LinkedHashSet<>(rows);
	}

	private void assertIndexedEqualsReference(Fixture fixture, GeoSparqlPropertyRelation relation,
			DatumComparison comparison, String settingName) throws Exception {
		String message = settingName + " " + relation + "\n" + comparison.dump();
		try {
			assertEquals(message,
					runReference(fixture, relation, 1L, 0),
					runIndexed(fixture, relation, 1L, 0));
		} catch (RuntimeException e) {
			throw new AssertionError(message, e);
		}
	}

	private void assertIndexedEqualsReference(
			Fixture fixture, GeoSparqlPropertyRelation relation, long subject, long object) throws Exception {
		String direction = subject == 0 ? "object-bound" : "subject-bound";
		long boundEntityId = subject == 0 ? object : subject;
		String message = relation + " " + direction + " entity " + boundEntityId;
		assertEquals(message,
				runReference(fixture, relation, subject, object),
				runIndexed(fixture, relation, subject, object));
	}

	private Set<Long> runReference(Fixture fixture, GeoSparqlPropertyRelation relation,
			long subject, long object) throws Exception {
		long boundEntityId = subject == 0 ? object : subject;
		List<SourceGeometryLiteral> boundSources = fixture.sources
				.getOrDefault(boundEntityId, List.of())
				.stream()
				.map(IndexGeometry::sourceGeometryLiteral)
				.toList();
		Set<Long> matchingEntityIds = new LinkedHashSet<>();
		CloseableIterator<CandidateEntity> candidates = fixture.plugin.indexer.getAllEntities();
		try {
			while (candidates.hasNext()) {
				CandidateEntity candidate = candidates.next();
				List<SourceGeometryLiteral> candidateSources = candidate.matchingSourceGeometryLiterals();
				boolean holds = subject == 0
						? Boolean.TRUE.equals(exactRelationOrUnknown(relation, candidateSources, boundSources))
						: Boolean.TRUE.equals(exactRelationOrUnknown(relation, boundSources, candidateSources));
				if (holds) {
					matchingEntityIds.add(candidate.entityId());
				}
			}
		} finally {
			candidates.close();
		}
		return matchingEntityIds;
	}

	private Boolean exactRelationOrUnknown(GeoSparqlPropertyRelation relation,
			List<SourceGeometryLiteral> subjectSources, List<SourceGeometryLiteral> objectSources) {
		boolean evaluated = false;
		for (SourceGeometryLiteral subjectSource : subjectSources) {
			for (SourceGeometryLiteral objectSource : objectSources) {
				try {
					if (relation.evaluate(subjectSource, objectSource)) {
						return true;
					}
					evaluated = true;
				} catch (JenaGeoSparqlException ignored) {
					// Keep unevaluable distinct from false for exact-reference diagnostics.
				}
			}
		}
		return evaluated ? false : null;
	}

	private static Set<Long> envelopeHits(Fixture fixture, IndexGeometry bound) throws Exception {
		CloseableIterator<CandidateEntity> candidates = fixture.plugin.indexer.getEnvelopeIntersections(bound);
		return candidateEntityIds(candidates);
	}

	private static Set<Long> disjointCleanupHits(
			Fixture fixture, IndexGeometry bound, boolean candidatesAreSubjects) throws Exception {
		CloseableIterator<CandidateEntity> candidates = fixture.plugin.indexer
				.getDisjointTransformCleanupCandidates(bound, candidatesAreSubjects);
		return candidateEntityIds(candidates);
	}

	private static Set<Long> candidateEntityIds(CloseableIterator<CandidateEntity> candidates) throws Exception {
		try {
			Set<Long> entityIds = new LinkedHashSet<>();
			while (candidates.hasNext()) {
				entityIds.add(candidates.next().entityId());
			}
			return entityIds;
		} finally {
			candidates.close();
		}
	}

	private List<Long> collectCandidateIds(GeoSparqlRelationIterator iterator, long boundSubject) {
		try {
			List<Long> entityIds = new ArrayList<>();
			while (iterator.next()) {
				entityIds.add(boundSubject == 0 ? iterator.subject : iterator.object);
			}
			return entityIds;
		} finally {
			iterator.close();
		}
	}

	private static List<IndexGeometry> geometries(String... wkts) {
		return java.util.Arrays.stream(wkts).map(TestIndexGeometries::fromWkt).toList();
	}

	private static IndexGeometry worldFallbackGeometry(String wkt) {
		// A world fallback keeps mixed-CRS sources in every relevant Lucene phase; exact evaluation still uses the
		// unchanged source geometry literal.
		return IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(wkt),
				new Envelope(-180, 180, -90, 90), CandidateBoundsKind.WORLD_FALLBACK,
				"unrepresentable-rectangle");
	}

	private DirectOperationComparison directOperationComparison(String name, String targetCrsUri,
			String sourceCrsUri, String sourceWkt, boolean reverseIntersects) throws Exception {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt("<" + sourceCrsUri + "> " + sourceWkt);
		GeometryWrapper sourceWrapper = source.asGeometryWrapper();
		GeometryWrapper jenaDirect = sourceWrapper.transform(targetCrsUri);
		Geometry directParsingGeometry = jenaDirect.getParsingGeometry();
		SourceGeometryLiteral target = SourceGeometryLiteral.fromWkt(
				"<" + targetCrsUri + "> " + directParsingGeometry.toText());
		CoordinateReferenceSystem targetCrs = SRSRegistry.getCRS(targetCrsUri);
		CoordinateOperation directOperation = CRS.findOperation(sourceWrapper.getCRS(), targetCrs, null);
		return new DirectOperationComparison(
				name,
				sourceCrsUri,
				targetCrsUri,
				source,
				target,
				jenaDirect.getXYGeometry(),
				IndexGeometry.fromSourceGeometryLiteral(source),
				IndexGeometry.fromSourceGeometryLiteral(target),
				directOperation,
				Envelopes.transform(directOperation, sourceEnvelope(sourceWrapper)),
				reverseIntersects);
	}

	private List<DirectOperationComparison> threeDimensionalDirectOperationComparisons() throws Exception {
		return List.of(
				directOperationComparison("wgs84-3d", IndexGeometry.INDEX_CRS, WGS84_3D,
						"POINT Z(-27.47 153.03 3000)", false),
				directOperationComparison("gda2020-3d", IndexGeometry.INDEX_CRS, GDA2020_3D,
						"POINT Z(-27.47 153.03 3000)", false),
				directOperationComparison("gda94-3d", IndexGeometry.INDEX_CRS, GDA94_3D,
						"POINT Z(-27.47 153.03 3000)", false),
				directOperationComparison("nad83-csrs-3d", IndexGeometry.INDEX_CRS, NAD83_CSRS_3D,
						"POINT Z(49.25 -123.1 10000)", false));
	}

	private static GeneralEnvelope sourceEnvelope(GeometryWrapper sourceWrapper) {
		CoordinateReferenceSystem sourceCrs = sourceWrapper.getCRS();
		Envelope sourceBounds = sourceWrapper.getParsingGeometry().getEnvelopeInternal();
		GeneralEnvelope sourceEnvelope = new GeneralEnvelope(sourceCrs);
		sourceEnvelope.setRange(0, sourceBounds.getMinX(), sourceBounds.getMaxX());
		sourceEnvelope.setRange(1, sourceBounds.getMinY(), sourceBounds.getMaxY());
		if (sourceCrs.getCoordinateSystem().getDimension() == 3) {
			double minZ = Double.POSITIVE_INFINITY;
			double maxZ = Double.NEGATIVE_INFINITY;
			for (Coordinate coordinate : sourceWrapper.getParsingGeometry().getCoordinates()) {
				minZ = Math.min(minZ, coordinate.getZ());
				maxZ = Math.max(maxZ, coordinate.getZ());
			}
			sourceEnvelope.setRange(2, minZ, maxZ);
		}
		return sourceEnvelope;
	}

	private void assertDirectCoincidentRelations(DirectOperationComparison comparison) {
		assertExactCoincidentDirection(comparison, comparison.targetLiteral, comparison.sourceLiteral);
		if (comparison.reverseIntersects) {
			assertExactCoincidentDirection(comparison, comparison.sourceLiteral, comparison.targetLiteral);
		}
		assertEquals(comparison.dump(), CandidateBoundsKind.TRANSFORMED,
				comparison.sourceIndex.candidateBoundsKind());
	}

	private void assertDirectOperationEnvelopeAndJenaGeometryAreCovered(
			DirectOperationComparison comparison) {
		Envelope candidate = comparison.sourceIndex.indexEnvelope();
		org.opengis.geometry.Envelope directEnvelope = comparison.directEnvelope;
		assertTrue(comparison.dump(), candidate.contains(
				directEnvelope.getMinimum(0), directEnvelope.getMinimum(1)));
		assertTrue(comparison.dump(), candidate.contains(
				directEnvelope.getMaximum(0), directEnvelope.getMaximum(1)));
		for (Coordinate coordinate : comparison.directTransformedGeometry.getCoordinates()) {
			assertTrue(comparison.dump(), candidate.contains(coordinate.x, coordinate.y));
		}
	}

	private void assertExactCoincidentDirection(DirectOperationComparison comparison,
			SourceGeometryLiteral subject, SourceGeometryLiteral object) {
		assertTrue(comparison.dump(), GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(subject, object));
		for (GeoSparqlPropertyRelation relation : comparison.falseCoincidentRelations()) {
			assertFalse(comparison.dump(), relation.evaluate(subject, object));
		}
	}

	private void assertDirectOperationPairThroughIndex(DirectOperationComparison comparison,
			IndexSettings setting, String diagnostics) throws Exception {
		Map<Long, List<IndexGeometry>> sources = new LinkedHashMap<>();
		sources.put(1L, List.of(comparison.targetIndex));
		sources.put(2L, List.of(comparison.sourceIndex));
		String settingName = setting.prefixTree.name().toLowerCase(Locale.ROOT) + "-" + setting.precision;
		Fixture fixture = createFixture("direct-" + comparison.name + "-" + settingName,
				sources, setting.prefixTree, setting.precision);
		String message = settingName + "\n" + diagnostics;

		assertIndexedCoincidentDirection(fixture, comparison, message, 1L, 2L);
		if (comparison.reverseIntersects) {
			assertIndexedCoincidentDirection(fixture, comparison,
					message + "\nreverse argument order", 2L, 1L);
		}
	}

	private void assertIndexedCoincidentDirection(Fixture fixture, DirectOperationComparison comparison,
			String message, long subjectId, long objectId) {
		assertTrue(message,
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, subjectId, 0).contains(objectId));
		assertTrue(message,
				runIndexed(fixture, GeoSparqlPropertyRelation.SF_INTERSECTS, 0, objectId).contains(subjectId));
		for (GeoSparqlPropertyRelation relation : comparison.falseCoincidentRelations()) {
			assertFalse(message + "\n" + relation,
					runIndexed(fixture, relation, subjectId, 0).contains(objectId));
			assertFalse(message + "\n" + relation,
					runIndexed(fixture, relation, 0, objectId).contains(subjectId));
		}
	}

	private static List<IndexSettings> heightDependentIndexSettings() {
		return List.of(
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, GeoSparqlConfig.PRECISION_DEFAULT),
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, QuadPrefixTree.MAX_LEVELS_POSSIBLE),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH, GeoSparqlConfig.PRECISION_DEFAULT),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH, GeohashPrefixTree.getMaxLevelsPossible()));
	}

	private static List<IndexSettings> maximumPrecisionIndexSettings() {
		return List.of(
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, QuadPrefixTree.MAX_LEVELS_POSSIBLE),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH,
						GeohashPrefixTree.getMaxLevelsPossible()));
	}

	private record Fixture(GeoSparqlPlugin plugin, FakeEntities entities,
			Map<Long, List<IndexGeometry>> sources) {
	}

	private record IndexSettings(GeoSparqlConfig.PrefixTree prefixTree, int precision) {
	}

	private record DirectOperationComparison(
			String name,
			String sourceCrsUri,
			String targetCrsUri,
			SourceGeometryLiteral sourceLiteral,
			SourceGeometryLiteral targetLiteral,
			Geometry directTransformedGeometry,
			IndexGeometry sourceIndex,
			IndexGeometry targetIndex,
			CoordinateOperation directOperation,
			org.opengis.geometry.Envelope directEnvelope,
			boolean reverseIntersects) {

		boolean isAreaPair() {
			return sourceIndex.sourceTopologicalDimension() == Dimension.A
					&& targetIndex.sourceTopologicalDimension() == Dimension.A;
		}

		List<GeoSparqlPropertyRelation> falseCoincidentRelations() {
			List<GeoSparqlPropertyRelation> relations = new ArrayList<>(List.of(
					GeoSparqlPropertyRelation.SF_DISJOINT,
					GeoSparqlPropertyRelation.EH_DISJOINT));
			if (isAreaPair()) {
				relations.add(GeoSparqlPropertyRelation.RCC8_DC);
			}
			return relations;
		}

		String dump() {
			return """
					source CRS:                  %s
					exact target CRS:            %s
					Jena direct operation:       %s
					Jena direct geometry:        %s
					direct operation envelope:   %s
					source candidate envelope:   %s (%s)
					target candidate envelope:   %s (%s)
					""".formatted(sourceCrsUri, targetCrsUri,
					directOperation.getName(),
					directTransformedGeometry, directEnvelope,
					sourceIndex.indexEnvelope(), sourceIndex.candidateBoundsKind(),
					targetIndex.indexEnvelope(), targetIndex.candidateBoundsKind());
		}
	}

	private record Gda94ToGda2020DatumOperations(
			CoordinateReferenceSystem source3d,
			CoordinateReferenceSystem source2d,
			CoordinateReferenceSystem target2d,
			CoordinateOperation full3d,
			CoordinateOperation sourceHorizontalToCrs84,
			CoordinateOperation targetHorizontalToCrs84) {

		static Gda94ToGda2020DatumOperations resolve() throws Exception {
			CoordinateReferenceSystem source3d = SourceGeometryLiteral.fromWkt(
					pointWkt(GDA94_3D, GDA94_LAT, GDA94_LON, 0)).asGeometryWrapper().getCRS();
			CoordinateReferenceSystem target3d = SourceGeometryLiteral.fromWkt(
					pointWkt(GDA2020_3D, GDA94_LAT, GDA94_LON, 0)).asGeometryWrapper().getCRS();
			CoordinateReferenceSystem source2d = CRS.getHorizontalComponent(source3d);
			CoordinateReferenceSystem target2d = CRS.getHorizontalComponent(target3d);
			CoordinateReferenceSystem crs84 = SRSRegistry.getCRS(IndexGeometry.INDEX_CRS);
			return new Gda94ToGda2020DatumOperations(source3d, source2d, target2d,
					CRS.findOperation(source3d, target3d, null),
					CRS.findOperation(source2d, crs84, null),
					CRS.findOperation(target2d, crs84, null));
		}

		DatumComparison compare(double height) throws Exception {
			MathTransform fullTransform = full3d.getMathTransform();
			DirectPosition full3dPosition = transform(fullTransform, source3d, GDA94_LAT, GDA94_LON, height);
			DirectPosition horizontalCrs84 = transform(sourceHorizontalToCrs84.getMathTransform(), source2d,
					GDA94_LAT, GDA94_LON);
			DirectPosition fullThenCrs84 = transform(targetHorizontalToCrs84.getMathTransform(), target2d,
					full3dPosition.getOrdinate(0), full3dPosition.getOrdinate(1));
			SourceGeometryLiteral sourceLiteral = SourceGeometryLiteral.fromWkt(
					pointWkt(GDA94_3D, GDA94_LAT, GDA94_LON, height));
			SourceGeometryLiteral targetLiteral = SourceGeometryLiteral.fromWkt(
					pointWkt(GDA2020_3D, full3dPosition.getOrdinate(0), full3dPosition.getOrdinate(1),
							full3dPosition.getOrdinate(2)));
			IndexGeometry sourceIndex = IndexGeometry.fromSourceGeometryLiteral(sourceLiteral);
			IndexGeometry targetIndex = IndexGeometry.fromSourceGeometryLiteral(targetLiteral);
			return new DatumComparison(height, sourceLiteral, targetLiteral, sourceIndex, targetIndex,
					horizontalCrs84.getOrdinate(0), horizontalCrs84.getOrdinate(1),
					full3dPosition.getOrdinate(0), full3dPosition.getOrdinate(1), full3dPosition.getOrdinate(2),
					fullThenCrs84.getOrdinate(0), fullThenCrs84.getOrdinate(1),
					sourceIndex.indexEnvelope(), targetIndex.indexEnvelope());
		}

		private static DirectPosition transform(MathTransform transform, CoordinateReferenceSystem crs,
				double... ordinates) throws Exception {
			GeneralDirectPosition source = new GeneralDirectPosition(crs);
			for (int i = 0; i < ordinates.length; i++) {
				source.setOrdinate(i, ordinates[i]);
			}
			return transform.transform(source, null);
		}

		private static String pointWkt(String crsUri, double lat, double lon, double height) {
			return String.format(Locale.US, "<%s> POINT Z(%.16f %.16f %.16f)", crsUri, lat, lon, height);
		}
	}

	private record DatumComparison(
			double sourceHeight,
			SourceGeometryLiteral sourceLiteral,
			SourceGeometryLiteral targetLiteral,
			IndexGeometry sourceIndex,
			IndexGeometry targetIndex,
			double horizontalLon84,
			double horizontalLat84,
			double fullLat,
			double fullLon,
			double fullHeight,
			double fullLon84,
			double fullLat84,
			Envelope sourceEnvelope,
			Envelope targetEnvelope) {

		double hypotCrs84Degrees() {
			return Math.hypot(fullLon84 - horizontalLon84, fullLat84 - horizontalLat84);
		}

		String dump() {
			return """
					source:              lat=%s, lon=%s, h=%s
					horizontal-only:     lon84=%s, lat84=%s
					full-3D transformed: lat'=%s, lon'=%s, h'=%s
					full-3D then CRS84:  lon84'=%s, lat84'=%s
					difference:          Δlon84=%s, Δlat84=%s, hypot=%s
					candidate envelopes: source=%s target=%s intersect=%s
					""".formatted(GDA94_LAT, GDA94_LON, sourceHeight,
					horizontalLon84, horizontalLat84,
					fullLat, fullLon, fullHeight,
					fullLon84, fullLat84,
					fullLon84 - horizontalLon84, fullLat84 - horizontalLat84, hypotCrs84Degrees(),
					sourceEnvelope, targetEnvelope, sourceEnvelope.intersects(targetEnvelope));
		}
	}

	private static final class FakeEntities implements Entities {
		private final Map<Long, Value> values = new HashMap<>();

		private void add(long id, Value value) {
			values.put(id, value);
		}

		@Override
		public Value get(long id) {
			return values.get(id);
		}

		@Override
		public Type getType(long id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String getLanguage(long id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public IRI getDatatype(long id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getClass(long id) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long put(Value value, Scope scope) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long replace(long id, Value value, Scope scope) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long resolve(Value value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long size() {
			return values.size();
		}

		@Override
		public boolean isTransactional() {
			return false;
		}

		@Override
		public int getEntityIdSize() {
			return Long.BYTES;
		}
	}
}
