package com.ontotext.trree.geosparql;

import com.ontotext.trree.OwlimSchemaRepository;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that GraphDB transactions activate durable GeoSPARQL state only when they mutate configuration or index
 * data, while retaining crash-safe ordering for mutations. Regression provenance:
 * https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/2.
 */
public class TestPluginEnableTransactionLifecycle extends AbstractGeoSparqlPluginTest {
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/enable-transaction/>\n";
	private static final IRI CONTAINER = VF.createIRI("http://example.com/enable-transaction/container");
	private static final IRI BEFORE_ENABLE = VF.createIRI("http://example.com/enable-transaction/before-enable");
	private static final IRI AFTER_ENABLE = VF.createIRI("http://example.com/enable-transaction/after-enable");
	private static final Literal CONTAINER_WKT = VF.createLiteral(
			"POLYGON((0 0,0 4,4 4,4 0,0 0))", GeoConstants.GEO_WKT_LITERAL);
	private static final Literal BEFORE_ENABLE_WKT = VF.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL);
	private static final Literal AFTER_ENABLE_WKT = VF.createLiteral("POINT(2 2)", GeoConstants.GEO_WKT_LITERAL);

	@Test
	public void enableAndGeometryMutationCommitTogetherAndSurviveRestart() throws Exception {
		insertPreEnableGeometries();

		connection.begin();
		enablePluginInCurrentTransaction();
		connection.add(AFTER_ENABLE, GeoConstants.GEO_AS_WKT, AFTER_ENABLE_WKT);
		connection.commit();

		assertIndexedBeforeAndAfterEnable();
		assertFalse(Files.exists(pendingTransactionMarker()));

		restartRepository();

		assertIndexedBeforeAndAfterEnable();
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void enableWithoutGeometryMutationCommitsFullRebuildOnce() throws Exception {
		insertPreEnableGeometries();

		connection.begin();
		enablePluginInCurrentTransaction();
		connection.commit();

		assertTrue(ask("ex:before-enable geo:sfWithin ex:container"));
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void enablePersistsPendingStateBeforeLuceneRebuild() throws Exception {
		insertPreEnableGeometries();
		GeoSparqlPlugin plugin = activePlugin();
		PendingStateObservingIndexer indexer = new PendingStateObservingIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		connection.begin();
		enablePluginInCurrentTransaction();
		connection.commit();

		assertTrue(indexer.enabledConfigObservedBeforeRebuild);
		assertTrue(indexer.pendingMarkerObservedBeforeRebuild);
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void forceReindexPersistsPendingStateBeforeLuceneRebuild() throws Exception {
		insertPreEnableGeometries();
		enablePlugin();
		GeoSparqlPlugin plugin = activePlugin();
		PendingStateObservingIndexer indexer = new PendingStateObservingIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		forceReindex();

		assertTrue(indexer.pendingMarkerObservedBeforeRebuild);
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void abortedEnableAndGeometryMutationRestoreDisabledConfigAndPreviousIndex() throws Exception {
		insertPreEnableGeometries();
		enablePlugin();
		disablePlugin();
		List<String> sourceLiteralsBeforeTransaction = persistedSourceLiterals();

		connection.begin();
		enablePluginInCurrentTransaction();
		connection.add(AFTER_ENABLE, GeoConstants.GEO_AS_WKT, AFTER_ENABLE_WKT);
		connection.rollback();

		assertFalse(activePlugin().getConfig().isEnabled());
		assertFalse(GeoSparqlUtils.readConfig(getGeoSparqlStorageDir().toPath()).isEnabled());
		assertEquals(sourceLiteralsBeforeTransaction, persistedSourceLiterals());
		assertFalse(Files.exists(pendingTransactionMarker()));

		restartRepository();

		assertFalse(GeoSparqlUtils.readConfig(getGeoSparqlStorageDir().toPath()).isEnabled());
		assertEquals(sourceLiteralsBeforeTransaction, persistedSourceLiterals());
	}

	@Test
	public void enabledTransactionStartsAndCommitsOneLuceneTransaction() throws Exception {
		insertPreEnableGeometries();
		enablePlugin();
		GeoSparqlPlugin plugin = activePlugin();
		CountingLuceneGeoIndexer indexer = new CountingLuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		connection.begin();
		connection.add(AFTER_ENABLE, GeoConstants.GEO_AS_WKT, AFTER_ENABLE_WKT);
		connection.commit();

		assertEquals(1, indexer.beginCount);
		assertEquals(1, indexer.commitCount);
		assertTrue(indexer.pendingMarkerObservedBeforeBegin);
		assertFalse(indexer.isTransactionActive());
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void unrelatedRdfTransactionDoesNotStartGeoSparqlPersistence() throws Exception {
		insertPreEnableGeometries();
		enablePlugin();
		GeoSparqlPlugin plugin = activePlugin();
		CountingLuceneGeoIndexer indexer = new CountingLuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		connection.begin();
		connection.add(VF.createIRI("http://example.com/unrelated/subject"),
				VF.createIRI("http://example.com/unrelated/predicate"), VF.createLiteral("value"));

		assertFalse(Files.exists(pendingTransactionMarker()));

		connection.commit();

		assertEquals(0, indexer.beginCount);
		assertEquals(0, indexer.commitCount);
		assertFalse(indexer.pendingMarkerObservedBeforeBegin);
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	@Test
	public void configOnlyDisableDoesNotStartLuceneTransaction() throws Exception {
		insertPreEnableGeometries();
		enablePlugin();
		GeoSparqlPlugin plugin = activePlugin();
		CountingLuceneGeoIndexer indexer = new CountingLuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		disablePlugin();

		assertEquals(0, indexer.beginCount);
		assertEquals(0, indexer.commitCount);
		assertFalse(Files.exists(pendingTransactionMarker()));
	}

	private void insertPreEnableGeometries() {
		connection.begin();
		connection.add(CONTAINER, GeoConstants.GEO_AS_WKT, CONTAINER_WKT);
		connection.add(BEFORE_ENABLE, GeoConstants.GEO_AS_WKT, BEFORE_ENABLE_WKT);
		connection.commit();
	}

	private void enablePluginInCurrentTransaction() {
		connection.prepareUpdate(QueryLanguage.SPARQL,
				"INSERT DATA { _:plugin <" + GeoSparqlPlugin.ENABLED_PREDICATE_IRI + "> true }").execute();
	}

	private void assertIndexedBeforeAndAfterEnable() {
		assertTrue(ask("ex:before-enable geo:sfWithin ex:container"));
		assertTrue(ask("ex:after-enable geo:sfWithin ex:container"));
	}

	private boolean ask(String pattern) {
		return connection.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES + "ASK { " + pattern + " }").evaluate();
	}

	private Path pendingTransactionMarker() {
		return GeoSparqlConfig.resolveIndexPath(getGeoSparqlStorageDir().toPath())
				.resolve("pending-graphdb-transaction");
	}

	private List<String> persistedSourceLiterals() throws Exception {
		GeoSparqlPlugin indexReaderPlugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setEnabled(true);
		indexReaderPlugin.setConfig(config);
		indexReaderPlugin.setDataDir(getGeoSparqlStorageDir());
		indexReaderPlugin.setLogger(LoggerFactory.getLogger(TestPluginEnableTransactionLifecycle.class));
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(indexReaderPlugin);
		indexer.initialize();
		List<String> lexicalForms = new ArrayList<>();
		try (CloseableIterator<SourceGeometryLiteral> sources = indexer.getSourceGeometryLiteralsFor(0)) {
			while (sources.hasNext()) {
				lexicalForms.add(sources.next().lexicalForm());
			}
		}
		lexicalForms.sort(String::compareTo);
		return lexicalForms;
	}

	private GeoSparqlPlugin activePlugin() {
		return (GeoSparqlPlugin) ((OwlimSchemaRepository) ((SailRepository) repository).getSail())
				.getPlugin("GeoSPARQL");
	}

	private static final class CountingLuceneGeoIndexer extends LuceneGeoIndexer {
		private int beginCount;
		private int commitCount;
		private boolean pendingMarkerObservedBeforeBegin;
		private final GeoSparqlTransactionMarker transactionMarker;

		private CountingLuceneGeoIndexer(GeoSparqlPlugin parent) {
			super(parent);
			transactionMarker = new GeoSparqlTransactionMarker(parent.getDataDir().toPath());
		}

		@Override
		public void begin() throws Exception {
			beginCount++;
			pendingMarkerObservedBeforeBegin = transactionMarker.exists();
			super.begin();
		}

		@Override
		public void commit() throws Exception {
			commitCount++;
			super.commit();
		}
	}

	private final class PendingStateObservingIndexer extends LuceneGeoIndexer {
		private boolean enabledConfigObservedBeforeRebuild;
		private boolean pendingMarkerObservedBeforeRebuild;

		private PendingStateObservingIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		public void freshIndex() throws Exception {
			enabledConfigObservedBeforeRebuild =
					GeoSparqlUtils.readConfig(getGeoSparqlStorageDir().toPath()).isEnabled();
			pendingMarkerObservedBeforeRebuild = Files.exists(pendingTransactionMarker());
			super.freshIndex();
		}
	}
}
