package com.ontotext.trree.geosparql;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/**
 * Verifies transaction rollback and durable publication ordering for GeoSPARQL configuration and index state.
 * Regression provenance: https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/2.
 */
public class GeoSparqlUpdateListenerTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void abortRestoresPrefixTreeConfigWrittenDuringCommit() throws Exception {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		Path dataDir = tmpFolder.newFolder("config-abort").toPath();
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		GeoSparqlUtils.saveConfig(config, dataDir);
		Path configPath = GeoSparqlConfig.resolveConfigPath(dataDir);
		byte[] originalConfigFile = Files.readAllBytes(configPath);
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);
		PluginConnection connection = emptyPluginConnection();

		listener.transactionStarted(connection);
		plugin.tmpPrefixTree = GeoSparqlConfig.PrefixTree.GEOHASH;
		plugin.tmpPrecision = 20;
		listener.transactionCommit(null);
		listener.transactionAborted(null);

		assertEquals(GeoSparqlConfig.PrefixTree.QUAD, plugin.getConfig().getPrefixTree());
		assertEquals(11, plugin.getConfig().getPrecision());
		assertArrayEquals(originalConfigFile, Files.readAllBytes(configPath));
	}

	@Test
	public void configMutationDoesNotReplaceConfigWhenMarkerCannotBeCreated() throws Exception {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		Path dataDir = tmpFolder.newFolder("config-marker-failure").toPath();
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		GeoSparqlUtils.saveConfig(config, dataDir);
		Path configPath = GeoSparqlConfig.resolveConfigPath(dataDir);
		byte[] originalConfigFile = Files.readAllBytes(configPath);
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);

		listener.transactionStarted(emptyPluginConnection());
		Files.createDirectories(GeoSparqlTransactionMarker.resolvePath(dataDir));
		plugin.tmpPrefixTree = GeoSparqlConfig.PrefixTree.GEOHASH;
		plugin.tmpPrecision = 20;

		assertThrows(PluginException.class, () -> listener.transactionCommit(emptyPluginConnection()));
		assertArrayEquals(originalConfigFile, Files.readAllBytes(configPath));
	}

	@Test
	public void unrelatedAbortDoesNotRewriteGeoSparqlConfig() throws Exception {
		Path dataDir = tmpFolder.newFolder("unrelated-abort").toPath();
		GeoSparqlPlugin plugin = enabledPlugin(dataDir);
		GeoSparqlUtils.saveConfig(plugin.getConfig(), dataDir);
		CountingConfigRestoreListener listener = new CountingConfigRestoreListener(plugin);

		listener.transactionStarted(emptyPluginConnection());
		listener.transactionAborted(emptyPluginConnection());

		assertEquals(0, listener.configRestoreCount);
		assertFalse(Files.exists(GeoSparqlTransactionMarker.resolvePath(dataDir)));
	}

	@Test
	public void abortRestoresCurrentSettingsChangedDuringForceReindex() throws Exception {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		Path dataDir = tmpFolder.newFolder("reindex-config-abort").toPath();
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		GeoSparqlUtils.saveConfig(config, dataDir);
		byte[] originalConfigFile = Files.readAllBytes(GeoSparqlConfig.resolveConfigPath(dataDir));
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);

		listener.transactionStarted(null);
		config.setPrefixTree(GeoSparqlConfig.PrefixTree.GEOHASH);
		config.setPrecision(20);
		config.updateCurrentSettings();
		listener.saveConfigForTransaction();
		listener.transactionAborted(null);

		assertEquals(GeoSparqlConfig.PrefixTree.QUAD, plugin.getConfig().getCurrentPrefixTree());
		assertEquals(11, plugin.getConfig().getCurrentPrecision());
		assertArrayEquals(originalConfigFile,
				Files.readAllBytes(GeoSparqlConfig.resolveConfigPath(dataDir)));
	}

	@Test
	public void listenerPostCommitAbortRestoresPreviousLuceneStateAfterRestart() throws Exception {
		Path dataDir = tmpFolder.newFolder("listener-post-commit-abort").toPath();
		GeoSparqlPlugin plugin = enabledPlugin(dataDir);
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;
		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(1 1)")));
		indexer.commit();
		indexer.complete();
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);
		PluginConnection connection = emptyPluginConnection();

		listener.transactionStarted(connection);
		listener.beginIndexTransactionForPersistentMutation();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(2 2)")));
		listener.transactionCommit(connection);
		PluginException pendingOutcome = assertThrows(PluginException.class,
				() -> indexer.getSourceGeometryLiteralsFor(1L));
		assertTrue(pendingOutcome.getMessage().contains("pending GraphDB transaction"));
		listener.transactionAborted(connection);

		LuceneGeoIndexer restarted = new LuceneGeoIndexer(enabledPlugin(dataDir));
		restarted.initialize();
		assertEquals("POINT(1 1)", onlySource(restarted).lexicalForm());
	}

	@Test
	public void abortRestoresCommitlessStateWhenIndexingStartsDuringPluginEnable() throws Exception {
		Path dataDir = tmpFolder.newFolder("enable-time-abort").toPath();
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);
		listener.transactionStarted(emptyPluginConnection());

		config.setEnabled(true);
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;
		listener.saveConfigForTransaction();
		listener.beginIndexTransactionForPersistentMutation();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(2 2)")));
		indexer.commit();
		listener.transactionAborted(emptyPluginConnection());

		LuceneGeoIndexer restarted = new LuceneGeoIndexer(enabledPlugin(dataDir));
		restarted.initialize();
		try (CloseableIterator<SourceGeometryLiteral> sources = restarted.getSourceGeometryLiteralsFor(0)) {
			assertFalse(sources.hasNext());
		}
	}

	@Test
	public void failedListenerRestorationLeavesCurrentAndRestartedIndexersFailClosed() throws Exception {
		Path dataDir = tmpFolder.newFolder("listener-failed-restoration").toPath();
		GeoSparqlPlugin plugin = enabledPlugin(dataDir);
		FailingRestoreIndexer indexer = new FailingRestoreIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;
		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(1 1)")));
		indexer.commit();
		indexer.complete();
		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);
		PluginConnection connection = emptyPluginConnection();

		listener.transactionStarted(connection);
		listener.beginIndexTransactionForPersistentMutation();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(2 2)")));
		listener.transactionCommit(connection);
		indexer.failRestore = true;
		listener.transactionAborted(connection);

		assertPendingTransactionFailure(indexer);
		LuceneGeoIndexer restarted = new LuceneGeoIndexer(enabledPlugin(dataDir));
		restarted.initialize();
		assertPendingTransactionFailure(restarted);
	}

	@Test
	public void failedConfigRestorationKeepsRestoredIndexFailClosedAcrossRestart() throws Exception {
		Path dataDir = tmpFolder.newFolder("failed-config-restoration").toPath();
		GeoSparqlPlugin plugin = enabledPlugin(dataDir);
		GeoSparqlUtils.saveConfig(plugin.getConfig(), dataDir);
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;
		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(1 1)")));
		indexer.commit();
		indexer.complete();
		FailingConfigRestoreListener listener = new FailingConfigRestoreListener(plugin);

		listener.transactionStarted(emptyPluginConnection());
		plugin.getConfig().setPrefixTree(GeoSparqlConfig.PrefixTree.GEOHASH);
		plugin.getConfig().setPrecision(20);
		plugin.getConfig().updateCurrentSettings();
		indexer.initSettings();
		listener.saveConfigForTransaction();
		listener.beginIndexTransactionForPersistentMutation();
		indexer.freshIndex();
		indexer.indexGeometryList(1L, id -> "geometry",
				List.of(TestIndexGeometries.fromWkt("POINT(2 2)")));
		listener.transactionCommit(emptyPluginConnection());
		listener.failConfigRestore = true;
		listener.transactionAborted(emptyPluginConnection());

		assertEquals(GeoSparqlConfig.PrefixTree.QUAD, plugin.getConfig().getCurrentPrefixTree());
		assertEquals(11, plugin.getConfig().getCurrentPrecision());
		assertEquals("POINT(1 1)", committedSourceLexicalForm(dataDir));
		assertPendingTransactionFailure(indexer);
		GeoSparqlConfig persistedConfig = GeoSparqlUtils.readConfig(dataDir);
		assertEquals(GeoSparqlConfig.PrefixTree.GEOHASH, persistedConfig.getCurrentPrefixTree());
		assertEquals(20, persistedConfig.getCurrentPrecision());
		LuceneGeoIndexer restarted = new LuceneGeoIndexer(pluginFromPersistedConfig(dataDir));
		restarted.initialize();
		assertPendingTransactionFailure(restarted);
	}

	@Test
	public void disablingPluginDuringTransactionDiscardsAccumulatedUpdateIds() throws Exception {
		Path dataDir = tmpFolder.newFolder("disable-accumulators").toPath();
		GeoSparqlPlugin plugin = enabledPlugin(dataDir);
		AtomicInteger indexedCount = new AtomicInteger();
		plugin.indexer = new LuceneGeoIndexer(plugin) {
			@Override
			public void begin() {}

			@Override
			public void commit() {}

			@Override
			public void complete() {}

			@Override
			public void indexGeometryList(long entityId, Function<Long, String> uriMapper,
										  List<IndexGeometry> geometries) {
				indexedCount.incrementAndGet();
			}
		};

		GeoSparqlUpdateListener listener = new GeoSparqlUpdateListener(plugin, 1L, 2L, 3L);
		PluginConnection connection = emptyPluginConnection();

		// Transaction 1: spatial statements are added while enabled, then plugin is disabled before commit
		listener.transactionStarted(connection);
		listener.statementAdded(10L, 1L, 20L, 0L, true, connection);
		listener.statementAdded(11L, 3L, 21L, 0L, true, connection);

		plugin.getConfig().setEnabled(false);
		listener.transactionCommit(connection);
		listener.transactionCompleted(connection);

		// Transaction 2: plugin is re-enabled, transaction runs with no modifications
		plugin.getConfig().setEnabled(true);
		listener.transactionStarted(connection);
		listener.transactionCommit(connection);
		listener.transactionCompleted(connection);

		assertEquals(0, indexedCount.get());
	}

	private GeoSparqlPlugin enabledPlugin(Path dataDir) {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setEnabled(true);
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		return plugin;
	}

	private GeoSparqlPlugin pluginFromPersistedConfig(Path dataDir) {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setConfig(GeoSparqlUtils.readConfig(dataDir));
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(LoggerFactory.getLogger(GeoSparqlUpdateListenerTest.class));
		return plugin;
	}

	private String committedSourceLexicalForm(Path dataDir) throws IOException {
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
			 DirectoryReader reader = DirectoryReader.open(directory)) {
			return reader.document(0).get("geoExactLexicalForm");
		}
	}

	private SourceGeometryLiteral onlySource(LuceneGeoIndexer indexer) throws Exception {
		try (CloseableIterator<SourceGeometryLiteral> sources = indexer.getSourceGeometryLiteralsFor(1L)) {
			assertTrue(sources.hasNext());
			SourceGeometryLiteral source = sources.next();
			assertFalse(sources.hasNext());
			return source;
		}
	}

	private void assertPendingTransactionFailure(LuceneGeoIndexer indexer) {
		PluginException failure = assertThrows(PluginException.class,
				() -> indexer.getSourceGeometryLiteralsFor(1L));
		assertTrue(failure.getMessage().contains("pending GraphDB transaction"));
	}

	private PluginConnection emptyPluginConnection() {
		return (PluginConnection) Proxy.newProxyInstance(
				PluginConnection.class.getClassLoader(), new Class<?>[]{PluginConnection.class},
				(proxy, method, arguments) -> null);
	}

	private static final class FailingRestoreIndexer extends LuceneGeoIndexer {
		private boolean failRestore;

		private FailingRestoreIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		protected void restorePreTransactionCommit() throws IOException {
			if (failRestore) {
				throw new IOException("Simulated restoration failure");
			}
			super.restorePreTransactionCommit();
		}
	}

	private static final class FailingConfigRestoreListener extends GeoSparqlUpdateListener {
		private boolean failConfigRestore;

		private FailingConfigRestoreListener(GeoSparqlPlugin parent) {
			super(parent, 1L, 2L, 3L);
		}

		@Override
		protected void restoreConfigFile(Path configPath) throws IOException {
			if (failConfigRestore) {
				throw new IOException("Simulated configuration restoration failure");
			}
			super.restoreConfigFile(configPath);
		}
	}

	private static final class CountingConfigRestoreListener extends GeoSparqlUpdateListener {
		private int configRestoreCount;

		private CountingConfigRestoreListener(GeoSparqlPlugin parent) {
			super(parent, 1L, 2L, 3L);
		}

		@Override
		protected void restoreConfigFile(Path configPath) throws IOException {
			configRestoreCount++;
			super.restoreConfigFile(configPath);
		}
	}
}
