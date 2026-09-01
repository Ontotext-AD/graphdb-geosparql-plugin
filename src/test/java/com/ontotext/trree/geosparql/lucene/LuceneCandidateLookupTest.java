package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Rectangle;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LuceneCandidateLookupTest {
	private static final int DEFAULT_PRECISION = 11;
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";

	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void envelopeLookupReturnsIntersectionsAndPrunesSeparatedEnvelopes() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("basic-envelope-candidates");
		IndexGeometry bound = geometry("POLYGON((0 0,0 4,4 4,4 0,0 0))");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "inside", List.of(geometry("POINT(1 1)")));
		indexer.indexGeometryList(2L, id -> "overlap",
				List.of(geometry("POLYGON((3 3,3 6,6 6,6 3,3 3))")));
		indexer.indexGeometryList(3L, id -> "separated", List.of(geometry("POINT(20 20)")));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L, 2L},
				collectEntityIds(indexer.getEnvelopeIntersections(bound)));
	}

	@Test
	public void envelopeLookupNeverOmitsAnIntersectingIndexedEnvelope() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("conservative-envelope-candidates");
		assertConservativeEnvelopeLookup(indexer, List.of(
				geometry("POINT(0 0)"),
				geometry("LINESTRING(0 -2,0 2)"),
				geometry("LINESTRING(-2 0,2 0)"),
				geometry("POLYGON((-1 -1,-1 1,1 1,1 -1,-1 -1))"),
				geometry("POLYGON((1 -1,1 1,3 1,3 -1,1 -1))"),
				geometry("POINT(20 20)")));
	}

	@Test
	public void envelopeLookupIsConservativeAcrossPrefixTreesAndPrecisions() throws Exception {
		List<IndexGeometry> geometries = generatedEnvelopeFixtures();
		List<IndexSettings> settings = List.of(
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, 1),
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, DEFAULT_PRECISION),
				new IndexSettings(GeoSparqlConfig.PrefixTree.QUAD, QuadPrefixTree.MAX_LEVELS_POSSIBLE),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH, 1),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH, DEFAULT_PRECISION),
				new IndexSettings(GeoSparqlConfig.PrefixTree.GEOHASH,
						GeohashPrefixTree.getMaxLevelsPossible()));

		for (IndexSettings setting : settings) {
			LuceneGeoIndexer indexer = createIndexer(
					"conservative-" + setting.prefixTree.name().toLowerCase(Locale.ROOT)
							+ "-" + setting.precision,
					setting.prefixTree, setting.precision);
			assertConservativeEnvelopeLookup(indexer, geometries);
		}
	}

	@Test
	public void envelopeLookupRetainsEveryIntersectionAcrossSearchPages() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("conservative-envelope-paging");
		int candidateCount = 1005;
		IndexGeometry bound = rectangle(-170, 170, -80, 80);

		indexer.begin();
		for (int i = 0; i < candidateCount; i++) {
			double x = -160 + (i % 50) * 6.0;
			double y = -70 + (i / 50) * 6.0;
			long entityId = i + 1L;
			indexer.indexGeometryList(entityId, id -> "paged geometry " + id,
					List.of(geometry("POINT(" + x + " " + y + ")")));
		}
		indexer.commit();
		indexer.complete();

		long[] candidates = collectEntityIds(indexer.getEnvelopeIntersections(bound));
		assertEquals(candidateCount, candidates.length);
		assertEquals(1L, candidates[0]);
		assertEquals(candidateCount, candidates[candidates.length - 1]);
	}

	@Test
	public void envelopeLookupKeepsOneEntityGroupedAcrossSearchPages() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("single-entity-envelope-paging");
		int sourceCount = 1005;
		IndexGeometry bound = rectangle(-1, 1, -1, 1);
		List<IndexGeometry> firstEntityGeometries = new ArrayList<>(sourceCount);
		Set<SourceGeometryLiteral> expectedFirstEntitySources = new HashSet<>();
		for (int i = 0; i < sourceCount; i++) {
			IndexGeometry source = geometry("POINT(" + (i + 1) / 10000.0 + " 0)");
			firstEntityGeometries.add(source);
			expectedFirstEntitySources.add(source.sourceGeometryLiteral());
		}
		IndexGeometry secondEntityGeometry = geometry("POINT(0 0.5)");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "many sources", firstEntityGeometries);
		indexer.indexGeometryList(2L, id -> "one source", List.of(secondEntityGeometry));
		indexer.commit();
		indexer.complete();

		try (CloseableIterator<CandidateEntity> candidates = indexer.getEnvelopeIntersections(bound)) {
			assertTrue(candidates.hasNext());
			CandidateEntity first = candidates.next();
			assertEquals(1L, first.entityId());
			assertEquals(sourceCount, first.matchingSourceGeometryLiterals().size());
			assertEquals(expectedFirstEntitySources,
					new HashSet<>(first.matchingSourceGeometryLiterals()));

			assertTrue(candidates.hasNext());
			CandidateEntity second = candidates.next();
			assertEquals(2L, second.entityId());
			assertEquals(List.of(secondEntityGeometry.sourceGeometryLiteral()),
					second.matchingSourceGeometryLiterals());
			assertFalse(candidates.hasNext());
		}
	}

	@Test
	public void separatedIndexEnvelopesProveSfAndEhDisjoint() {
		List<IndexGeometry> geometries = generatedEnvelopeFixtures();
		geometries.add(geometry("GEOMETRYCOLLECTION(POINT(-150 -70),LINESTRING(-145 -65,-140 -60))"));

		for (int leftIndex = 0; leftIndex < geometries.size(); leftIndex++) {
			for (int rightIndex = leftIndex + 1; rightIndex < geometries.size(); rightIndex++) {
				IndexGeometry left = geometries.get(leftIndex);
				IndexGeometry right = geometries.get(rightIndex);
				if (left.indexEnvelope().intersects(right.indexEnvelope())) {
					continue;
				}
				String pair = "index geometry pair " + leftIndex + "/" + rightIndex;
				assertTrue(pair + " must be sfDisjoint", GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
						left.sourceGeometryLiteral(), right.sourceGeometryLiteral()));
				assertTrue(pair + " must be ehDisjoint", GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
						left.sourceGeometryLiteral(), right.sourceGeometryLiteral()));
			}
		}
	}

	@Test
	public void projectedLineAndOnLinePointRemainEnvelopeIntersectionCandidates() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("projected-nonlinear-transform");
		IndexGeometry line = geometry(
				"<" + EPSG_32634 + "> LINESTRING(200000 7000000, 800000 7000000)");
		IndexGeometry point = geometry("<" + EPSG_32634 + "> POINT(500000 7000000)");
		IndexGeometry distantCrs84 = geometry("POINT(20 50)");

		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				line.sourceGeometryLiteral(), point.sourceGeometryLiteral()));
		assertTrue(line.indexEnvelope().intersects(point.indexEnvelope()));
		assertFalse(point.indexEnvelope().intersects(distantCrs84.indexEnvelope()));

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "line", List.of(line));
		indexer.indexGeometryList(2L, id -> "distant", List.of(distantCrs84));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeIntersections(point)));
		assertEquals(Set.of(new EnvelopeDisjointCandidate(2L, 0)),
				new HashSet<>(collectEnvelopeDisjointCandidates(
						indexer.getEnvelopeDisjointCandidates(point))));
	}

	@Test
	public void separatedCrossCrsIndexEnvelopesProveSfAndEhDisjoint() {
		IndexGeometry projected = geometry("<" + EPSG_32634 + "> POINT(799997.80 4589779.63)");
		IndexGeometry crs84 = geometry("POINT(20 50)");

		assertFalse(projected.indexEnvelope().intersects(crs84.indexEnvelope()));
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				projected.sourceGeometryLiteral(), crs84.sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
				projected.sourceGeometryLiteral(), crs84.sourceGeometryLiteral()));
	}

	@Test
	public void rcc8DisconnectedRequiresAreaApplicabilityBeforeEnvelopeShortCircuit() {
		IndexGeometry leftPoint = geometry("POINT(-10 -10)");
		IndexGeometry rightPoint = geometry("POINT(10 10)");
		IndexGeometry leftArea = rectangle(-10, -5, -10, -5);
		IndexGeometry rightArea = rectangle(5, 10, 5, 10);

		assertFalse(leftPoint.indexEnvelope().intersects(rightPoint.indexEnvelope()));
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				leftPoint.sourceGeometryLiteral(), rightPoint.sourceGeometryLiteral()));
		assertFalse(GeoSparqlPropertyRelation.RCC8_DC.evaluate(
				leftPoint.sourceGeometryLiteral(), rightPoint.sourceGeometryLiteral()));

		assertFalse(leftArea.indexEnvelope().intersects(rightArea.indexEnvelope()));
		assertTrue(GeoSparqlPropertyRelation.RCC8_DC.evaluate(
				leftArea.sourceGeometryLiteral(), rightArea.sourceGeometryLiteral()));
	}

	@Test
	public void emptySentinelsRemainOnTheExactEvaluationPath() {
		IndexGeometry emptyPoint = geometry("POINT EMPTY");
		IndexGeometry emptyCollection = geometry("GEOMETRYCOLLECTION EMPTY");
		IndexGeometry polygon = rectangle(-1, 1, -1, 1);

		assertFalse(emptyPoint.isSpatialCandidate());
		assertFalse(emptyCollection.isSpatialCandidate());
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				emptyPoint.sourceGeometryLiteral(), polygon.sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				emptyCollection.sourceGeometryLiteral(), polygon.sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
				emptyPoint.sourceGeometryLiteral(), polygon.sourceGeometryLiteral()));
		assertTrue(GeoSparqlPropertyRelation.EH_DISJOINT.evaluate(
				emptyCollection.sourceGeometryLiteral(), polygon.sourceGeometryLiteral()));
	}

	/**
	 * World-envelope Lucene behaviour for {@link CandidateBoundsKind#WORLD_FALLBACK}.
	 *
	 * <p>Valid-domain source literals in the CRS families under test produce {@link CandidateBoundsKind#TRANSFORMED}
	 * through the projector. This fixture therefore injects the world envelope so the Lucene path can be checked
	 * independently of CRS-data availability.
	 */
	@Test
	public void worldFallbackEnvelopeRemainsACandidateAndIsNotADefiniteDisjointMatch() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("world-fallback-envelope");
		IndexGeometry localBound = rectangle(-1, 1, -1, 1);
		IndexGeometry fallback = IndexGeometryFixtures.withIndexEnvelope(
				SourceGeometryLiteral.fromWkt("<" + EPSG_32634 + "> POINT(500000 7000000)"),
				worldCrs84Envelope(), CandidateBoundsKind.WORLD_FALLBACK, "unrepresentable-rectangle");
		IndexGeometry separated = geometry("POINT(20 20)");

		assertFalse(fallback.isEnvelopeCoveringRectangle());
		assertEquals(CandidateBoundsKind.WORLD_FALLBACK, fallback.candidateBoundsKind());
		assertEquals(worldCrs84Envelope(), fallback.indexEnvelope());

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "fallback", List.of(fallback));
		indexer.indexGeometryList(2L, id -> "separated", List.of(separated));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeIntersections(localBound)));
		assertEquals(Set.of(new EnvelopeDisjointCandidate(2L, 0)),
				new HashSet<>(collectEnvelopeDisjointCandidates(
						indexer.getEnvelopeDisjointCandidates(localBound))));
		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeDisjointUncertainCandidates(localBound)));
	}

	@Test
	public void disjointPartitionSeparatesDefiniteUncertainAndNonSpatialSourceDocuments() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("disjoint-partition");
		IndexGeometry bound = rectangle(0, 4, 0, 4);

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "inside", List.of(geometry("POINT(1 1)")));
		indexer.indexGeometryList(2L, id -> "overlap",
				List.of(rectangle(3, 6, 3, 6)));
		indexer.indexGeometryList(3L, id -> "separated", List.of(geometry("POINT(20 20)")));
		indexer.indexGeometryList(4L, id -> "empty point", List.of(geometry("POINT EMPTY")));
		indexer.indexGeometryList(5L, id -> "empty collection",
				List.of(geometry("GEOMETRYCOLLECTION EMPTY")));
		indexer.indexGeometryList(6L, id -> "mixed",
				List.of(geometry("POINT(2 2)"), geometry("LINESTRING(10 10,12 12)")));
		indexer.indexGeometryList(7L, id -> "boundary touch", List.of(geometry("POINT(0 2)")));
		indexer.commit();
		indexer.complete();

		List<EnvelopeDisjointCandidate> definite =
				collectEnvelopeDisjointCandidates(indexer.getEnvelopeDisjointCandidates(bound));
		assertEquals(Set.of(
				new EnvelopeDisjointCandidate(3L, 0),
				new EnvelopeDisjointCandidate(6L, 1)), new HashSet<>(definite));
		assertArrayEquals(new long[]{1L, 2L, 6L, 7L},
				collectEntityIds(indexer.getEnvelopeIntersections(bound)));
		assertArrayEquals(new long[]{2L},
				collectEntityIds(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
		assertArrayEquals(new long[]{4L, 5L},
				collectEntityIds(indexer.getNonSpatialCandidates()));
	}

	@Test
	public void envelopeDisjointCandidatesStreamMoreThanOneThousandSourceDocuments() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("disjoint-partition-large-result");
		int candidateCount = 1005;
		IndexGeometry bound = rectangle(-1, 1, -1, 1);

		indexer.begin();
		for (int i = 0; i < candidateCount; i++) {
			double x = 10 + (i % 100);
			double y = 10 + (i / 100);
			long entityId = i + 1L;
			indexer.indexGeometryList(entityId, id -> "disjoint streamed geometry " + id,
					List.of(geometry("POINT(" + x + " " + y + ")")));
		}
		indexer.commit();
		indexer.complete();

		List<EnvelopeDisjointCandidate> candidates =
				collectEnvelopeDisjointCandidates(indexer.getEnvelopeDisjointCandidates(bound));
		assertEquals(candidateCount, candidates.size());
		Set<Long> candidateIds = new HashSet<>();
		candidates.forEach(candidate -> candidateIds.add(candidate.entityId()));
		assertEquals(candidateCount, candidateIds.size());
		for (long entityId = 1; entityId <= candidateCount; entityId++) {
			assertTrue(candidateIds.contains(entityId));
		}
		assertTrue(candidates.stream().allMatch(candidate -> candidate.sourceTopologicalDimension() == 0));
	}

	@Test
	public void emptyBoundHasNoDefiniteOrUncertainEnvelopePartition() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("empty-bound-partition");
		IndexGeometry empty = geometry("GEOMETRYCOLLECTION EMPTY");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "point", List.of(geometry("POINT(10 10)")));
		indexer.indexGeometryList(2L, id -> "empty", List.of(empty));
		indexer.commit();
		indexer.complete();

		assertTrue(collectEnvelopeDisjointCandidates(
				indexer.getEnvelopeDisjointCandidates(empty)).isEmpty());
		assertArrayEquals(new long[0], collectEntityIds(indexer.getEnvelopeIntersections(empty)));
		assertArrayEquals(new long[]{2L}, collectEntityIds(indexer.getNonSpatialCandidates()));
	}

	private static void assertConservativeEnvelopeLookup(LuceneGeoIndexer indexer,
			List<IndexGeometry> geometries) throws Exception {
		indexer.begin();
		for (int i = 0; i < geometries.size(); i++) {
			long entityId = i + 1L;
			indexer.indexGeometryList(entityId, id -> "geometry " + id, List.of(geometries.get(i)));
		}
		indexer.commit();
		indexer.complete();
		for (int boundIndex = 0; boundIndex < geometries.size(); boundIndex++) {
			IndexGeometry bound = geometries.get(boundIndex);
			Set<Long> expectedIntersections = new HashSet<>();
			for (int indexed = 0; indexed < geometries.size(); indexed++) {
				if (geometries.get(indexed).indexEnvelope().intersects(bound.indexEnvelope())) {
					expectedIntersections.add(indexed + 1L);
				}
			}

			Set<Long> actualCandidates = boxedSet(
					collectEntityIds(indexer.getEnvelopeIntersections(bound)));
			assertTrue("bound geometry " + (boundIndex + 1) + " omitted envelope intersections "
							+ difference(expectedIntersections, actualCandidates),
					actualCandidates.containsAll(expectedIntersections));
		}
	}

	@Test
	public void wideAntimeridianEnvelopeRetainsMidpointCandidate() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("antimeridian-envelope-candidates");
		IndexGeometry bound = geometry("LINESTRING(170 10,-170 12)");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "midpoint", List.of(geometry("POINT(0 11)")));
		indexer.indexGeometryList(2L, id -> "outside latitude", List.of(geometry("POINT(0 20)")));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeIntersections(bound)));
	}

	@Test
	public void envelopeLookupAllowsExpectedTopologyFalsePositives() throws Exception {
		List<FalsePositiveFixture> fixtures = List.of(
				new FalsePositiveFixture(
						"POLYGON((0 0,0 10,3 10,3 3,10 3,10 0,0 0))", "POINT(8 8)"),
				new FalsePositiveFixture(
						"POLYGON((0 0,0 10,10 10,10 0,0 0),(4 4,6 4,6 6,4 6,4 4))",
						"POINT(5 5)"),
				new FalsePositiveFixture(
						"MULTIPOLYGON(((0 0,0 2,2 2,2 0,0 0)),"
								+ "((8 8,8 10,10 10,10 8,8 8)))", "POINT(5 5)"),
				new FalsePositiveFixture(
						"GEOMETRYCOLLECTION(POINT(0 0),POINT(10 10))", "POINT(5 5)"));

		for (int i = 0; i < fixtures.size(); i++) {
			FalsePositiveFixture fixture = fixtures.get(i);
			LuceneGeoIndexer indexer = createIndexer("false-positive-" + i);
			IndexGeometry indexed = geometry(fixture.indexedWkt);
			IndexGeometry bound = geometry(fixture.boundWkt);

			assertFalse(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					indexed.sourceGeometryLiteral(), bound.sourceGeometryLiteral()));

			indexer.begin();
			indexer.indexGeometryList(1L, id -> "false positive", List.of(indexed));
			indexer.commit();
			indexer.complete();

			assertArrayEquals("fixture " + i, new long[]{1L},
					collectEntityIds(indexer.getEnvelopeIntersections(bound)));
		}
	}

	@Test
	public void ineligibleHoledBoundKeepsOverlappingEnvelopeDisjointCandidatesUncertain() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("disjoint-holed-bound");
		IndexGeometry holedPolygon = geometry(
				"POLYGON((0 0,0 10,10 10,10 0,0 0),(4 4,6 4,6 6,4 6,4 4))");
		IndexGeometry pointInHole = geometry("POINT(5 5)");

		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				pointInHole.sourceGeometryLiteral(), holedPolygon.sourceGeometryLiteral()));

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "point in hole", List.of(pointInHole));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeDisjointUncertainCandidates(holedPolygon)));
	}

	@Test
	public void emptySentinelsAreAbsentFromEnvelopeQueriesAndPresentInFullScans() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("empty-sentinel");
		IndexGeometry empty = geometry("GEOMETRYCOLLECTION EMPTY");
		IndexGeometry point = geometry("POINT(1 1)");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "point", List.of(point));
		indexer.indexGeometryList(2L, id -> "empty", List.of(empty));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L}, collectEntityIds(indexer.getEnvelopeIntersections(point)));
		assertArrayEquals(new long[0], collectEntityIds(indexer.getEnvelopeIntersections(empty)));
		assertArrayEquals(new long[]{1L, 2L}, collectEntityIds(indexer.getAllEntities()));
	}

	@Test
	public void oneEnvelopeRepresentsLargeAndNestedCollections() throws Exception {
		StringBuilder wkt = new StringBuilder("GEOMETRYCOLLECTION(GEOMETRYCOLLECTION(POINT(0 0)),");
		for (int i = 1; i < 1500; i++) {
			if (i > 1) {
				wkt.append(',');
			}
			wkt.append("POINT(").append(i % 100).append(' ').append(i / 100).append(')');
		}
		wkt.append(')');
		IndexGeometry collection = geometry(wkt.toString());
		LuceneGeoIndexer indexer = createIndexer("large-collection");

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "edge point", List.of(geometry("POINT(99 14)")));
		indexer.indexGeometryList(2L, id -> "outside", List.of(geometry("POINT(101 14)")));
		indexer.commit();
		indexer.complete();

		assertArrayEquals(new long[]{1L},
				collectEntityIds(indexer.getEnvelopeIntersections(collection)));
	}

	private LuceneGeoIndexer createIndexer(String directoryName) throws Exception {
		return createIndexer(directoryName, GeoSparqlConfig.PrefixTree.QUAD, DEFAULT_PRECISION);
	}

	private LuceneGeoIndexer createIndexer(String directoryName, GeoSparqlConfig.PrefixTree prefixTree,
			int precision) throws Exception {
		Path dataDir = tmpFolder.getRoot().toPath().resolve(directoryName);
		Files.createDirectories(dataDir);

		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setPrefixTree(prefixTree);
		config.setPrecision(precision);
		config.updateCurrentSettings();
		parent.setConfig(config);
		parent.setLogger(LoggerFactory.getLogger(LuceneCandidateLookupTest.class));
		parent.setDataDir(dataDir.toFile());

		LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent);
		indexer.initialize();
		return indexer;
	}

	private static IndexGeometry geometry(String wkt) {
		return TestIndexGeometries.fromSource(SourceGeometryLiteral.fromWkt(wkt));
	}

	private static List<IndexGeometry> generatedEnvelopeFixtures() {
		List<IndexGeometry> geometries = new ArrayList<>(List.of(
				geometry("POINT(0 0)"),
				geometry("LINESTRING(0 -80,0 80)"),
				geometry("LINESTRING(-170 0,170 0)"),
				geometry("POLYGON((-1 -1,-1 1,1 1,1 -1,-1 -1))"),
				geometry("POLYGON((1 -1,1 1,3 1,3 -1,1 -1))"),
				geometry("LINESTRING(170 10,-170 12)"),
				geometry("POINT(179.999 89.999)"),
				geometry("POINT(-179.999 -89.999)")));
		Random random = new Random(0x5fD15L);
		for (int i = 0; i < 24; i++) {
			double minX = -170.0 + random.nextDouble() * 330.0;
			double minY = -80.0 + random.nextDouble() * 150.0;
			double maxX = Math.min(179.999, minX + 0.001 + random.nextDouble() * 10.0);
			double maxY = Math.min(89.999, minY + 0.001 + random.nextDouble() * 10.0);
			geometries.add(rectangle(minX, maxX, minY, maxY));
		}
		return geometries;
	}

	private static IndexGeometry rectangle(double minX, double maxX, double minY, double maxY) {
		return geometry(String.format(Locale.ROOT,
				"POLYGON((%1$f %3$f,%1$f %4$f,%2$f %4$f,%2$f %3$f,%1$f %3$f))",
				minX, maxX, minY, maxY));
	}

	private static Envelope worldCrs84Envelope() {
		Rectangle world = SpatialContext.GEO.getWorldBounds();
		return new Envelope(world.getMinX(), world.getMaxX(), world.getMinY(), world.getMaxY());
	}

	private static long[] collectEntityIds(CloseableIterator<CandidateEntity> iterator) throws IOException {
		try {
			long[] ids = new long[16];
			int size = 0;
			while (iterator.hasNext()) {
				if (size == ids.length) {
					long[] expanded = new long[ids.length * 2];
					System.arraycopy(ids, 0, expanded, 0, ids.length);
					ids = expanded;
				}
				ids[size++] = iterator.next().entityId();
			}
			long[] result = new long[size];
			System.arraycopy(ids, 0, result, 0, size);
			return result;
		} finally {
			iterator.close();
		}
	}

	private static List<EnvelopeDisjointCandidate> collectEnvelopeDisjointCandidates(
			CloseableIterator<EnvelopeDisjointCandidate> iterator) throws IOException {
		try {
			List<EnvelopeDisjointCandidate> candidates = new ArrayList<>();
			while (iterator.hasNext()) {
				candidates.add(iterator.next());
			}
			return candidates;
		} finally {
			iterator.close();
		}
	}

	private static Set<Long> boxedSet(long[] values) {
		Set<Long> result = new HashSet<>();
		Arrays.stream(values).forEach(result::add);
		return result;
	}

	private static Set<Long> difference(Set<Long> expected, Set<Long> actual) {
		Set<Long> missing = new HashSet<>(expected);
		missing.removeAll(actual);
		return missing;
	}

	private static final class FalsePositiveFixture {
		private final String indexedWkt;
		private final String boundWkt;

		private FalsePositiveFixture(String indexedWkt, String boundWkt) {
			this.indexedWkt = indexedWkt;
			this.boundWkt = boundWkt;
		}
	}

	private static final class IndexSettings {
		private final GeoSparqlConfig.PrefixTree prefixTree;
		private final int precision;

		private IndexSettings(GeoSparqlConfig.PrefixTree prefixTree, int precision) {
			this.prefixTree = prefixTree;
			this.precision = precision;
		}
	}
}
