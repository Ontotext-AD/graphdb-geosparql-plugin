package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.PluginException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RepositoryGeometrySourceTest {
	private static final long AS_WKT = 10L;
	private static final long AS_GML = 11L;
	private static final long HAS_DEFAULT_GEOMETRY = 12L;
	private static final long GEOMETRY_RESOURCE = 100L;
	private static final long FEATURE_1 = 200L;
	private static final long FEATURE_2 = 201L;
	private static final long LITERAL = 300L;

	@Test
	public void geometriesForGeometryResourceIsMemoizedWithinSourceInstance() {
		CountingGeoSparqlPlugin plugin = newPlugin(false);

		FakeEntities entities = new FakeEntities();
		entities.add(LITERAL, SimpleValueFactory.getInstance()
				.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL));

		FakeStatements statements = new FakeStatements();
		statements.add(GEOMETRY_RESOURCE, AS_WKT, LITERAL);
		statements.add(FEATURE_1, HAS_DEFAULT_GEOMETRY, GEOMETRY_RESOURCE);
		statements.add(FEATURE_2, HAS_DEFAULT_GEOMETRY, GEOMETRY_RESOURCE);

		RepositoryGeometrySource source = new RepositoryGeometrySource(plugin,
				new FakePluginConnection(entities, statements));

		List<IndexGeometry> first = source.geometriesForGeometryResource(GEOMETRY_RESOURCE);
		List<IndexGeometry> second = source.geometriesForGeometryResource(GEOMETRY_RESOURCE);
		List<IndexGeometry> featureGeometries = source.geometriesForFeature(FEATURE_1);

		List<Long> featureIds = new ArrayList<>();
		List<Integer> featureGeometryCounts = new ArrayList<>();
		source.forEachFeatureUsingGeometry(GEOMETRY_RESOURCE, (featureId, geometries) -> {
			featureIds.add(featureId);
			featureGeometryCounts.add(geometries.size());
		});

		assertEquals(1, first.size());
		assertEquals(1, second.size());
		assertEquals(1, featureGeometries.size());
		assertEquals(Set.of(FEATURE_1, FEATURE_2), new HashSet<>(featureIds));
		assertEquals(2, featureGeometryCounts.size());
		assertTrue(featureGeometryCounts.stream().allMatch(count -> count == 1));
		assertEquals(1, plugin.conversionCount);
		assertThrows(UnsupportedOperationException.class, () -> first.add(first.get(0)));
	}

	@Test
	public void cacheIsScopedToSourceInstance() {
		CountingGeoSparqlPlugin plugin = newPlugin(false);
		FakePluginConnection pluginConnection = validGeometryConnection("POINT(1 1)");

		RepositoryGeometrySource firstSource = new RepositoryGeometrySource(plugin, pluginConnection);
		assertEquals(1, firstSource.geometriesForGeometryResource(GEOMETRY_RESOURCE).size());
		assertEquals(1, firstSource.geometriesForFeature(FEATURE_1).size());
		assertEquals(1, plugin.conversionCount);

		RepositoryGeometrySource secondSource = new RepositoryGeometrySource(plugin, pluginConnection);
		assertEquals(1, secondSource.geometriesForGeometryResource(GEOMETRY_RESOURCE).size());
		assertEquals(2, plugin.conversionCount);
	}

	@Test
	public void invalidGeometryThrowsWhenIgnoreErrorsIsFalse() {
		CountingGeoSparqlPlugin plugin = newPlugin(false);
		RepositoryGeometrySource source = new RepositoryGeometrySource(plugin,
				validGeometryConnection("not valid wkt"));

		PluginException exception = assertThrows(PluginException.class,
				() -> source.geometriesForGeometryResource(GEOMETRY_RESOURCE));

		assertTrue(exception.getMessage().contains("Could not index GeoSPARQL geometry"));
		assertTrue(exception.getMessage().contains("Source geometry literal datatype: "
				+ GeoConstants.GEO_WKT_LITERAL));
		assertTrue(exception.getMessage().contains("CRS: " + IndexGeometry.INDEX_CRS
				+ " (implicit GeoSPARQL WKT default)"));
		assertEquals(1, plugin.conversionCount);
	}

	@Test
	public void unsupportedWktCrsFailureIncludesCrsContext() {
		CountingGeoSparqlPlugin plugin = newPlugin(false);
		RepositoryGeometrySource source = new RepositoryGeometrySource(plugin,
				validGeometryConnection("<http://example.com/crs/unknown> POINT(1 2)"));

		PluginException exception = assertThrows(PluginException.class,
				() -> source.geometriesForGeometryResource(GEOMETRY_RESOURCE));

		assertTrue(exception.getMessage().contains("CRS: http://example.com/crs/unknown"));
		assertTrue(exception.getMessage().contains("Apache SIS CRS data"));
		assertEquals(1, plugin.conversionCount);
	}

	@Test
	public void invalidGeometryIsSkippedAndCachedWhenIgnoreErrorsIsTrue() {
		CountingGeoSparqlPlugin plugin = newPlugin(true);
		RepositoryGeometrySource source = new RepositoryGeometrySource(plugin,
				validGeometryConnection("not valid wkt"));

		assertTrue(source.geometriesForGeometryResource(GEOMETRY_RESOURCE).isEmpty());
		assertTrue(source.geometriesForFeature(FEATURE_1).isEmpty());
		assertEquals(1, plugin.conversionCount);
	}

	private static CountingGeoSparqlPlugin newPlugin(boolean ignoreErrors) {
		CountingGeoSparqlPlugin plugin = new CountingGeoSparqlPlugin();
		plugin.asWKT = AS_WKT;
		plugin.asGML = AS_GML;
		plugin.hasDefaultGeometry = HAS_DEFAULT_GEOMETRY;
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setIgnoreErrors(ignoreErrors);
		plugin.setConfig(config);
		plugin.setLogger(LoggerFactory.getLogger(RepositoryGeometrySourceTest.class));
		return plugin;
	}

	private static FakePluginConnection validGeometryConnection(String wkt) {
		FakeEntities entities = new FakeEntities();
		entities.add(GEOMETRY_RESOURCE, SimpleValueFactory.getInstance()
				.createIRI("http://example.com/geometry"));
		entities.add(LITERAL, SimpleValueFactory.getInstance().createLiteral(wkt, GeoConstants.GEO_WKT_LITERAL));

		FakeStatements statements = new FakeStatements();
		statements.add(GEOMETRY_RESOURCE, AS_WKT, LITERAL);
		statements.add(FEATURE_1, HAS_DEFAULT_GEOMETRY, GEOMETRY_RESOURCE);

		return new FakePluginConnection(entities, statements);
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
			return new ListStatementIterator(matchingTriples(subject, predicate, object));
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
		private int index;

		private ListStatementIterator(List<Triple> triples) {
			this.triples = triples;
		}

		@Override
		public boolean next() {
			if (index >= triples.size()) {
				return false;
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
