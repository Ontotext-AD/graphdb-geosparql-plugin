package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.Repository;
import com.ontotext.trree.sdk.SecurityContext;
import com.ontotext.trree.sdk.StatementIterator;
import com.ontotext.trree.sdk.Statements;
import com.ontotext.trree.sdk.SystemProperties;
import com.ontotext.trree.sdk.ThreadsafePluginConnecton;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;

public class GeoSparqlFullIndexerTest {
	private static final long AS_WKT = 10L;
	private static final long AS_GML = 11L;
	private static final long HAS_DEFAULT_GEOMETRY = 12L;
	private static final long GEOMETRY_1 = 100L;
	private static final long GEOMETRY_2 = 101L;
	private static final long LITERAL_1 = 200L;
	private static final long LITERAL_2 = 201L;
	private static final long FEATURE_1 = 300L;
	private static final long FEATURE_2 = 301L;

	@Test
	public void fullIndexConvertsEachGeometryLiteralBeforeAdvancingTheRepositoryScan() throws Exception {
		CountingGeoSparqlPlugin plugin = newPlugin();
		FakeEntities entities = new FakeEntities();
		entities.add(GEOMETRY_1, SimpleValueFactory.getInstance().createIRI("http://example.com/geometry/1"));
		entities.add(GEOMETRY_2, SimpleValueFactory.getInstance().createIRI("http://example.com/geometry/2"));
		entities.add(LITERAL_1, SimpleValueFactory.getInstance()
				.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL));
		entities.add(LITERAL_2, SimpleValueFactory.getInstance()
				.createLiteral("POINT(2 2)", GeoConstants.GEO_WKT_LITERAL));

		FakeStatements statements = new FakeStatements(() -> assertEquals(
				"The first source geometry literal should be converted before scanning the second statement",
				1, plugin.conversionCount));
		statements.add(GEOMETRY_1, AS_WKT, LITERAL_1);
		statements.add(GEOMETRY_2, AS_WKT, LITERAL_2);

		RecordingIndexer indexer = new RecordingIndexer();
		new GeoSparqlFullIndexer(indexer, plugin).reindex(new FakePluginConnection(entities, statements));

		assertEquals(List.of(GEOMETRY_1, GEOMETRY_2), indexer.indexedSubjects);
		assertEquals(2, plugin.conversionCount);
	}

	@Test
	public void fullIndexConvertsSharedDefaultGeometryBeforeAdvancingTheFeatureScan() throws Exception {
		CountingGeoSparqlPlugin plugin = newPlugin();
		FakeEntities entities = new FakeEntities();
		entities.add(GEOMETRY_1, SimpleValueFactory.getInstance().createIRI("http://example.com/geometry/1"));
		entities.add(LITERAL_1, SimpleValueFactory.getInstance()
				.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL));

		FakeStatements statements = new FakeStatements(null, () -> assertEquals(
				"The first Feature's default geometry should be converted before scanning the second statement",
				2, plugin.conversionCount));
		statements.add(GEOMETRY_1, AS_WKT, LITERAL_1);
		statements.add(FEATURE_1, HAS_DEFAULT_GEOMETRY, GEOMETRY_1);
		statements.add(FEATURE_2, HAS_DEFAULT_GEOMETRY, GEOMETRY_1);

		RecordingIndexer indexer = new RecordingIndexer();
		new GeoSparqlFullIndexer(indexer, plugin).reindex(new FakePluginConnection(entities, statements));

		assertEquals(List.of(GEOMETRY_1, FEATURE_1, FEATURE_2), indexer.indexedSubjects);
		assertEquals(3, plugin.conversionCount);
	}

	@Test
	public void fullIndexAppendsGenericCollectionSourceOnce() throws Exception {
		CountingGeoSparqlPlugin plugin = newPlugin();
		FakeEntities entities = new FakeEntities();
		entities.add(GEOMETRY_1, SimpleValueFactory.getInstance().createIRI("http://example.com/geometry/1"));
		entities.add(LITERAL_1, SimpleValueFactory.getInstance().createLiteral(
				"GEOMETRYCOLLECTION(POINT(1 1),LINESTRING(2 2,3 3))", GeoConstants.GEO_WKT_LITERAL));
		FakeStatements statements = new FakeStatements((Runnable) null);
		statements.add(GEOMETRY_1, AS_WKT, LITERAL_1);

		RecordingIndexer indexer = new RecordingIndexer();
		new GeoSparqlFullIndexer(indexer, plugin).reindex(new FakePluginConnection(entities, statements));

		assertEquals(List.of(GEOMETRY_1), indexer.indexedSubjects);
		assertEquals(1, plugin.conversionCount);
	}

	private static CountingGeoSparqlPlugin newPlugin() {
		CountingGeoSparqlPlugin plugin = new CountingGeoSparqlPlugin();
		plugin.asWKT = AS_WKT;
		plugin.asGML = AS_GML;
		plugin.hasDefaultGeometry = HAS_DEFAULT_GEOMETRY;
		plugin.setConfig(new GeoSparqlConfig());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlFullIndexerTest.class));
		return plugin;
	}

	private static final class CountingGeoSparqlPlugin extends GeoSparqlPlugin {
		private int conversionCount;

		@Override
		IndexGeometry getIndexGeometryFromLiteral(Literal literal, IRI fallbackDatatype) {
			conversionCount++;
			return JenaGeometryAdapter.toIndexGeometry(
					SourceGeometryLiteral.fromWkt(literal.stringValue()));
		}
	}

	private static final class RecordingIndexer implements GeoSparqlIndexer {
		private final List<Long> indexedSubjects = new ArrayList<>();

		@Override
		public void appendGeometry(long subject, Function<Long, String> subjectMapper, IndexGeometry geometry) {
			indexedSubjects.add(subject);
		}

		@Override
		public void initialize() {
		}

		@Override
		public void indexGeometryList(long subject, Function<Long, String> subjectMapper,
				List<IndexGeometry> geometries) {
			throw new UnsupportedOperationException("Full indexing must append one source geometry at a time.");
		}

		@Override
		public CloseableIterator<CandidateEntity> getEnvelopeIntersections(IndexGeometry boundSourceIndexGeometry) {
			return null;
		}

		@Override
		public CloseableIterator<EnvelopeDisjointCandidate> getEnvelopeDisjointCandidates(
				IndexGeometry boundSourceIndexGeometry) {
			return null;
		}

		@Override
		public CloseableIterator<CandidateEntity> getNonSpatialCandidates() {
			return null;
		}

		@Override
		public CloseableIterator<CandidateEntity> getAllEntities() {
			return null;
		}

		@Override
		public CloseableIterator<SourceGeometryLiteral> getSourceGeometryLiteralsFor(long subject) {
			return null;
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
		public void freshIndex() {
		}
	}

	private static final class FakePluginConnection implements PluginConnection {
		private final Entities entities;
		private final Statements statements;

		private FakePluginConnection(Entities entities, Statements statements) {
			this.entities = entities;
			this.statements = statements;
		}

		@Override
		public Entities getEntities() {
			return entities;
		}

		@Override
		public Statements getStatements() {
			return statements;
		}

		@Override
		public Repository getRepository() {
			return null;
		}

		@Override
		public long getTransactionId() {
			return 1L;
		}

		@Override
		public boolean isTesting() {
			return true;
		}

		@Override
		public SystemProperties getProperties() {
			return null;
		}

		@Override
		public String getFingerprint() {
			return "fake";
		}

		@Override
		public boolean isInCluster() {
			return false;
		}

		@Override
		public ThreadsafePluginConnecton getThreadsafeConnection() {
			return null;
		}

		@Override
		public SecurityContext getSecurityContext() {
			return null;
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

	private static final class FakeStatements implements Statements {
		private final List<Triple> triples = new ArrayList<>();
		private final Runnable beforeSecondGeometryStatement;
		private final Runnable beforeSecondFeatureStatement;

		private FakeStatements(Runnable beforeSecondGeometryStatement) {
			this(beforeSecondGeometryStatement, null);
		}

		private FakeStatements(Runnable beforeSecondGeometryStatement, Runnable beforeSecondFeatureStatement) {
			this.beforeSecondGeometryStatement = beforeSecondGeometryStatement;
			this.beforeSecondFeatureStatement = beforeSecondFeatureStatement;
		}

		private void add(long subject, long predicate, long object) {
			triples.add(new Triple(subject, predicate, object));
		}

		@Override
		public StatementIterator get(long subject, long predicate, long object, long context) {
			return get(subject, predicate, object);
		}

		@Override
		public boolean has(long subject, long predicate, long object, long context) {
			return has(subject, predicate, object);
		}

		@Override
		public long estimateSize(long subject, long predicate, long object, long context) {
			return matchingTriples(subject, predicate, object).size();
		}

		@Override
		public long uniqueSubjects(long predicate) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long uniqueObjects(long predicate) {
			throw new UnsupportedOperationException();
		}

		@Override
		public StatementIterator get(long subject, long predicate, long object) {
			Runnable beforeSecond = null;
			if (subject == 0 && object == 0) {
				if (predicate == AS_WKT) {
					beforeSecond = beforeSecondGeometryStatement;
				} else if (predicate == HAS_DEFAULT_GEOMETRY) {
					beforeSecond = beforeSecondFeatureStatement;
				}
			}
			return new ListStatementIterator(matchingTriples(subject, predicate, object), beforeSecond);
		}

		@Override
		public boolean has(long subject, long predicate, long object) {
			return !matchingTriples(subject, predicate, object).isEmpty();
		}

		private List<Triple> matchingTriples(long subject, long predicate, long object) {
			List<Triple> matches = new ArrayList<>();
			for (Triple triple : triples) {
				if ((subject == 0 || subject == triple.subject)
						&& (predicate == 0 || predicate == triple.predicate)
						&& (object == 0 || object == triple.object)) {
					matches.add(triple);
				}
			}
			return matches;
		}
	}

	private static final class ListStatementIterator extends StatementIterator {
		private final List<Triple> triples;
		private final Runnable beforeSecond;
		private int index;

		private ListStatementIterator(List<Triple> triples, Runnable beforeSecond) {
			this.triples = triples;
			this.beforeSecond = beforeSecond;
		}

		@Override
		public boolean next() {
			if (index >= triples.size()) {
				return false;
			}
			if (index == 1 && beforeSecond != null) {
				beforeSecond.run();
			}
			Triple triple = triples.get(index++);
			subject = triple.subject;
			predicate = triple.predicate;
			object = triple.object;
			context = 0;
			return true;
		}

		@Override
		public void close() {
		}
	}

	private static final class Triple {
		private final long subject;
		private final long predicate;
		private final long object;

		private Triple(long subject, long predicate, long object) {
			this.subject = subject;
			this.predicate = predicate;
			this.object = object;
		}
	}
}
