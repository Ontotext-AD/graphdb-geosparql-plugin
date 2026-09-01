package com.ontotext.trree.geosparql;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that a process crash while GeoSPARQL state awaits a GraphDB transaction outcome leaves restart fail closed
 * and requiring force reindex, so an enabled configuration never trusts a stale Lucene index. Regression provenance:
 * https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/2.
 */
public class GeoSparqlProcessCrashRecoveryTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void restartFailsClosedAfterPendingMarkerCreation() throws Exception {
		assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary.AFTER_MARKER);
	}

	@Test
	public void restartFailsClosedAfterConfigurationReplacement() throws Exception {
		assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary.AFTER_CONFIG_REPLACEMENT);
	}

	@Test
	public void restartFailsClosedAfterProvisionalLuceneCommit() throws Exception {
		assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary.AFTER_PROVISIONAL_COMMIT);
	}

	@Test
	public void restartFailsClosedAfterGraphDbCommitBeforeMarkerRemoval() throws Exception {
		assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary.AFTER_GRAPHDB_COMMIT);
	}

	@Test
	public void restartFailsClosedDuringAbortRestoration() throws Exception {
		assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary.DURING_ABORT_RESTORATION);
	}

	private void assertRestartFailsClosed(GeoSparqlCrashProcess.CrashBoundary boundary) throws Exception {
		Path managerDir = tmpFolder.newFolder(boundary.name().toLowerCase()).toPath();
		Process process = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", testClassPath(), GeoSparqlCrashProcess.class.getName(), boundary.name(), managerDir.toString())
				.redirectErrorStream(true)
				.start();
		assertTrue("Crash process did not terminate", process.waitFor(30, TimeUnit.SECONDS));
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(output, GeoSparqlCrashProcess.HALT_CODE, process.exitValue());

		Path dataDir = findPluginDataDir(managerDir);
		GeoSparqlConfig persistedConfig = GeoSparqlUtils.readConfig(dataDir);
		assertEquals(boundary.enabledAfterCrash(), persistedConfig.isEnabled());
		assertTrue(Files.exists(GeoSparqlTransactionMarker.resolvePath(dataDir)));
		assertEquals(boundary.newIndexCommitPublished()
					? List.of("POINT(1 1)", "POINT(2 2)") : List.of("POINT(1 1)"),
				committedSourceLexicalForms(dataDir));

		LuceneGeoIndexer restarted = new LuceneGeoIndexer(
				GeoSparqlCrashProcess.plugin(dataDir, persistedConfig));
		restarted.initialize();
		PluginException failure = assertThrows(PluginException.class,
				() -> restarted.getSourceGeometryLiteralsFor(0));
		assertTrue(failure.getMessage().contains("pending GraphDB transaction"));
		assertTrue(failure.getMessage().contains("force-reindex"));
	}

	private String testClassPath() {
		return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
	}

	private Path findPluginDataDir(Path managerDir) throws Exception {
		try (var paths = Files.walk(managerDir)) {
			Path marker = paths.filter(path -> path.getFileName().toString().equals("pending-graphdb-transaction"))
					.findFirst()
					.orElseThrow(() -> new AssertionError("Crash process did not leave a pending marker."));
			return marker.getParent().getParent().getParent();
		}
	}

	private List<String> committedSourceLexicalForms(Path dataDir) throws Exception {
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir));
				DirectoryReader reader = DirectoryReader.open(directory)) {
			List<String> lexicalForms = new ArrayList<>();
			for (int documentId = 0; documentId < reader.maxDoc(); documentId++) {
				lexicalForms.add(reader.document(documentId).get("geoExactLexicalForm"));
			}
			Collections.sort(lexicalForms);
			return lexicalForms;
		}
	}
}
