package com.ontotext.trree.geosparql;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.sdk.Entities;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.locationtech.jts.geom.Envelope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GeoSparqlDisjointDifferentialTest {
	private static final long BOUND = 100L;
	private static final long PREDICATE = 200L;
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";

	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void partitionedDisjointMatchesExhaustiveReferenceForAllFamiliesAndBindingDirections() throws Exception {
		Fixture fixture = createFixture();

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT,
				GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(relation + " object-bound",
					runReference(fixture, relation, 0, BOUND),
					runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(relation + " subject-bound",
					runReference(fixture, relation, BOUND, 0),
					runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void interiorRectanglePruningMatchesExhaustiveEvaluationAcrossPolygonShapes() throws Exception {
		List<String> bounds = List.of(
				"POLYGON((0 0,6 0,0 6,0 0))",
				"POLYGON((0 0,6 0,6 2,2 2,2 6,0 6,0 0))",
				"POLYGON((0 0,6 0,6 6,0 6,0 0),(2 2,2 4,4 4,4 2,2 2))",
				"POLYGON((3 0,6 3,3 6,0 3,3 0))",
				"POLYGON((1 1,1.000000000000001 1,1.000000000000001 6,1 6,1 1))");
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		List<String> sourceWkts = new ArrayList<>(List.of(
				"POINT(0 0)", "POINT(-0.0 0.0)", "POINT(0.0 -0.0)", "POINT(3 3)", "POINT(6 0)",
				"POINT EMPTY", "LINESTRING EMPTY", "POLYGON EMPTY", "GEOMETRYCOLLECTION EMPTY",
				"LINESTRING(-1 3,7 3)", "LINESTRING(3 -1,3 7)",
				"POLYGON((0 0,6 0,6 6,0 6,0 0))", "POLYGON((2.5 2.5,3.5 2.5,3.5 3.5,2.5 3.5,2.5 2.5))",
				"MULTIPOINT((0 0),(8 8))", "GEOMETRYCOLLECTION(POINT(1 1),LINESTRING(3 -1,3 7))",
				"MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)),((8 8,9 8,9 9,8 9,8 8)))",
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(100000 100000)"));
		for (double x : List.of(Math.nextDown(2.0), 2.0, Math.nextUp(2.0),
				Math.nextDown(4.0), 4.0, Math.nextUp(4.0))) {
			sourceWkts.add("POINT(" + x + " " + (6 - x) + ")");
		}
		Random random = new Random(4427);
		for (int i = 0; i < 40; i++) {
			sourceWkts.add("POINT(" + (-1 + random.nextDouble() * 12) + " "
					+ (-1 + random.nextDouble() * 12) + ")");
		}
		for (int i = 0; i < sourceWkts.size(); i++) {
			sources.put(i + 1L, geometries(sourceWkts.get(i)));
		}
		sources.put(80L, geometries("POINT(1 1)", "POINT(5 5)"));
		SourceGeometryLiteral fallback = SourceGeometryLiteral.fromWkt(
				"<http://www.opengis.net/def/crs/EPSG/0/3857> POINT(2000000 1000000)");
		sources.put(81L, List.of(IndexGeometryFixtures.withIndexEnvelope(fallback,
				new Envelope(-180, 180, -90, 90), CandidateBoundsKind.WORLD_FALLBACK,
				"unrepresentable-rectangle")));
		for (int i = 0; i < bounds.size(); i++) {
			sources.put(BOUND, geometries(bounds.get(i)));
			Fixture fixture = createFixture("interior-rectangle-shape-" + i, sources);
			for (GeoSparqlPropertyRelation relation : List.of(GeoSparqlPropertyRelation.SF_DISJOINT,
					GeoSparqlPropertyRelation.EH_DISJOINT, GeoSparqlPropertyRelation.RCC8_DC)) {
				assertEquals(relation + " object-bound shape " + i,
						runReference(fixture, relation, 0, BOUND), runPartitioned(fixture, relation, 0, BOUND));
				assertEquals(relation + " subject-bound shape " + i,
						runReference(fixture, relation, BOUND, 0), runPartitioned(fixture, relation, BOUND, 0));
			}
		}
	}

	/**
	 * At coordinates near 10⁻¹⁶², products of coordinate differences underflow and JTS can certify a rectangle
	 * within a polygon that reaches outside it, or find that a polygon fills its envelope when it does not. Each
	 * bound is scaled by a power of two so that its shape is exact. The L-shaped bound is the one of the shape test
	 * above; its sources are a grid over the quarter of its envelope that holds its corner, mostly inside the bound
	 * so that interior pruning runs, and a ninth of the grid lies in the missing corner. The second bound does not
	 * fill its envelope, and its sources are a grid over that envelope.
	 */
	@Test
	public void disjointLookupMatchesExhaustiveEvaluationAtUnderflowingScale() throws Exception {
		assertMatchesExhaustiveEvaluationAtUnderflowingScale("underflowing-l-shape",
				new int[] { 0, 0, 6, 0, 6, 2, 2, 2, 2, 6, 0, 6, 0, 0 }, new Envelope(0, 3, 0, 3));
		assertMatchesExhaustiveEvaluationAtUnderflowingScale("underflowing-hexagon",
				new int[] { 9, 13, 1, 10, 0, 6, 3, 4, 8, 7, 11, 5, 9, 13 }, new Envelope(0, 11, 4, 13));
	}

	private void assertMatchesExhaustiveEvaluationAtUnderflowingScale(String name, int[] boundCoordinates,
			Envelope grid) throws Exception {
		int exponent = -540;
		StringBuilder ring = new StringBuilder();
		for (int i = 0; i < boundCoordinates.length; i += 2) {
			ring.append(i == 0 ? "" : ",").append(scaled(boundCoordinates[i], boundCoordinates[i + 1], exponent));
		}
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries("POLYGON((" + ring + "))"));
		long id = BOUND + 1;
		for (int x = 0; x <= 12; x++) {
			for (int y = 0; y <= 12; y++) {
				sources.put(id++, geometries("POINT(" + scaled(grid.getMinX() + grid.getWidth() * x / 12,
						grid.getMinY() + grid.getHeight() * y / 12, exponent) + ")"));
			}
		}
		Fixture fixture = createFixture(name, sources);
		for (GeoSparqlPropertyRelation relation : List.of(GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT, GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(name + " " + relation + " object-bound",
					runReference(fixture, relation, 0, BOUND), runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(name + " " + relation + " subject-bound",
					runReference(fixture, relation, BOUND, 0), runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	private static String scaled(double x, double y, int exponent) {
		return Math.scalb(x, exponent) + " " + Math.scalb(y, exponent);
	}

	@Test
	public void interiorPruningIsLocalToEachBoundAndEachCandidateSource() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries("POLYGON((0 0,6 0,0 6,0 0))",
				"POLYGON((10 10,16 10,10 16,10 10))"));
		sources.put(1L, geometries("POINT(1 1)", "POINT(5 5)"));
		sources.put(2L, geometries("POLYGON((1 1,2 1,1 2,1 1))",
				"POLYGON((11 11,12 11,11 12,11 11))"));
		sources.put(3L, geometries("POINT(11 11)"));
		Fixture fixture = createFixture("interior-rectangles-multiple-sources", sources);
		for (GeoSparqlPropertyRelation relation : List.of(GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT, GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(runReference(fixture, relation, 0, BOUND), runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(runReference(fixture, relation, BOUND, 0), runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void mixedEmptyAndSpatialBoundSourcesMatchExhaustiveReference() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries(
				"POLYGON((0 0,0 2,2 2,2 0,0 0))",
				"GEOMETRYCOLLECTION EMPTY"));
		sources.put(1L, geometries("POINT(1 1)"));
		sources.put(2L, geometries("POINT(10 10)"));
		sources.put(3L, geometries("POINT EMPTY"));
		sources.put(4L, geometries("GEOMETRYCOLLECTION EMPTY"));
		Fixture fixture = createFixture("mixed-empty-bound", sources);

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT,
				GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(relation + " mixed empty object-bound",
					runReference(fixture, relation, 0, BOUND),
					runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(relation + " mixed empty subject-bound",
					runReference(fixture, relation, BOUND, 0),
					runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void projectedLineAndOnLinePointAreNotEmittedAsDefiniteDisjointMatches() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries(
				"<" + EPSG_32634 + "> LINESTRING(200000 7000000, 800000 7000000)"));
		sources.put(1L, geometries("<" + EPSG_32634 + "> POINT(500000 7000000)"));
		Fixture fixture = createFixture("projected-line-midpoint-disjoint", sources);

		assertFalse(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				sources.get(BOUND).get(0).sourceGeometryLiteral(),
				sources.get(1L).get(0).sourceGeometryLiteral()));
		assertEquals(Set.of(),
				runPartitioned(fixture, GeoSparqlPropertyRelation.SF_DISJOINT, 0, BOUND));
		assertEquals(Set.of(),
				runPartitioned(fixture, GeoSparqlPropertyRelation.SF_DISJOINT, BOUND, 0));
		assertEquals(runReference(fixture, GeoSparqlPropertyRelation.SF_DISJOINT, 0, BOUND),
				runPartitioned(fixture, GeoSparqlPropertyRelation.SF_DISJOINT, 0, BOUND));
	}

	@Test
	public void separatedCrossCrsSourcesMatchExhaustiveReferenceInBothBindingDirections() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries("POINT(20 50)"));
		sources.put(1L, geometries("<" + EPSG_32634 + "> POINT(799997.80 4589779.63)"));
		Fixture fixture = createFixture("cross-crs-disjoint", sources);

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT)) {
			assertEquals(Set.of(1L), runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(runReference(fixture, relation, BOUND, 0),
					runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void multipleNonEmptyBoundSourcesPreserveExistentialEntitySemantics() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries(
				"POLYGON((0 0,0 2,2 2,2 0,0 0))",
				"POLYGON((10 10,10 12,12 12,12 10,10 10))"));
		sources.put(1L, geometries("POLYGON((1 1,1 3,3 3,3 1,1 1))"));
		sources.put(2L, geometries(
				"POLYGON((1 1,1 2,2 2,2 1,1 1))",
				"POLYGON((11 11,11 12,12 12,12 11,11 11))"));
		sources.put(3L, geometries("POLYGON((20 20,20 22,22 22,22 20,20 20))"));
		Fixture fixture = createFixture("multiple-bound-sources", sources);

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT,
				GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(relation + " multiple bound object-bound",
					runReference(fixture, relation, 0, BOUND),
					runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(relation + " multiple bound subject-bound",
					runReference(fixture, relation, BOUND, 0),
					runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void rectangularContainmentPruningMatchesExhaustiveReferenceForAllFamiliesAndBindingDirections()
			throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries("POLYGON((0 0,0 10,10 10,10 0,0 0))"));
		sources.put(1L, geometries("POINT(5 5)"));
		sources.put(2L, geometries("POINT(0 5)"));
		sources.put(3L, geometries("POLYGON((8 8,8 12,12 12,12 8,8 8))"));
		sources.put(4L, geometries("POLYGON((2 2,2 4,4 4,4 2,2 2))"));
		sources.put(5L, geometries("POINT(20 20)"));
		sources.put(6L, geometries("POLYGON((20 20,20 22,22 22,22 20,20 20))"));
		Fixture fixture = createFixture("rectangular-containment-pruning", sources);

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT,
				GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(relation + " rectangular object-bound",
					runReference(fixture, relation, 0, BOUND),
					runPartitioned(fixture, relation, 0, BOUND));
			assertEquals(relation + " rectangular subject-bound",
					runReference(fixture, relation, BOUND, 0),
					runPartitioned(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void partitionedTraversalEmitsOneRowWhenEntityMatchesAcrossCandidatePhases() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries(
				"POLYGON((0 0,0 10,10 10,10 0,0 0),(4 4,6 4,6 6,4 6,4 4))"));
		sources.put(1L, geometries(
				"POLYGON((20 20,20 22,22 22,22 20,20 20))",
				"POLYGON((4.5 4.5,4.5 5.5,5.5 5.5,5.5 4.5,4.5 4.5))",
				"GEOMETRYCOLLECTION EMPTY"));
		Fixture fixture = createFixture("candidate-phases-deduplication", sources);

		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.EH_DISJOINT,
				GeoSparqlPropertyRelation.RCC8_DC)) {
			assertEquals(relation + " object-bound rows",
					List.of(1L), runPartitionedRows(fixture, relation, 0, BOUND));
			assertEquals(relation + " subject-bound rows",
					List.of(1L), runPartitionedRows(fixture, relation, BOUND, 0));
		}
	}

	@Test
	public void rcc8ClassifiesEachSourceDocumentBeforeEntityDeduplication() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries("POLYGON((0 0,0 2,2 2,2 0,0 0))"));
		sources.put(1L, geometries(
				"POINT(10 10)",
				"POLYGON((20 20,20 22,22 22,22 20,20 20))"));
		Fixture fixture = createFixture("rcc8-source-document-classification", sources);

		assertEquals(List.of(1L),
				runPartitionedRows(fixture, GeoSparqlPropertyRelation.RCC8_DC, 0, BOUND));
		assertEquals(List.of(1L),
				runPartitionedRows(fixture, GeoSparqlPropertyRelation.RCC8_DC, BOUND, 0));
	}

	private Fixture createFixture() throws Exception {
		Map<Long, List<IndexGeometry>> sources = new HashMap<>();
		sources.put(BOUND, geometries(
				"POLYGON((0 0,0 10,10 10,10 0,0 0),(4 4,6 4,6 6,4 6,4 4))"));
		sources.put(1L, geometries("POINT(20 20)"));
		sources.put(2L, geometries("POINT(5 5)"));
		sources.put(3L, geometries("POINT(1 1)"));
		sources.put(4L, geometries("GEOMETRYCOLLECTION EMPTY"));
		sources.put(5L, geometries("POINT EMPTY"));
		sources.put(6L, geometries("POLYGON((20 20,20 22,22 22,22 20,20 20))"));
		sources.put(7L, geometries(
				"POLYGON((1 1,1 2,2 2,2 1,1 1))",
				"POLYGON((30 30,30 32,32 32,32 30,30 30))"));
		sources.put(8L, geometries("GEOMETRYCOLLECTION(POINT(30 30),LINESTRING(31 31,32 32))"));
		sources.put(9L, geometries("POLYGON((10 2,10 4,12 4,12 2,10 2))"));
		return createFixture("disjoint-differential", sources);
	}

	private Fixture createFixture(String directoryName, Map<Long, List<IndexGeometry>> sources)
			throws Exception {
		Path dataDir = tmpFolder.getRoot().toPath().resolve(directoryName);
		Files.createDirectories(dataDir);

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.updateCurrentSettings();
		plugin.setConfig(config);
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlDisjointDifferentialTest.class));
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

	private Set<Long> runPartitioned(Fixture fixture, GeoSparqlPropertyRelation relation,
			long subject, long object) {
		List<Long> rows = runPartitionedRows(fixture, relation, subject, object);
		Set<Long> entityIds = new LinkedHashSet<>(rows);
		assertEquals(relation + " must emit each entity pair once", entityIds.size(), rows.size());
		return entityIds;
	}

	private List<Long> runPartitionedRows(Fixture fixture, GeoSparqlPropertyRelation relation,
			long subject, long object) {
		return collectCandidateIds(new GeoSparqlRelationIterator(fixture.plugin, relation,
				subject, PREDICATE, object, fixture.entities), subject);
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
						? relationHolds(relation, candidateSources, boundSources)
						: relationHolds(relation, boundSources, candidateSources);
				if (holds) {
					matchingEntityIds.add(candidate.entityId());
				}
			}
		} finally {
			candidates.close();
		}
		return matchingEntityIds;
	}

	private boolean relationHolds(GeoSparqlPropertyRelation relation,
			List<SourceGeometryLiteral> subjectSources, List<SourceGeometryLiteral> objectSources) {
		for (SourceGeometryLiteral subjectSource : subjectSources) {
			for (SourceGeometryLiteral objectSource : objectSources) {
				if (relation.evaluate(subjectSource, objectSource)) {
					return true;
				}
			}
		}
		return false;
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

	private record Fixture(GeoSparqlPlugin plugin, FakeEntities entities,
			Map<Long, List<IndexGeometry>> sources) {
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
