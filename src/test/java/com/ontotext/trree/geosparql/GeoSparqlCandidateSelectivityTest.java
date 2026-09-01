package com.ontotext.trree.geosparql;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Local queries over Australia-wide features demonstrate the selectivity of ordinary and mixed-CRS candidate
 * traversal.
 *
 * <p>This is the counterpart to the coverage and differential invariants: selective SIS envelopes must remain
 * selective for ordinary projected Australian data. Non-disjoint mixed-CRS traversal uses the same geographically
 * selective index-envelope path in both binding directions.
 */
public class GeoSparqlCandidateSelectivityTest {
	private static final String GDA2020 = "http://www.opengis.net/def/crs/EPSG/0/7844";
	private static final String MGA56 = "http://www.opengis.net/def/crs/EPSG/0/7856";
	private static final int FEATURE_COUNT = 20_000;
	private static final int LON_STEPS = 200;
	private static final double MIN_LAT = -43.0;
	private static final double MAX_LAT = -12.0;
	private static final double MIN_LON = 114.0;
	private static final double MAX_LON = 153.5;

	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void sameCrsAndMixedCrsNonDisjointQueriesStaySelective()
			throws Exception {
		Path dataDir = tmpFolder.getRoot().toPath().resolve("gda2020-selectivity");
		Files.createDirectories(dataDir);

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.updateCurrentSettings();
		plugin.setConfig(config);
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlCandidateSelectivityTest.class));
		plugin.setDataDir(dataDir.toFile());

		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin);
		indexer.initialize();
		indexer.begin();
		int latSteps = FEATURE_COUNT / LON_STEPS;
		long entityId = 1;
		int transformedCount = 0;
		for (int latIndex = 0; latIndex < latSteps; latIndex++) {
			double lat = MIN_LAT + (MAX_LAT - MIN_LAT) * latIndex / (latSteps - 1);
			for (int lonIndex = 0; lonIndex < LON_STEPS; lonIndex++) {
				double lon = MIN_LON + (MAX_LON - MIN_LON) * lonIndex / (LON_STEPS - 1);
				IndexGeometry geometry = geometry(String.format(Locale.ROOT,
						"<%s> POINT(%.6f %.6f)", GDA2020, lat, lon));
				assertEquals("ordinary GDA2020 points must stay selective",
						CandidateBoundsKind.TRANSFORMED, geometry.candidateBoundsKind());
				transformedCount++;
				indexer.indexGeometryList(entityId, id -> "feature-" + id, List.of(geometry));
				entityId++;
			}
		}
		indexer.commit();
		indexer.complete();

		IndexGeometry brisbane = geometry(
				"<" + GDA2020 + "> POLYGON((-27.6 152.9,-27.3 152.9,-27.3 153.2,-27.6 153.2,-27.6 152.9))");
		assertEquals(CandidateBoundsKind.TRANSFORMED, brisbane.candidateBoundsKind());
		int candidates = countEntities(indexer.getEnvelopeIntersections(brisbane));
		int indexed = transformedCount;
		assertEquals(FEATURE_COUNT, indexed);
		assertTrue("Brisbane query retrieved " + candidates + " of " + indexed
						+ " GDA2020 features; the candidate index has become unselective",
				candidates > 0 && candidates < indexed / 10);
		assertRelationTraversalIsSelective(indexer, brisbane, true, indexed);
		assertRelationTraversalIsSelective(indexer, brisbane, false, indexed);

		IndexGeometry crs84Brisbane = geometry(
				"POLYGON((152.9 -27.6,152.9 -27.3,153.2 -27.3,153.2 -27.6,152.9 -27.6))");
		assertEquals(CandidateBoundsKind.NATIVE_CRS84, crs84Brisbane.candidateBoundsKind());

		int boundSubjectEnvelopeCandidates = countEntities(
				indexer.getEnvelopeIntersections(crs84Brisbane));
		TraversalCounts boundSubjectCounts = countRelationTraversal(indexer, crs84Brisbane, true);
		assertTrue("CRS84-bound subject envelope phase materialized "
					+ boundSubjectEnvelopeCandidates + " of " + indexed + " features",
				boundSubjectEnvelopeCandidates > 0 && boundSubjectEnvelopeCandidates < indexed / 10);
		assertEquals(boundSubjectEnvelopeCandidates,
				boundSubjectCounts.traversalCandidatesMaterialized());
		assertEquals(boundSubjectCounts.traversalCandidatesMaterialized(),
				boundSubjectCounts.sourceGeometryLiteralsMaterialized());
		assertEquals(boundSubjectCounts.traversalCandidatesMaterialized(),
				boundSubjectCounts.distinctCandidateEntities());

		int boundObjectEnvelopeCandidates = countEntities(
				indexer.getEnvelopeIntersections(crs84Brisbane));
		TraversalCounts boundObjectCounts = countRelationTraversal(indexer, crs84Brisbane, false);
		assertTrue("CRS84-bound object envelope phase materialized "
					+ boundObjectEnvelopeCandidates + " of " + indexed + " features",
				boundObjectEnvelopeCandidates > 0 && boundObjectEnvelopeCandidates < indexed / 10);
		assertEquals(boundObjectEnvelopeCandidates,
				boundObjectCounts.traversalCandidatesMaterialized());
		assertEquals("candidate traversal reconstructs each selected source literal once",
				boundObjectCounts.traversalCandidatesMaterialized(),
				boundObjectCounts.sourceGeometryLiteralsMaterialized());
		assertEquals(boundObjectCounts.traversalCandidatesMaterialized(),
				boundObjectCounts.distinctCandidateEntities());

		IndexGeometry mgaBrisbane = geometry("<" + MGA56
				+ "> POLYGON((450000 6900000,450000 7020000,560000 7020000,560000 6900000,450000 6900000))");
		assertEquals(CandidateBoundsKind.TRANSFORMED, mgaBrisbane.candidateBoundsKind());
		int mgaCandidates = countEntities(indexer.getEnvelopeIntersections(mgaBrisbane));
		assertTrue("MGA2020 Brisbane envelope retrieved " + mgaCandidates + " of " + indexed + " features",
				mgaCandidates > 0 && mgaCandidates < indexed / 10);
	}

	private static IndexGeometry geometry(String wkt) {
		return IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt));
	}

	private static int countEntities(CloseableIterator<CandidateEntity> iterator) throws Exception {
		try {
			int count = 0;
			while (iterator.hasNext()) {
				iterator.next();
				count++;
			}
			return count;
		} finally {
			iterator.close();
		}
	}

	private static void assertRelationTraversalIsSelective(
			GeoSparqlIndexer indexer, IndexGeometry bound, boolean boundSubject, int indexed) {
		int candidates = countRelationTraversal(indexer, bound, boundSubject).traversalCandidatesMaterialized();
		String direction = boundSubject ? "subject-bound" : "object-bound";
		assertTrue(direction + " relation traversal retrieved " + candidates + " of " + indexed
					+ " same-CRS GDA2020 features",
				candidates > 0 && candidates < indexed / 10);
	}

	private static TraversalCounts countRelationTraversal(
			GeoSparqlIndexer indexer, IndexGeometry bound, boolean boundSubject) {
		int candidates = 0;
		int sourceGeometryLiterals = 0;
		Set<Long> exactCandidateEntityIds = new HashSet<>();
		try (RelationCandidateTraversal traversal = new RelationCandidateTraversal(
				indexer, GeoSparqlPropertyRelation.SF_INTERSECTS,
				List.of(bound), boundSubject, LoggerFactory.getLogger(GeoSparqlCandidateSelectivityTest.class))) {
			while (traversal.hasNext()) {
				RelationCandidateTraversal.Candidate candidate = traversal.next();
				assertEquals(RelationCandidateTraversal.MatchCertainty.REQUIRES_EXACT_EVALUATION,
						candidate.matchCertainty());
				candidates++;
				sourceGeometryLiterals += candidate.exactCandidateEntity()
						.matchingSourceGeometryLiterals().size();
				exactCandidateEntityIds.add(candidate.entityId());
			}
		}
		return new TraversalCounts(candidates, sourceGeometryLiterals, exactCandidateEntityIds.size());
	}

	private record TraversalCounts(
			int traversalCandidatesMaterialized,
			int sourceGeometryLiteralsMaterialized,
			int distinctCandidateEntities) {
	}
}
