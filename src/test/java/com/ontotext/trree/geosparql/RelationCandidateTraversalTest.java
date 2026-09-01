package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.junit.Test;
import org.locationtech.jts.geom.Dimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RelationCandidateTraversalTest {
	private static final Logger LOG = LoggerFactory.getLogger(RelationCandidateTraversalTest.class);

	@Test
	public void envelopeDisjointCandidateRequiresPositiveEntityId() {
		assertThrows(IllegalArgumentException.class, () -> new EnvelopeDisjointCandidate(0L, Dimension.P));
		assertThrows(IllegalArgumentException.class, () -> new EnvelopeDisjointCandidate(-1L, Dimension.P));
	}

	@Test
	public void partitionedDisjointStreamsDefiniteThenUncertainThenEmptySentinel() {
		IndexGeometry bound = geometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeDisjointCandidates(bound,
				new EnvelopeDisjointCandidate(1L, geometry("POINT(10 10)").sourceTopologicalDimension()));
		indexer.addEnvelopeCandidates(bound, candidate(2L, geometry("POINT(1 1)")));
		indexer.nonSpatialCandidates = List.of(candidate(3L, geometry("GEOMETRYCOLLECTION EMPTY")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(bound), LOG);

		RelationCandidateTraversal.Candidate definite = traversal.next();
		assertEquals(1L, definite.entityId());
		assertEquals(RelationCandidateTraversal.MatchCertainty.DEFINITE_MATCH, definite.matchCertainty());
		assertThrows(IllegalStateException.class, definite::exactCandidateEntity);

		RelationCandidateTraversal.Candidate uncertain = traversal.next();
		assertEquals(2L, uncertain.entityId());
		assertEquals(RelationCandidateTraversal.MatchCertainty.REQUIRES_EXACT_EVALUATION,
				uncertain.matchCertainty());
		assertSame(bound.sourceGeometryLiteral(), uncertain.boundSourceGeometryLiteral().orElseThrow());

		RelationCandidateTraversal.Candidate sentinel = traversal.next();
		assertEquals(3L, sentinel.entityId());
		assertEquals(RelationCandidateTraversal.MatchCertainty.REQUIRES_EXACT_EVALUATION,
				sentinel.matchCertainty());
		assertTrue(sentinel.boundSourceGeometryLiteral().isEmpty());

		assertFalse(traversal.hasNext());
		assertEquals(List.of("open:definite", "close:definite",
				"open:" + bound.sourceGeometryLiteral().lexicalForm(),
				"close:" + bound.sourceGeometryLiteral().lexicalForm(),
				"open:sentinel", "close:sentinel"), indexer.events);
	}

	@Test
	public void partitionedDisjointCompletesEveryDefiniteLookupBeforeAnyUncertainLookup() {
		IndexGeometry firstBound = geometry("POINT(0 0)");
		IndexGeometry secondBound = geometry("POINT(10 10)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeDisjointCandidates(firstBound, new EnvelopeDisjointCandidate(1L, 0));
		indexer.addEnvelopeDisjointCandidates(secondBound, new EnvelopeDisjointCandidate(2L, 0));
		indexer.addEnvelopeCandidates(firstBound, candidate(3L, geometry("POINT(0 0)")));
		indexer.addEnvelopeCandidates(secondBound, candidate(4L, geometry("POINT(10 10)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(firstBound, secondBound), LOG);

		assertEquals(1L, traversal.next().entityId());
		assertEquals(2L, traversal.next().entityId());
		assertEquals(3L, traversal.next().entityId());
		assertEquals(4L, traversal.next().entityId());
		assertFalse(traversal.hasNext());
	}

	@Test
	public void partitionedDisjointUsesOneFullScanWhenAnyBoundSourceIsEmpty() {
		IndexGeometry spatialBound = geometry("POINT(0 0)");
		IndexGeometry emptyBound = geometry("GEOMETRYCOLLECTION EMPTY");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.fullScanCandidates = List.of(candidate(1L, geometry("POINT(10 10)")));
		indexer.addEnvelopeDisjointCandidates(spatialBound, new EnvelopeDisjointCandidate(2L, 0));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(spatialBound, emptyBound), LOG);

		RelationCandidateTraversal.Candidate candidate = traversal.next();
		assertEquals(1L, candidate.entityId());
		assertTrue(candidate.boundSourceGeometryLiteral().isEmpty());
		assertFalse(traversal.hasNext());
		assertEquals(1, indexer.fullScanLookupCount);
		assertTrue(indexer.events.isEmpty());
	}

	@Test
	public void objectBoundNativeCrs84TraversalUsesEnvelopeCandidates() {
		IndexGeometry bound = geometry("POINT(0 0)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeCandidates(bound, candidate(1L, geometry("POINT(0 0)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_INTERSECTS, List.of(bound), false, LOG);

		assertEquals(1L, traversal.next().entityId());
		assertFalse(traversal.hasNext());
		assertEquals(0, indexer.fullScanLookupCount);
		assertEquals(List.of(bound.sourceGeometryLiteral()), indexer.envelopeLookupSources);
	}

	@Test
	public void nonDisjointTraversalUsesOnlyEnvelopeCandidates() {
		IndexGeometry bound = geometry("POINT(0 0)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.disjointCleanupCandidates = List.of(candidate(1L,
				geometry("<http://www.opengis.net/def/crs/EPSG/0/32632> POINT(500000 5200000)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_INTERSECTS, List.of(bound), false, LOG);

		assertFalse(traversal.hasNext());
	}

	@Test
	public void objectBoundDisjointCleanupCandidatesRetainBoundSourceForExactEvaluation() {
		IndexGeometry bound = geometry("POINT(0 0)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.disjointCleanupCandidates = List.of(candidate(1L,
				geometry("<http://www.opengis.net/def/crs/EPSG/0/32632> POINT(500000 5200000)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(bound), false, LOG);
		RelationCandidateTraversal.Candidate candidate = traversal.next();

		assertEquals(1L, candidate.entityId());
		assertSame(bound.sourceGeometryLiteral(), candidate.boundSourceGeometryLiteral().orElseThrow());
		assertFalse(traversal.hasNext());
	}

	@Test
	public void subjectBoundDisjointUsesSelectivePartitionsAndMixedCrsCleanup() {
		IndexGeometry bound = geometry(
				"<http://www.opengis.net/def/crs/EPSG/0/32632> POINT(500000 5200000)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.disjointCleanupCandidates = List.of(candidate(1L, geometry("POINT(9 46.953529)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(bound), true, LOG);

		RelationCandidateTraversal.Candidate candidate = traversal.next();
		assertEquals(RelationCandidateTraversal.MatchCertainty.REQUIRES_EXACT_EVALUATION,
				candidate.matchCertainty());
		assertSame(bound.sourceGeometryLiteral(), candidate.boundSourceGeometryLiteral().orElseThrow());
		assertFalse(traversal.hasNext());
		assertEquals(0, indexer.fullScanLookupCount);
		assertEquals(List.of(bound.sourceGeometryLiteral()), indexer.envelopeDisjointLookupSources);
	}

	@Test
	public void rcc8DefinitePartitionReturnsOnlyAreaAreaSourcePairs() {
		IndexGeometry areaBound = geometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeDisjointCandidates(areaBound,
				new EnvelopeDisjointCandidate(1L, geometry("POINT(10 10)").sourceTopologicalDimension()),
				new EnvelopeDisjointCandidate(2L, geometry(
						"POLYGON((10 10,10 12,12 12,12 10,10 10))").sourceTopologicalDimension()));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.RCC8_DC, List.of(areaBound), LOG);

		assertEquals(2L, traversal.next().entityId());
		assertFalse(traversal.hasNext());
	}

	@Test
	public void rcc8SkipsNonAreaBoundSourcesInBothEnvelopePhases() {
		IndexGeometry pointBound = geometry("POINT(0 0)");
		IndexGeometry lineBound = geometry("LINESTRING(0 0,2 2)");
		IndexGeometry areaBound = geometry("POLYGON((0 0,0 2,2 2,2 0,0 0))");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeDisjointCandidates(pointBound,
				new EnvelopeDisjointCandidate(1L, Dimension.A));
		indexer.addEnvelopeDisjointCandidates(lineBound,
				new EnvelopeDisjointCandidate(2L, Dimension.A));
		indexer.addEnvelopeDisjointCandidates(areaBound,
				new EnvelopeDisjointCandidate(3L, Dimension.A));
		indexer.addEnvelopeCandidates(pointBound, candidate(4L, geometry("POINT(0 0)")));
		indexer.addEnvelopeCandidates(lineBound, candidate(5L, geometry("LINESTRING(0 0,2 2)")));
		indexer.addEnvelopeCandidates(areaBound, candidate(6L, areaBound));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.RCC8_DC, List.of(pointBound, lineBound, areaBound), LOG);

		while (traversal.hasNext()) {
			traversal.next();
		}

		assertEquals(List.of(areaBound.sourceGeometryLiteral()), indexer.envelopeDisjointLookupSources);
		assertEquals(List.of(areaBound.sourceGeometryLiteral()), indexer.envelopeLookupSources);
	}

	@Test
	public void rcc8WithOnlyNonAreaBoundsFinishesWithoutOpeningReaders() {
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.RCC8_DC,
				List.of(geometry("POINT(0 0)"), geometry("LINESTRING(0 0,2 2)")), LOG);

		assertFalse(traversal.hasNext());
		assertTrue(indexer.events.isEmpty());
		assertTrue(indexer.envelopeDisjointLookupSources.isEmpty());
		assertTrue(indexer.envelopeLookupSources.isEmpty());
	}

	@Test
	public void noBoundGeometriesFinishWithoutOpeningReadersForEveryRelationPolicy() {
		for (GeoSparqlPropertyRelation relation : List.of(
				GeoSparqlPropertyRelation.SF_DISJOINT,
				GeoSparqlPropertyRelation.SF_WITHIN)) {
			TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
			RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
					relation, List.of(), LOG);

			assertFalse(relation + " should have no candidates", traversal.hasNext());
			assertEquals(relation + " should not open a full scan", 0, indexer.fullScanLookupCount);
			assertTrue(relation + " should not open a Lucene reader", indexer.events.isEmpty());
		}
	}

	@Test
	public void earlyCloseClosesActiveDefiniteReaderWithoutOpeningLaterPhases() {
		IndexGeometry bound = geometry("POINT(0 0)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeDisjointCandidates(bound, new EnvelopeDisjointCandidate(1L, 0));
		indexer.addEnvelopeCandidates(bound, candidate(2L, geometry("POINT(0 0)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(bound), LOG);

		assertTrue(traversal.hasNext());
		traversal.close();

		assertTrue(indexer.lastDefiniteIterator.closed);
		assertEquals(List.of("open:definite", "close:definite"), indexer.events);
		assertFalse(traversal.hasNext());
	}

	@Test
	public void traversalClosesActiveReaderWhenCandidateIterationFails() {
		IndexGeometry bound = geometry("POINT(0 0)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.envelopeFailure = new IllegalStateException("candidate failure");

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(bound), LOG);

		IllegalStateException failure = assertThrows(IllegalStateException.class, traversal::hasNext);
		assertEquals("candidate failure", failure.getMessage());
		assertTrue(indexer.failingEnvelopeIterator.closed);
		assertFalse(traversal.hasNext());
	}

	@Test
	public void fullScanOpensOnceAndClosesItsReaderOnExhaustion() {
		IndexGeometry emptyBound = geometry("GEOMETRYCOLLECTION EMPTY");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.fullScanCandidates = List.of(candidate(1L, geometry("POINT(1 1)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(emptyBound), LOG);

		assertTrue(traversal.hasNext());
		assertTrue(traversal.hasNext());
		RelationCandidateTraversal.Candidate result = traversal.next();
		assertEquals(1L, result.exactCandidateEntity().entityId());
		assertTrue(result.boundSourceGeometryLiteral().isEmpty());
		assertFalse(traversal.hasNext());
		assertFalse(traversal.hasNext());
		assertEquals(1, indexer.fullScanLookupCount);
		assertTrue(indexer.fullScanIterator.closed);

		traversal.close();
		assertTrue(indexer.fullScanIterator.closed);
	}

	@Test
	public void fullScanClosesItsReaderWhenTraversalClosesEarly() {
		IndexGeometry emptyBound = geometry("GEOMETRYCOLLECTION EMPTY");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.fullScanCandidates = List.of(candidate(1L, geometry("POINT(1 1)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_DISJOINT, List.of(emptyBound), LOG);

		assertTrue(traversal.hasNext());
		traversal.close();

		assertTrue(indexer.fullScanIterator.closed);
		assertFalse(traversal.hasNext());
	}

	@Test
	public void envelopeTraversalSkipsEmptyAndRetainsBoundSource() {
		IndexGeometry first = geometry("POINT(0 0)");
		IndexGeometry empty = geometry("GEOMETRYCOLLECTION EMPTY");
		IndexGeometry second = geometry("POINT(10 10)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeCandidates(first, candidate(1L, geometry("POINT(0 0)")));
		indexer.addEnvelopeCandidates(second, candidate(2L, geometry("POINT(10 10)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_WITHIN, List.of(first, empty, second), LOG);

		RelationCandidateTraversal.Candidate firstResult = traversal.next();
		assertEquals(1L, firstResult.exactCandidateEntity().entityId());
		assertSame(first.sourceGeometryLiteral(), firstResult.boundSourceGeometryLiteral().orElseThrow());

		RelationCandidateTraversal.Candidate secondResult = traversal.next();
		assertEquals(2L, secondResult.exactCandidateEntity().entityId());
		assertSame(second.sourceGeometryLiteral(), secondResult.boundSourceGeometryLiteral().orElseThrow());

		assertFalse(traversal.hasNext());
		assertEquals(List.of(first.sourceGeometryLiteral(), second.sourceGeometryLiteral()),
				indexer.envelopeLookupSources);
		assertEquals(List.of(
				"open:POINT(0 0)",
				"close:POINT(0 0)",
				"open:POINT(10 10)",
				"close:POINT(10 10)"), indexer.events);
	}

	@Test
	public void earlyCloseClosesActiveEnvelopeReaderWithoutOpeningAnother() {
		IndexGeometry first = geometry("POINT(0 0)");
		IndexGeometry second = geometry("POINT(10 10)");
		TrackingGeoSparqlIndexer indexer = new TrackingGeoSparqlIndexer();
		indexer.addEnvelopeCandidates(first, candidate(1L, geometry("POINT(0 0)")));
		indexer.addEnvelopeCandidates(second, candidate(2L, geometry("POINT(10 10)")));

		RelationCandidateTraversal traversal = new RelationCandidateTraversal(indexer,
				GeoSparqlPropertyRelation.SF_WITHIN, List.of(first, second), LOG);

		assertTrue(traversal.hasNext());
		traversal.close();

		assertEquals(List.of(first.sourceGeometryLiteral()), indexer.envelopeLookupSources);
		assertEquals(List.of("open:POINT(0 0)", "close:POINT(0 0)"), indexer.events);
		assertFalse(traversal.hasNext());
	}

	private static IndexGeometry geometry(String wkt) {
		return IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt));
	}

	private static CandidateEntity candidate(long entityId, IndexGeometry... geometries) {
		LinkedHashSet<SourceGeometryLiteral> sources = new LinkedHashSet<>();
		for (IndexGeometry geometry : geometries) {
			sources.add(geometry.sourceGeometryLiteral());
		}
		return new CandidateEntity(entityId, sources);
	}

	private static final class TrackingGeoSparqlIndexer implements GeoSparqlIndexer {
		private final Map<SourceGeometryLiteral, List<CandidateEntity>> envelopeCandidates = new LinkedHashMap<>();
		private final Map<SourceGeometryLiteral, List<EnvelopeDisjointCandidate>> envelopeDisjointCandidates =
				new LinkedHashMap<>();
		private final List<SourceGeometryLiteral> envelopeLookupSources = new ArrayList<>();
		private final List<SourceGeometryLiteral> envelopeDisjointLookupSources = new ArrayList<>();
		private final List<String> events = new ArrayList<>();
		private List<CandidateEntity> fullScanCandidates = List.of();
		private List<CandidateEntity> disjointCleanupCandidates = List.of();
		private List<CandidateEntity> nonSpatialCandidates = List.of();
		private TrackingIterator<CandidateEntity> fullScanIterator;
		private TrackingIterator<EnvelopeDisjointCandidate> lastDefiniteIterator;
		private RuntimeException envelopeFailure;
		private FailingIterator<CandidateEntity> failingEnvelopeIterator;
		private int fullScanLookupCount;

		private void addEnvelopeCandidates(IndexGeometry bound, CandidateEntity... candidates) {
			envelopeCandidates.put(bound.sourceGeometryLiteral(), List.of(candidates));
		}

		private void addEnvelopeDisjointCandidates(IndexGeometry bound,
				EnvelopeDisjointCandidate... candidates) {
			envelopeDisjointCandidates.put(bound.sourceGeometryLiteral(), List.of(candidates));
		}

		@Override
		public CloseableIterator<CandidateEntity> getEnvelopeIntersections(IndexGeometry boundSourceIndexGeometry) {
			SourceGeometryLiteral source = boundSourceIndexGeometry.sourceGeometryLiteral();
			envelopeLookupSources.add(source);
			events.add("open:" + source.lexicalForm());
			if (envelopeFailure != null) {
				failingEnvelopeIterator = new FailingIterator<>(envelopeFailure);
				return failingEnvelopeIterator;
			}
			return new TrackingIterator<>(envelopeCandidates.getOrDefault(source, List.of()),
					() -> events.add("close:" + source.lexicalForm()));
		}

		@Override
		public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
				IndexGeometry boundSourceIndexGeometry) {
			envelopeDisjointLookupSources.add(boundSourceIndexGeometry.sourceGeometryLiteral());
			events.add("open:definite");
			lastDefiniteIterator = new TrackingIterator<>(envelopeDisjointCandidates.getOrDefault(
					boundSourceIndexGeometry.sourceGeometryLiteral(), List.of()),
					() -> events.add("close:definite"));
			return lastDefiniteIterator;
		}

		@Override
		public CloseableIterator<CandidateEntity> getNonSpatialCandidates() {
			events.add("open:sentinel");
			return new TrackingIterator<>(nonSpatialCandidates, () -> events.add("close:sentinel"));
		}

		@Override
		public CloseableIterator<CandidateEntity> getAllEntities() {
			fullScanLookupCount++;
			fullScanIterator = new TrackingIterator<>(fullScanCandidates, () -> {
			});
			return fullScanIterator;
		}

		@Override
		public CloseableIterator<CandidateEntity> getDisjointTransformCleanupCandidates(
				IndexGeometry boundSourceIndexGeometry, boolean candidateIsSubject) {
			return new TrackingIterator<>(disjointCleanupCandidates, () -> {
			});
		}

		@Override
		public CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsFor(long subject) {
			return new TrackingIterator<>(List.of(), () -> {
			});
		}

		@Override
		public void initialize() {
		}

		@Override
		public void indexGeometryList(long subject, Function<Long, String> subjectMapper,
				List<IndexGeometry> geometries) {
		}

		@Override
		public void initSettings() {
		}

		@Override
		public void begin() {
		}

		@Override
		public void commit() {
		}

		@Override
		public void rollback() {
		}

		@Override
		public void appendGeometry(long subject, Function<Long, String> subjectMapper, IndexGeometry geometry) {
		}

		@Override
		public void freshIndex() {
		}
	}

	private static final class TrackingIterator<T> implements CloseableIterator<T> {
		private final Iterator<T> values;
		private final Runnable onClose;
		private boolean closed;

		private TrackingIterator(Collection<T> values, Runnable onClose) {
			this.values = values.iterator();
			this.onClose = onClose;
		}

		@Override
		public boolean hasNext() {
			return values.hasNext();
		}

		@Override
		public T next() {
			return values.next();
		}

		@Override
		public void close() {
			if (!closed) {
				closed = true;
				onClose.run();
			}
		}
	}

	private static final class FailingIterator<T> implements CloseableIterator<T> {
		private final RuntimeException failure;
		private boolean closed;

		private FailingIterator(RuntimeException failure) {
			this.failure = failure;
		}

		@Override
		public boolean hasNext() {
			throw failure;
		}

		@Override
		public T next() {
			throw failure;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
