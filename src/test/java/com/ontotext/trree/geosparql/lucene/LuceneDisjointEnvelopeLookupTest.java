package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

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
				assertEquals(Set.of(2L), definite(indexer.getEnvelopeDisjointCandidates(bound)));
				assertEquals(Set.of(1L, 3L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
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
		assertEquals(Set.of(), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
		assertEquals(Set.of(5L), uncertain(indexer.getNonSpatialCandidates()));
	}

	@Test
	public void nonRectangularBoundPrunesContainedSourcesAndRetainsDisjointCandidates() throws Exception {
		for (GeoSparqlConfig.PrefixTree tree : GeoSparqlConfig.PrefixTree.values()) {
			LuceneGeoIndexer indexer = createIndexer("interior-rectangles-" + tree, tree, 1);
			index(indexer, List.of(geometry("POINT(1 1)"), geometry("POINT(15 15)"),
					geometry("POINT(0 8)"), geometry("POINT(-1 -1)"), geometry("POINT EMPTY")));
			IndexGeometry bound = geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))");

			assertEquals(Set.of(2L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
			assertEquals(Set.of(4L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
			assertEquals(Set.of(5L), uncertain(indexer.getNonSpatialCandidates()));
		}
	}

	@Test
	public void interiorPruningRetainsAllCandidatesWhenAtMostHalfCanBeExcluded() throws Exception {
		for (int outsideCount : new int[]{1, 2}) {
			LuceneGeoIndexer indexer = createIndexer("low-reduction-" + outsideCount,
					GeoSparqlConfig.PrefixTree.QUAD, 11);
			List<IndexGeometry> sources = new ArrayList<>();
			sources.add(geometry("POINT(1 1)"));
			for (int i = 0; i < outsideCount; i++) {
				sources.add(geometry("POINT(15 15)"));
			}
			index(indexer, sources);
			Set<Long> expected = outsideCount == 1 ? Set.of(1L, 2L) : Set.of(1L, 2L, 3L);
			assertEquals(expected, uncertain(indexer.getEnvelopeDisjointUncertainCandidates(
					geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"))));
		}
	}

	@Test
	public void interiorPruningRetainsTransformedAndWorldFallbackSources() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("interior-non-native", GeoSparqlConfig.PrefixTree.QUAD, 11);
		SourceGeometryLiteral fallback = SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(2000000 1000000)");
		index(indexer, List.of(geometry("POINT(1 1)"),
				geometry("<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(100000 100000)"),
				IndexGeometryFixtures.withIndexEnvelope(fallback, new Envelope(-180, 180, -90, 90),
						CandidateBoundsKind.WORLD_FALLBACK, "unrepresentable-rectangle")));

		assertEquals(Set.of(2L, 3L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(
				geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"))));
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
				assertSame(wkt, candidates, NativeCrs84DisjointPruning.prune(geometry(wkt), candidates, searcher));
			}
		}
	}

	@Test
	public void complexPolygonBoundsRetainTheCompleteCandidateQuery() throws Exception {
		StringBuilder polygon = new StringBuilder("POLYGON((12 8");
		for (int i = 1; i < 300; i++) {
			double angle = i * 2 * Math.PI / 300;
			polygon.append(',').append(8 + 4 * Math.cos(angle)).append(' ').append(8 + 4 * Math.sin(angle));
		}
		polygon.append(",12 8))");
		Query candidates = new MatchAllDocsQuery();
		try (MultiReader reader = new MultiReader()) {
			assertSame(candidates, NativeCrs84DisjointPruning.prune(
					geometry(polygon.toString()), candidates, new IndexSearcher(reader)));
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
			assertSame(candidates, NativeCrs84DisjointPruning.prune(
					geometry("POLYGON((0 0,16 0,16 8,8 16,0 16,0 0))"), candidates, searcher));
		}
	}

	@Test
	public void emptyCandidateCountsRetainTheCompleteQuery() throws Exception {
		try (MultiReader reader = new MultiReader()) {
			Query candidates = new MatchAllDocsQuery();
			assertSame(candidates, NativeCrs84DisjointPruning.prune(
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
		assertEquals(Set.of(1L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
	}

	@Test
	public void signedZerosOnRectangleBoundaryAreContainedNonMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexer("zeroRectangle", GeoSparqlConfig.PrefixTree.QUAD, 11);
		index(indexer, List.of(geometry("POINT(0.0 0.0)"), geometry("POINT(-0.0 0.0)"),
				geometry("POINT(0.0 -0.0)"), geometry("POINT(-0.0 -0.0)")));
		IndexGeometry bound = geometry("POLYGON((0 0,0 1,1 1,1 0,0 0))");

		assertEquals(Set.of(), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
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
			assertEquals(Set.of(1L, 2L, 3L, 4L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
		}
	}

	@Test
	public void envelopesMeetingAcrossTheAntimeridianAreUncertainInsteadOfDefiniteMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexEnvelopeOnlyIndexer("antimeridian");
		index(indexer, List.of(
				transformed(new Envelope(-180, -170, -10, 10)),
				transformed(new Envelope(-180, -170, 20, 30)),
				transformed(new Envelope(-179, -170, -10, 10)),
				transformed(new Envelope(-180 + 1e-7, -170, -10, 10))));
		IndexGeometry bound = transformed(new Envelope(170, 180, -10, 10));

		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound)));
		assertEquals(Set.of(1L, 4L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
	}

	@Test
	public void envelopesReachingTheSamePolarCapAreUncertainInsteadOfDefiniteMatches() throws Exception {
		LuceneGeoIndexer indexer = createIndexEnvelopeOnlyIndexer("polar-cap");
		index(indexer, List.of(
				transformed(new Envelope(100, 110, -89.9999, -89.9998)),
				transformed(new Envelope(100, 110, -89.99, -89.98)),
				transformed(new Envelope(100, 110, 89.9995, 90))));
		IndexGeometry bound = transformed(new Envelope(10, 20, -90, -89.9995));

		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
		assertEquals(Set.of(2L, 3L), definite(indexer.getEnvelopeDisjointCandidates(bound)));
		assertEquals(Set.of(1L), uncertain(indexer.getEnvelopeDisjointUncertainCandidates(bound)));
	}

	private LuceneGeoIndexer createIndexer(String name, GeoSparqlConfig.PrefixTree tree, int precision)
			throws Exception {
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin(name, tree, precision));
		indexer.initialize();
		return indexer;
	}

	/** An index whose sources are classified by CRS84 index envelopes, where the longitude rules apply. */
	private LuceneGeoIndexer createIndexEnvelopeOnlyIndexer(String name) throws Exception {
		LuceneGeoIndexer indexer = new IndexEnvelopeOnlyIndexer(plugin(name, GeoSparqlConfig.PrefixTree.QUAD, 11));
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

	private static IndexGeometry transformed(Envelope indexEnvelope) {
		return IndexGeometryFixtures.withIndexEnvelope(SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(0 0)"), indexEnvelope);
	}
}
