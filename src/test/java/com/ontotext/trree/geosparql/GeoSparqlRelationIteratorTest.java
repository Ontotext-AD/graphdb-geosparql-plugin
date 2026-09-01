package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import com.ontotext.trree.sdk.Entities;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GeoSparqlRelationIteratorTest {
	private static final long SUBJECT = 1L;
	private static final long OBJECT = 2L;
	private static final long PREDICATE = 3L;

	@Test
	public void candidateReturnedByIndexIsRejectedWhenExactRelationDoesNotHold() throws Exception {
		IndexGeometry polygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry boundaryPoint = indexGeometry("POINT(2 1)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/boundary-point"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/container"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(polygon),
				singletonList(boundaryPoint), singletonList(boundaryPoint));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_WITHIN, 0, PREDICATE, OBJECT, entities);
		try {
			assertFalse(iterator.next());
			assertEquals(1, ((ScriptedGeoSparqlIndexer) plugin.indexer).envelopeLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void objectBoundLookupReconsidersCandidateAfterEarlierBoundGeometryExactMiss() throws Exception {
		IndexGeometry missPolygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry hitPolygon = indexGeometry("POLYGON((10 10,10 12,12 12,12 10,10 10))");
		IndexGeometry boundaryPoint = indexGeometry("POINT(2 1)");
		IndexGeometry insidePoint = indexGeometry("POINT(11 11)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/thing"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/container"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(List.of(missPolygon, hitPolygon),
				singletonList(boundaryPoint), singletonList(insidePoint));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_WITHIN, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertEquals(SUBJECT, iterator.subject);
			assertEquals(OBJECT, iterator.object);
			assertFalse(iterator.next());
			assertEquals(2, ((ScriptedGeoSparqlIndexer) plugin.indexer).envelopeLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void unevaluableSourcePairDoesNotHideALaterTrueMatch() {
		com.ontotext.trree.geosparql.jena.JenaGeometryAdapter.initialize();
		IndexGeometry utm32n = indexGeometry(
				"<http://www.opengis.net/def/crs/EPSG/0/32632> POINT(500000 5200000)");
		IndexGeometry epsg4979 = indexGeometry(
				"<http://www.opengis.net/def/crs/EPSG/0/4979> POINT Z(-27.47 153.03 55)");

		try {
			GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
					utm32n.sourceGeometryLiteral(), epsg4979.sourceGeometryLiteral());
			fail("UTM 32N as subject against EPSG:4979 must be unevaluable");
		} catch (com.ontotext.trree.geosparql.jena.JenaGeoSparqlException expected) {
			// Jena cannot reduce the 3D CRS to this projected CRS for this pair.
		}
		assertTrue(GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(
				epsg4979.sourceGeometryLiteral(), epsg4979.sourceGeometryLiteral()));

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/mixed"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/wgs84-3d"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(epsg4979),
				List.of(utm32n, epsg4979), List.of());

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_INTERSECTS, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertEquals(SUBJECT, iterator.subject);
			assertEquals(OBJECT, iterator.object);
			assertFalse(iterator.next());
		} finally {
			iterator.close();
		}
	}

	@Test
	public void disjointLookupReturnsCandidateSideEmptyCollection() throws Exception {
		IndexGeometry polygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry emptyCollection = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION EMPTY"));

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/empty"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/polygon"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(polygon),
				List.of(), List.of());
		((ScriptedGeoSparqlIndexer) plugin.indexer).nonSpatialCandidates =
				candidateIterator(SUBJECT, singletonList(emptyCollection));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_DISJOINT, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertEquals(SUBJECT, iterator.subject);
			assertEquals(OBJECT, iterator.object);
			assertFalse(iterator.next());
			ScriptedGeoSparqlIndexer indexer = (ScriptedGeoSparqlIndexer) plugin.indexer;
			assertEquals(1, indexer.envelopeLookupCount);
			assertEquals(1, indexer.nonSpatialLookupCount);
			assertEquals(0, indexer.allEntitiesLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void envelopeProvenDisjointMatchNeedsNoCandidateSourceGeometry() throws Exception {
		IndexGeometry polygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry separatedPoint = indexGeometry("POINT(10 10)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/separated"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/polygon"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(polygon), List.of(), List.of());
		((ScriptedGeoSparqlIndexer) plugin.indexer).definiteCandidates = iterator(List.of(
				new EnvelopeDisjointCandidate(SUBJECT, separatedPoint.sourceTopologicalDimension())));

		GeoSparqlRelationIterator relationIterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_DISJOINT, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(relationIterator.next());
			assertEquals(SUBJECT, relationIterator.subject);
			assertEquals(OBJECT, relationIterator.object);
		} finally {
			relationIterator.close();
		}
	}

	@Test
	public void subjectBoundLookupChecksEveryDistinctCandidateSourceAndEmitsPairOnce() throws Exception {
		IndexGeometry boundPoint = indexGeometry("POINT(11 11)");
		IndexGeometry missPolygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry hitPolygon = indexGeometry("POLYGON((10 10,10 12,12 12,12 10,10 10))");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createLiteral(
				"POINT(11 11)", GeoConstants.GEO_WKT_LITERAL));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/container"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(OBJECT, singletonList(boundPoint),
				List.of(missPolygon, hitPolygon), List.of(missPolygon, hitPolygon));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_WITHIN, SUBJECT, PREDICATE, 0, entities);
		try {
			assertTrue(iterator.next());
			assertEquals(SUBJECT, iterator.subject);
			assertEquals(OBJECT, iterator.object);
			assertFalse(iterator.next());
			assertEquals(1, ((ScriptedGeoSparqlIndexer) plugin.indexer).envelopeLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void nonDisjointCandidateUsesMatchedSourceGeometryLiteralsWithoutEntityReload() {
		IndexGeometry boundPolygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry candidatePoint = indexGeometry("POINT(1 1)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/point"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/polygon"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		ScriptedGeoSparqlIndexer indexer = new ScriptedGeoSparqlIndexer(
				singletonList(boundPolygon), singletonList(candidatePoint), List.of());
		indexer.disjointCleanupCandidateGeometries = singletonList(candidatePoint);
		plugin.indexer = indexer;

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_INTERSECTS, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertFalse(iterator.next());
			assertEquals(0, indexer.disjointCleanupLookupCount);
			assertEquals(0, indexer.candidateSourceLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void boundEmptySourceUsesOneFullScanForDisjointRelation() throws Exception {
		IndexGeometry polygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry emptyCollection = emptyCollection();

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/polygon"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/empty"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(emptyCollection),
				singletonList(polygon), singletonList(polygon),
				candidateIterator(SUBJECT, singletonList(polygon)));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_DISJOINT, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertFalse(iterator.next());
			ScriptedGeoSparqlIndexer indexer = (ScriptedGeoSparqlIndexer) plugin.indexer;
			assertEquals(1, indexer.allEntitiesLookupCount);
			assertEquals(0, indexer.envelopeLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void boundNonEmptyGenericCollectionUsesPartitionedDisjointLookup() throws Exception {
		IndexGeometry polygon = indexGeometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		IndexGeometry collection = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION(POINT(10 10),POINT(20 20))"));
		IndexGeometry ordinarySource = indexGeometry("POINT(30 30)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/polygon"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/collection"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(List.of(collection, ordinarySource),
				singletonList(polygon), singletonList(polygon),
				candidateIterator(SUBJECT, singletonList(polygon)));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_DISJOINT, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertFalse(iterator.next());
			ScriptedGeoSparqlIndexer indexer = (ScriptedGeoSparqlIndexer) plugin.indexer;
			assertEquals(0, indexer.allEntitiesLookupCount);
			assertEquals(2, indexer.envelopeLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void boundGenericCollectionUsesOneEnvelopeLookupForNonDisjointRelation()
			throws Exception {
		IndexGeometry collection = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION(POINT(0 0),POINT(1 1))"));

		FakeEntities entities = new FakeEntities();
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/collection"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(singletonList(collection), List.of(), List.of());

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_EQUALS, 0, PREDICATE, OBJECT, entities);
		try {
			assertFalse(iterator.next());
			ScriptedGeoSparqlIndexer indexer = (ScriptedGeoSparqlIndexer) plugin.indexer;
			assertEquals(1, indexer.envelopeLookupCount);
			assertEquals(0, indexer.allEntitiesLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void nonDisjointLookupSkipsEmptySourceAndUsesSpatialSource() throws Exception {
		IndexGeometry emptyCollection = emptyCollection();
		IndexGeometry polygon = indexGeometry("POLYGON((10 10,10 12,12 12,12 10,10 10))");
		IndexGeometry insidePoint = indexGeometry("POINT(11 11)");

		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/point"));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createIRI("http://example.com/container"));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));
		plugin.indexer = new ScriptedGeoSparqlIndexer(List.of(emptyCollection, polygon),
				singletonList(insidePoint), singletonList(insidePoint));

		GeoSparqlRelationIterator iterator = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_WITHIN, 0, PREDICATE, OBJECT, entities);
		try {
			assertTrue(iterator.next());
			assertFalse(iterator.next());
			ScriptedGeoSparqlIndexer indexer = (ScriptedGeoSparqlIndexer) plugin.indexer;
			assertEquals(1, indexer.envelopeLookupCount);
			assertEquals(0, indexer.allEntitiesLookupCount);
		} finally {
			iterator.close();
		}
	}

	@Test
	public void boundCrossesPropertyRelationPermitsPointLineButRejectsLinePoint() {
		FakeEntities entities = new FakeEntities();
		entities.add(SUBJECT, SimpleValueFactory.getInstance().createLiteral(
				"GEOMETRYCOLLECTION(LINESTRING(0 0,2 0))", GeoConstants.GEO_WKT_LITERAL));
		entities.add(OBJECT, SimpleValueFactory.getInstance().createLiteral(
				"GEOMETRYCOLLECTION(MULTIPOINT((1 0),(1 1)))", GeoConstants.GEO_WKT_LITERAL));

		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlRelationIteratorTest.class));

		GeoSparqlRelationIterator rejected = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_CROSSES, SUBJECT, PREDICATE, OBJECT, entities);
		try {
			assertFalse(rejected.next());
		} finally {
			rejected.close();
		}

		GeoSparqlRelationIterator permitted = new GeoSparqlRelationIterator(plugin,
				GeoSparqlPropertyRelation.SF_CROSSES, OBJECT, PREDICATE, SUBJECT, entities);
		try {
			assertTrue(permitted.next());
			assertFalse(permitted.next());
		} finally {
			permitted.close();
		}
	}

	private static IndexGeometry indexGeometry(String wkt) {
		return TestIndexGeometries.fromWkt(wkt);
	}

	private static IndexGeometry emptyCollection() {
		return IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION EMPTY"));
	}

	private static final class ScriptedGeoSparqlIndexer implements GeoSparqlIndexer {
		private final List<IndexGeometry> boundObjectGeometries;
		private final List<IndexGeometry> firstCandidateGeometries;
		private final List<IndexGeometry> secondCandidateGeometries;
		private final CloseableIterator<CandidateEntity> allCandidates;
		private List<IndexGeometry> disjointCleanupCandidateGeometries = List.of();
		private CloseableIterator<CandidateEntity> nonSpatialCandidates = emptyIterator();
		private CloseableIterator<EnvelopeDisjointCandidate> definiteCandidates = emptyIterator();
		private final long candidateEntityId;
		private int envelopeLookupCount;
		private int disjointCleanupLookupCount;
		private int candidateSourceLookupCount;
		private int nonSpatialLookupCount;
		private int allEntitiesLookupCount;

		private ScriptedGeoSparqlIndexer(List<IndexGeometry> boundObjectGeometries,
										 List<IndexGeometry> firstCandidateGeometries,
										 List<IndexGeometry> secondCandidateGeometries) {
			this(boundObjectGeometries, firstCandidateGeometries, secondCandidateGeometries,
					emptyIterator(), SUBJECT);
		}

		private ScriptedGeoSparqlIndexer(long candidateEntityId,
				List<IndexGeometry> boundObjectGeometries,
				List<IndexGeometry> firstCandidateGeometries,
				List<IndexGeometry> secondCandidateGeometries) {
			this(boundObjectGeometries, firstCandidateGeometries, secondCandidateGeometries,
					emptyIterator(), candidateEntityId);
		}

		private ScriptedGeoSparqlIndexer(List<IndexGeometry> boundObjectGeometries,
										 List<IndexGeometry> firstCandidateGeometries,
										 List<IndexGeometry> secondCandidateGeometries,
										 CloseableIterator<CandidateEntity> allCandidates) {
			this(boundObjectGeometries, firstCandidateGeometries, secondCandidateGeometries,
					allCandidates, SUBJECT);
		}

		private ScriptedGeoSparqlIndexer(List<IndexGeometry> boundObjectGeometries,
										 List<IndexGeometry> firstCandidateGeometries,
										 List<IndexGeometry> secondCandidateGeometries,
										 CloseableIterator<CandidateEntity> allCandidates,
										 long candidateEntityId) {
			this.boundObjectGeometries = boundObjectGeometries;
			this.firstCandidateGeometries = firstCandidateGeometries;
			this.secondCandidateGeometries = secondCandidateGeometries;
			this.allCandidates = allCandidates;
			this.candidateEntityId = candidateEntityId;
		}

		@Override
		public CloseableIterator<CandidateEntity> getEnvelopeIntersections(
				IndexGeometry boundSourceIndexGeometry) {
			envelopeLookupCount++;
			if (envelopeLookupCount == 1) {
				return firstCandidateGeometries.isEmpty()
						? emptyIterator() : candidateIterator(candidateEntityId, firstCandidateGeometries);
			}
			if (envelopeLookupCount == 2) {
				return secondCandidateGeometries.isEmpty()
						? emptyIterator() : candidateIterator(candidateEntityId, secondCandidateGeometries);
			}
			return emptyIterator();
		}

		@Override
		public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
				IndexGeometry boundSourceIndexGeometry) {
			return definiteCandidates;
		}

		@Override
		public CloseableIterator<CandidateEntity> getNonSpatialCandidates() {
			nonSpatialLookupCount++;
			return nonSpatialCandidates;
		}

		@Override
		public CloseableIterator<CandidateEntity> getAllEntities() {
			allEntitiesLookupCount++;
			return allCandidates;
		}

		@Override
		public CloseableIterator<CandidateEntity> getDisjointTransformCleanupCandidates(
				IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
			disjointCleanupLookupCount++;
			return disjointCleanupCandidateGeometries.isEmpty()
					? emptyIterator() : candidateIterator(candidateEntityId, disjointCleanupCandidateGeometries);
		}

		@Override
		public CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsFor(long subject) {
			if (subject == candidateEntityId) {
				candidateSourceLookupCount++;
				LinkedHashSet<SourceGeometryLiteral> sources = new LinkedHashSet<>();
				for (IndexGeometry geometry : firstCandidateGeometries) {
					sources.add(geometry.sourceGeometryLiteral());
				}
				for (IndexGeometry geometry : secondCandidateGeometries) {
					sources.add(geometry.sourceGeometryLiteral());
				}
				for (IndexGeometry geometry : disjointCleanupCandidateGeometries) {
					sources.add(geometry.sourceGeometryLiteral());
				}
				return iterator(new ArrayList<>(sources));
			}
			if (subject == OBJECT) {
				List<SourceGeometryLiteral> sources = new ArrayList<>();
				for (IndexGeometry geometry : boundObjectGeometries) {
					sources.add(geometry.sourceGeometryLiteral());
				}
				return iterator(sources);
			}
			throw new UnsupportedOperationException();
		}

		@Override
		public void initialize() throws Exception {
		}

		@Override
		public void indexGeometryList(long subject, Function<Long, String> subjectMapper,
									  List<IndexGeometry> geometries) {
		}

		@Override
		public void initSettings() {
		}

		@Override
		public void begin() throws Exception {
		}

		@Override
		public void commit() throws Exception {
		}

		@Override
		public void rollback() throws Exception {
		}

		@Override
		public void appendGeometry(long subject, Function<Long, String> subjectMapper, IndexGeometry geometry) {
		}

		@Override
		public void freshIndex() throws Exception {
		}
	}

	private static CloseableIterator<CandidateEntity> candidateIterator(long entityId,
			List<IndexGeometry> geometries) {
		LinkedHashSet<SourceGeometryLiteral> sources = new LinkedHashSet<>();
		for (IndexGeometry geometry : geometries) {
			sources.add(geometry.sourceGeometryLiteral());
		}
		return iterator(List.of(new CandidateEntity(entityId, sources)));
	}

	private static <T> CloseableIterator<T> emptyIterator() {
		return iterator(List.of());
	}

	private static <T> CloseableIterator<T> iterator(List<T> values) {
		Iterator<T> iterator = values.iterator();
		return new CloseableIterator<T>() {
			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}

			@Override
			public T next() {
				return iterator.next();
			}

			@Override
			public void close() throws IOException {
			}
		};
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
