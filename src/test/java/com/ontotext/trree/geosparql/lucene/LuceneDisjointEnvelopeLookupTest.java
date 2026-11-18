package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.locationtech.jts.geom.Envelope;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LuceneDisjointEnvelopeLookupTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void separatedEnvelopesAreDefiniteMatchesAtEveryGridPrecision() throws Exception {
		for (GeoSparqlConfig.PrefixTree tree : GeoSparqlConfig.PrefixTree.values()) {
			for (int precision : new int[]{1, 11}) {
				LuceneGeoIndexer indexer = createIndexer(tree.name() + precision, tree, precision);
				index(indexer, List.of(
						geometry("POLYGON((10.009 10.009,10.011 10.009,10.011 10.011,10.009 10.011,10.009 10.009))"),
						geometry("POLYGON((10.012 10.012,10.013 10.012,10.013 10.013,10.012 10.013,10.012 10.012))"),
						geometry("POINT(10.01 10.01)")));
				IndexGeometry bound = geometry("POINT(10.01 10.01)");

				assertEquals(Set.of(2L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
				assertEquals(Set.of(2L), definite(indexer.getEnvelopeDisjointCandidates(bound, false)));
				assertEquals(Set.of(1L, 3L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
			}
		}
	}

	@Test
	public void coveringRectangleRetainsSeparationAndRejectsClosedBoundarySources() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("rectangle", GeoSparqlConfig.PrefixTree.QUAD, 1);
		index(indexer, List.of(geometry("POINT(0 0)"), geometry("POINT(1 1)"),
				geometry("POINT(1.00001 1)"), geometry("POINT(1 1.00001)"), geometry("POINT EMPTY"),
				geometry("POINT(-1.00001 -1)"), geometry("POINT(-1 -1.00001)")));
		IndexGeometry bound = geometry("POLYGON((-1 -1,-1 1,1 1,1 -1,-1 -1))");

		assertEquals(Set.of(3L, 4L, 6L, 7L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
		assertEquals(Set.of(5L), uncertain(indexer.getNonSpatialCandidates()));
	}

	/**
	 * Separated envelopes make neither nominal area a definite match, in the bound's CRS or in another, while the
	 * collection that holds a polygon stays one.
	 */
	@Test
	public void separatedNominalAreasAreEvaluatedExactly() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("nominal-areas", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(
				geometry("GEOMETRYCOLLECTION(POINT(20 20),POLYGON EMPTY)"),
				geometry("GEOMETRYCOLLECTION(POINT(20 20),POLYGON((20 20,21 20,21 21,20 20)))"),
				geometry("<http://www.opengis.net/def/crs/EPSG/0/3857> "
						+ "GEOMETRYCOLLECTION(POINT(2000000 2000000),POLYGON EMPTY)")));
		IndexGeometry bound = geometry("POLYGON((0 0,1 0,1 1,0 1,0 0))");

		for (boolean candidateIsSubject : new boolean[]{true, false}) {
			assertEquals(Set.of(2L), definite(indexer.getEnvelopeDisjointCandidates(bound, candidateIsSubject)));
			assertEquals(Set.of(1L, 3L),
					uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject)));
		}
	}

	/** Sources 6 and later lie inside the bound with sources 1 and 3; there are enough of them to certify it. */
	@Test
	public void nonRectangularBoundPrunesContainedSourcesAndRetainsDisjointCandidates() throws Exception {
		for (GeoSparqlConfig.PrefixTree tree : GeoSparqlConfig.PrefixTree.values()) {
			LuceneGeoIndexer indexer = createIndexer("interior-rectangles-" + tree, tree, 1);
			List<IndexGeometry> sources = new ArrayList<>(List.of(geometry("POINT(1 1)"), geometry("POINT(15 15)"),
					geometry("POINT(0 8)"), geometry("POINT(-1 -1)"), geometry("POINT EMPTY")));
			sources.addAll(Collections.nCopies(200, geometry("POINT(2 2)")));
			index(indexer, sources);
			IndexGeometry bound = geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))");

			assertEquals(Set.of(2L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
			assertEquals(Set.of(4L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
			assertEquals(Set.of(5L), uncertain(indexer.getNonSpatialCandidates()));
		}
	}

	/** Certifying a bound takes work that a few candidates cannot repay, so they are all evaluated exactly. */
	@Test
	public void fewCandidatesAreKeptWithoutCertifyingTheBound() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("few-candidates", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(geometry("POINT(1 1)"), geometry("POINT(15 15)"), geometry("POINT(0 8)"),
				geometry("POINT(-1 -1)")));
		IndexGeometry bound = geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))");

		assertEquals(Set.of(1L, 2L, 3L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
		assertEquals(Set.of(4L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
	}

	@Test
	public void interiorPruningRetainsAllCandidatesWhenAtMostHalfCanBeExcluded() throws Exception {
		for (int outsideCount : new int[]{100, 101}) {
			LuceneGeoIndexer indexer = createIndexer("low-reduction-" + outsideCount,
					GeoSparqlConfig.PrefixTree.QUAD, 11);
			List<IndexGeometry> sources = new ArrayList<>(Collections.nCopies(100, geometry("POINT(1 1)")));
			sources.addAll(Collections.nCopies(outsideCount, geometry("POINT(15 15)")));
			index(indexer, sources);

			assertEquals(sources.size(), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(
					geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"), false)).size());
		}
	}

	/**
	 * A circle fills no large rectangle. Certifying larger cells before their quarters still covers most of it, so
	 * most of the sources spread over its envelope are removed, and every removed source meets the circle.
	 */
	@Test
	public void roundBoundPrunesMostSourcesInsideIt() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("round-bound", GeoSparqlConfig.PrefixTree.QUAD, 11);
		List<IndexGeometry> sources = gridOverTheCircleEnvelope();
		index(indexer, sources);
		IndexGeometry bound = geometry(circle(300));

		Set<Long> uncertain = uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false));

		assertTrue(uncertain.size() + " of " + sources.size(), uncertain.size() < sources.size() / 2);
		for (int id = 1; id <= sources.size(); id++) {
			if (!uncertain.contains((long) id)) {
				assertFalse("removed source is disjoint: " + sources.get(id - 1).sourceGeometryLiteral().lexicalForm(),
						GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(sources.get(id - 1).sourceGeometryLiteral(),
								bound.sourceGeometryLiteral()));
			}
		}
	}

	/**
	 * EPSG:4326 writes latitude before longitude. A bound whose shape differs along the two axes is certified in
	 * that CRS, written in it or transformed into it, and every source its rectangles remove meets the bound,
	 * whichever side of the pair the sources are on.
	 */
	@Test
	public void latitudeFirstBoundPrunesOnlySourcesThatMeetIt() throws Exception {
		String epsg4326 = "<http://www.opengis.net/def/crs/EPSG/0/4326> ";
		LuceneGeoIndexer indexer = createIndexer("latitude-first", GeoSparqlConfig.PrefixTree.QUAD, 11);
		List<IndexGeometry> sources = new ArrayList<>();
		for (int column = 0; column < 36; column++) {
			for (int row = 0; row < 40; row++) {
				sources.add(geometry(String.format(Locale.ROOT, "%sPOINT(%.6f %.6f)", epsg4326,
						60 + (row + 0.5) / 40, 15 + (column + 0.5) / 36)));
			}
		}
		index(indexer, sources);
		// The square from 15 E 60 N to 16 E 61 N without its north-east corner, cut from 16 E 60.25 N to 15.5 E 61 N.
		IndexGeometry written = geometry(epsg4326 + "POLYGON((60 15,60 16,60.25 16,61 15.5,61 15,60 15))");
		IndexGeometry transformed = geometry("POLYGON((15 60,16 60,16 60.25,15.5 61,15 61,15 60))");

		for (IndexGeometry bound : List.of(written, transformed)) {
			for (boolean candidateIsSubject : bound == written ? new boolean[]{true, false} : new boolean[]{true}) {
				Set<Long> evaluated = uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject));
				Set<Long> definite = definite(indexer.getEnvelopeDisjointCandidates(bound, candidateIsSubject));

				assertTrue(evaluated.size() + " of " + sources.size(), evaluated.size() < sources.size() / 2);
				for (int id = 1; id <= sources.size(); id++) {
					if (!evaluated.contains((long) id) && !definite.contains((long) id)) {
						SourceGeometryLiteral source = sources.get(id - 1).sourceGeometryLiteral();
						assertFalse("removed source is disjoint: " + source.lexicalForm(),
								candidateIsSubject
										? GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(source, bound.sourceGeometryLiteral())
										: GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(bound.sourceGeometryLiteral(), source));
					}
				}
			}
		}
	}

	/** One Jena check takes longer the more points a bound has, so a bound beyond the point limit is not certified. */
	@Test
	public void boundWithTooManyPointsRetainsAllCandidates() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("many-points", GeoSparqlConfig.PrefixTree.QUAD, 11);
		List<IndexGeometry> sources = gridOverTheCircleEnvelope();
		index(indexer, sources);

		assertEquals(sources.size(),
				uncertain(indexer.getEnvelopeDisjointUncertainCandidates(geometry(circle(1100)), false)).size());
	}

	@Test
	public void interiorPruningRetainsTransformedAndWorldFallbackSources() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("interior-non-native", GeoSparqlConfig.PrefixTree.QUAD, 11);
		SourceGeometryLiteral fallback = SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(2000000 1000000)");
		List<IndexGeometry> sources = new ArrayList<>(List.of(geometry("POINT(1 1)"),
				geometry("<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(100000 100000)"),
				IndexGeometryFixtures.withIndexEnvelope(fallback, new Envelope(-180, 180, -90, 90),
						CandidateBoundsKind.WORLD_FALLBACK, "unrepresentable-rectangle")));
		// Enough CRS84 sources inside the bound to certify it.
		sources.addAll(Collections.nCopies(100, geometry("POINT(2 2)")));
		index(indexer, sources);

		assertEquals(Set.of(2L, 3L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(
				geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"), false)));
	}

	@Test
	public void ineligibleBoundsRetainTheCompleteCandidateQuery() throws Exception {
		try (MultiReader reader = new MultiReader()) {
			IndexSearcher searcher = new IndexSearcher(reader);
			Query candidates = new MatchAllDocsQuery();
			for (String wkt : List.of("POINT(1 1)", "POLYGON EMPTY",
					"MULTIPOLYGON(((0 0,2 0,2 2,0 0)))",
					"GEOMETRYCOLLECTION(POLYGON((0 0,2 0,2 2,0 0)))",
					"POLYGON((0 0,4 4,0 4,4 0,0 0))",
					"<http://www.opengis.net/def/crs/EPSG/0/3857> POLYGON((0 0,100000 0,0 100000,0 0))")) {
				assertSame(wkt, candidates, prune(geometry(wkt), candidates, searcher));
			}
		}
	}

	@Test
	public void unavailableCandidateCountsRetainTheCompleteQuery() throws Exception {
		try (MultiReader reader = new MultiReader()) {
			IndexSearcher searcher = new IndexSearcher(reader) {
				@Override
				public int count(Query query) throws IOException {
					throw new IOException("Candidate counts are unavailable");
				}
			};
			Query candidates = new MatchAllDocsQuery();
			assertSame(candidates, prune(
					geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"), candidates, searcher));
		}
	}

	@Test
	public void emptyCandidateCountsRetainTheCompleteQuery() throws Exception {
		try (MultiReader reader = new MultiReader()) {
			Query candidates = new MatchAllDocsQuery();
			assertSame(candidates, prune(
					geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"),
					candidates, new IndexSearcher(reader)));
		}
	}

	@Test
	public void adjacentDoubleCoordinatesAreSeparatedButEqualCoordinatesIntersect() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("adjacent", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(geometry("POINT(1 1)"),
				geometry("POINT(" + Math.nextDown(1.0) + " 1)"),
				geometry("POINT(" + Math.nextUp(1.0) + " 1)"),
				geometry("POINT(1 " + Math.nextDown(1.0) + ")"),
				geometry("POINT(1 " + Math.nextUp(1.0) + ")")));
		IndexGeometry bound = geometry("POINT(1 1)");

		assertEquals(Set.of(2L, 3L, 4L, 5L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(1L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
	}

	@Test
	public void signedZerosOnRectangleBoundaryAreContainedNonMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("zeroRectangle", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(geometry("POINT(0.0 0.0)"), geometry("POINT(-0.0 0.0)"),
				geometry("POINT(0.0 -0.0)"), geometry("POINT(-0.0 -0.0)")));
		IndexGeometry bound = geometry("POLYGON((0 0,0 1,1 1,1 0,0 0))");

		assertEquals(Set.of(), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
	}

	@Test
	public void signedZerosIntersectWhileNearbySeparatedSourcesAreDefinite() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("zero", GeoSparqlConfig.PrefixTree.QUAD, 11);
		List<IndexGeometry> zeros = List.of(geometry("POINT(0.0 0.0)"), geometry("POINT(-0.0 0.0)"),
				geometry("POINT(0.0 -0.0)"), geometry("POINT(-0.0 -0.0)"));
		index(indexer, List.of(zeros.get(0), zeros.get(1), zeros.get(2), zeros.get(3),
				geometry("POINT(0.00001 0)"), geometry("POINT(0 -0.00001)")));

		for (IndexGeometry bound : zeros) {
			assertEquals(Set.of(5L, 6L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
			assertEquals(Set.of(1L, 2L, 3L, 4L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
		}
	}

	/**
	 * A CRS84 subject classifies objects of another CRS by their CRS84 index envelopes, where envelopes that meet
	 * across the antimeridian are not separated.
	 */
	@Test
	public void envelopesMeetingAcrossTheAntimeridianAreUncertainInsteadOfDefiniteMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("antimeridian", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(
				transformed(new Envelope(-180, -170, -10, 10)),
				transformed(new Envelope(-180, -170, 20, 30)),
				transformed(new Envelope(-179, -170, -10, 10)),
				transformed(new Envelope(-180 + 1e-7, -170, -10, 10))));
		IndexGeometry bound = geometry("POLYGON((170 -10,180 -10,180 10,170 10,170 -10))");

		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, false)));
		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, false)));
		assertEquals(Set.of(1L, 4L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
	}

	/** A CRS84 subject does not separate objects of another CRS by longitude where both reach one polar cap. */
	@Test
	public void envelopesReachingTheSamePolarCapAreUncertainInsteadOfDefiniteMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("polar-cap", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(
				transformed(new Envelope(100, 110, -89.9999, -89.9998)),
				transformed(new Envelope(100, 110, -89.99, -89.98)),
				transformed(new Envelope(100, 110, 89.9995, 90))));
		IndexGeometry bound = geometry("POLYGON((10 -90,20 -90,20 -89.9995,10 -89.9995,10 -90))");

		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, false)));
		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, false)));
		assertEquals(Set.of(1L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound, false)));
	}

	/** Prunes the candidates in the bound's own CRS, where the bound is evaluated as it is. */
	private static Query prune(IndexGeometry bound, Query candidates, IndexSearcher searcher) {
		String crsUri = bound.sourceGeometryLiteral().effectiveCrsUri();
		return InteriorRectanglePruning.prune(bound.evaluationEnvelope(crsUri, true).orElseThrow(), candidates,
				LuceneGeoDocumentSchema.sourceCrsQuery(crsUri), LuceneGeoDocumentSchema::sourceEnvelopeWithinQuery,
				searcher);
	}

	private LuceneGeoIndexer createIndexer(String name, GeoSparqlConfig.PrefixTree tree, int precision)
			throws Exception {
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin(name, tree, precision));
		indexer.initialize();
		return indexer;
	}

	private GeoSparqlPlugin plugin(String name, GeoSparqlConfig.PrefixTree tree, int precision) throws Exception {
		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setPrefixTree(tree);
		config.setPrecision(precision);
		config.updateCurrentSettings();
		parent.setConfig(config);
		parent.setLogger(LoggerFactory.getLogger(LuceneDisjointEnvelopeLookupTest.class));
		parent.setDataDir(Files.createDirectories(tmpFolder.getRoot().toPath().resolve(name)).toFile());
		return parent;
	}

	private static void index(LuceneGeoIndexer indexer, List<IndexGeometry> geometries) throws Exception {
		indexer.begin();
		for (int i = 0; i < geometries.size(); i++) {
			indexer.indexGeometryList(i + 1L, id -> "geometry-" + id, List.of(geometries.get(i)));
		}
		indexer.commit();
		indexer.complete();
	}

	private static Set<Long> definite(CloseableIterator<EnvelopeDisjointCandidate> iterator) throws Exception {
		try (iterator) {
			Set<Long> ids = new HashSet<>();
			while (iterator.hasNext()) {
				ids.add(iterator.next().entityId());
			}
			return ids;
		}
	}

	private static Set<Long> uncertain(CloseableIterator<CandidateEntity> iterator) throws Exception {
		try (iterator) {
			Set<Long> ids = new HashSet<>();
			while (iterator.hasNext()) {
				ids.add(iterator.next().entityId());
			}
			return ids;
		}
	}

	private static IndexGeometry geometry(String wkt) {
		return TestIndexGeometries.fromWkt(wkt);
	}

	/** A circle of radius 4 around 8 8 with the given number of distinct points. */
	private static String circle(int points) {
		StringBuilder polygon = new StringBuilder("POLYGON((12 8");
		for (int i = 1; i < points; i++) {
			double angle = i * 2 * Math.PI / points;
			polygon.append(',').append(8 + 4 * Math.cos(angle)).append(' ').append(8 + 4 * Math.sin(angle));
		}
		return polygon.append(",12 8))").toString();
	}

	/** 6,400 points spread evenly over the envelope of the circle. */
	private static List<IndexGeometry> gridOverTheCircleEnvelope() {
		List<IndexGeometry> sources = new ArrayList<>();
		for (int column = 0; column < 80; column++) {
			for (int row = 0; row < 80; row++) {
				sources.add(geometry("POINT(" + (4.05 + column * 0.1) + " " + (4.05 + row * 0.1) + ")"));
			}
		}
		return sources;
	}

	private static IndexGeometry transformed(Envelope indexEnvelope) {
		return IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(0 0)"), indexEnvelope);
	}
}
