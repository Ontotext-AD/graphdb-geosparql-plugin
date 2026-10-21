package com.ontotext.trree.geosparql;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GraphDbPluginArchiveIT {
	private static final String PLUGIN_ARCHIVE_PROPERTY = "graphdb.pluginArchive";
	private static final String PLUGIN_DIRECTORY = "geosparql-plugin/";
	private static final Pattern GEOMETRY_RUNTIME_JAR = Pattern.compile("(jena-|sis-|derby|geoapi-).*\\.jar");

	@Test
	public void assembledPluginContainsRequiredCrsRuntimeOnly() {
		Path pluginArchive = requiredPluginArchive();
		try (ZipFile archive = new ZipFile(pluginArchive.toFile())) {
			assertArchiveContains(archive, "geosparql-plugin.jar");
			assertArchiveContains(archive, "derby.jar");
			assertArchiveContains(archive, "derbyshared.jar");
			assertArchiveContains(archive, "derbytools.jar");

			assertArchiveDoesNotContain(archive, "sis-embedded-data.jar");
			assertArchiveDoesNotContain(archive, "gt-referencing.jar");
			assertArchiveDoesNotContain(archive, "gt-epsg-extension.jar");
			assertArchiveDoesNotContain(archive, "gt-epsg-hsql.jar");
			assertArchiveDoesNotContain(archive, "hsqldb.jar");
		} catch (IOException e) {
			throw new AssertionError("Unable to inspect assembled plugin archive at " + pluginArchive, e);
		}
	}

	@Test
	public void deletedRepositoriesReleaseTheirPluginClassLoaders() throws Exception {
		assertLifecycle("deleted-repositories");
	}

	@Test
	public void geoSparqlFilterFunctionsRemainAvailableAfterEitherRepositoryCloses() throws Exception {
		assertLifecycle("close-oldest");
		assertLifecycle("close-newest");
	}

	@Test
	public void repositoryRestartPreservesGeoSparqlFilterFunctions() throws Exception {
		assertLifecycle("restart");
	}

	@Test
	public void disabledPluginCanBeEnabledWithAnotherRepositoryActive() throws Exception {
		assertLifecycle("disable-enable");
	}

	@Test
	public void failedInitializationReleasesPluginClassLoader() throws Exception {
		assertLifecycle("initialization-failure");
	}

	@Test
	public void runtimeRemainsUsableAfterFailedInitializations() throws Exception {
		assertLifecycle("initialization-failure-recovery", true);
	}

	/**
	 * Dependency limitation: Apache SIS 1.6, observed with GraphDB 10.8.12 and 11.0 on Java 21, starts a reference-queue
	 * thread and registers a JVM shutdown hook that keep the class loader that loaded it reachable after GraphDB closes
	 * it, unless {@code Shutdown.stop} runs. Once SIS releases a closed class loader without {@code Shutdown.stop}, this
	 * test fails, and a closed class loader that loaded SIS can be collected. ADR 0022 records why the plugin loads SIS
	 * once per JVM.
	 */
	@Test
	public void unstoppedSisRetainsClosedPluginClassLoader() throws Exception {
		assertLifecycle("unstopped-sis");
	}

	/**
	 * Dependency limitation: an embedded Apache Derby 10.15 engine, observed with GraphDB 10.8.12 and 11.0 on Java 21,
	 * locks the SIS_DATA EPSG database for its JVM. A Derby engine in a second class loader cannot open it and reports
	 * {@code XSDB6}, and Apache SIS 1.6 falls back to its built-in CRS subset. Once Derby engines in different class
	 * loaders can share the database, this test fails, and a second class loader resolves EPSG:3006.
	 */
	@Test
	public void isolatedPluginRuntimesCannotShareEpsgDatabase() throws Exception {
		assertLifecycle("isolated-epsg-database", true);
	}

	@Test
	public void repositoriesUseEpsgDataConcurrently() throws Exception {
		assertLifecycle("concurrent-epsg-repositories", true);
	}

	@Test
	public void firstEpsgRepositoryCanBeDeletedWhileOthersQuery() throws Exception {
		assertLifecycle("delete-during-epsg-queries", true);
	}

	@Test
	public void entryPointsLoadedTogetherShareOneRuntime() throws Exception {
		assertLifecycle("concurrent-runtime-creation");
	}

	@Test
	public void repositoriesCreatedTogetherShareOneRuntime() throws Exception {
		assertLifecycle("concurrent-repository-creation", true);
	}

	@Test
	public void separatePluginInstallationsUseSeparateRuntimes() throws Exception {
		assertLifecycle("separate-installations");
	}

	@Test
	public void indexedEpsgRepositoriesShareCrsDataAndReleaseTheirPluginClassLoaders() throws Exception {
		assertLifecycle("indexed-epsg-repositories", true);
	}

	@Test
	public void recreatedRepositoryIndexesEpsgData() throws Exception {
		assertLifecycle("recreated-repository", true);
	}

	/**
	 * Dependency limitation: on Java 21.0.11 a URL class loader, and a thread, capture the AccessControlContext of the
	 * call that creates them, including the protection domain of the GraphDB entry point on the stack. The plugin runtime
	 * class loader and the dependency threads started by the first repository's calls therefore keep that closed
	 * repository class loader, which defines only the entry point, reachable once per JVM. Derby's threads do the same
	 * for the repository that first opens the SIS_DATA EPSG database. Once the JDK no longer retains these class
	 * loaders, this test fails, and the first repository's class loader can be collected.
	 */
	@Test
	public void jdkRetainsFirstRepositoryClassLoader() throws Exception {
		assertLifecycle("first-loader-retained");
	}

	/**
	 * Dependency limitation: GraphDB 11.0 keeps the last repository a thread queried in that thread's repository
	 * statistics thread-local, and with it the repository's plugin class loader, after the repository is deleted. Once
	 * GraphDB releases deleted repositories, this test fails, and the last queried repository's class loader can be
	 * collected.
	 */
	@Test
	public void graphDbRetainsLastQueriedRepositoryClassLoader() throws Exception {
		assertLifecycle("last-loader-retained");
	}

	private void assertLifecycle(String scenario) throws Exception {
		assertLifecycle(scenario, false);
	}

	private void assertLifecycle(String scenario, boolean epsgData) throws Exception {
		Path work = Files.createTempDirectory("geosparql-lifecycle-");
		Process process = null;
		try {
			Path plugins = work.resolve("plugins");
			extract(requiredPluginArchive(), "", plugins);
			Path output = work.resolve("process.log");
			// Clear JVM soft caches so collection measures strong ownership of plugin runtimes.
			ProcessBuilder builder = new ProcessBuilder(
					Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
					"-XX:SoftRefLRUPolicyMSPerMB=0",
					"-Dderby.stream.error.file=" + work.resolve("derby.log"), "-Dderby.infolog.append=true",
					"-cp", externalHostClassPath(), GeoSparqlPluginLifecycleProcess.class.getName(),
					scenario, work.resolve("repositories").toString(), plugins.toString())
					.redirectErrorStream(true).redirectOutput(output.toFile());
			if (epsgData) {
				// The archive excludes the EPSG dataset; deployments supply its SIS-DATA directory through SIS_DATA.
				Path sisData = work.resolve("sis-data");
				extract(sisEmbeddedData(), "SIS-DATA/", sisData);
				builder.environment().put("SIS_DATA", sisData.toString());
			}
			process = builder.start();
			boolean terminated = process.waitFor(120, TimeUnit.SECONDS);
			if (!terminated) {
				process.destroyForcibly().waitFor();
			}
			String diagnostics = Files.readString(output);
			String tail = diagnostics.substring(Math.max(0, diagnostics.length() - 12000));
			assertTrue("Lifecycle process timed out (" + scenario + "):\n" + tail, terminated);
			diagnostics.lines().filter(line -> line.startsWith("thread-census ")).forEach(System.out::println);
			assertEquals("Lifecycle process failed (" + scenario + "):\n" + tail, 0, process.exitValue());
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly().waitFor();
			}
			try (var paths = Files.walk(work)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static void extract(Path zip, String prefix, Path target) throws IOException {
		try (ZipFile archive = new ZipFile(zip.toFile())) {
			for (var entries = archive.entries(); entries.hasMoreElements();) {
				var entry = entries.nextElement();
				if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
					continue;
				}
				Path destination = target.resolve(entry.getName().substring(prefix.length())).normalize();
				assertTrue("Archive entry must stay inside the extraction directory", destination.startsWith(target));
				Files.createDirectories(destination.getParent());
				try (var input = archive.getInputStream(entry)) {
					Files.copy(input, destination);
				}
			}
		}
	}

	private String externalHostClassPath() throws Exception {
		Path pluginClasses = Path.of("target", "classes").toAbsolutePath().normalize();
		Path pluginArtifact = Path.of(GeoSparqlPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		// A GraphDB host provides neither the plugin nor its geometry runtime; the archive supplies both.
		return testClassPath()
				.filter(entry -> !entry.toAbsolutePath().normalize().equals(pluginClasses))
				.filter(entry -> !entry.toAbsolutePath().normalize().equals(pluginArtifact))
				.filter(entry -> !GEOMETRY_RUNTIME_JAR.matcher(entry.getFileName().toString()).matches())
				.map(Path::toString)
				.collect(Collectors.joining(File.pathSeparator));
	}

	private Path sisEmbeddedData() {
		return testClassPath()
				.filter(entry -> entry.getFileName().toString().startsWith("sis-embedded-data-"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing sis-embedded-data on the test class path"));
	}

	private Stream<Path> testClassPath() {
		String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		return Arrays.stream(classPath.split(Pattern.quote(File.pathSeparator))).map(Path::of);
	}

	private Path requiredPluginArchive() {
		String value = System.getProperty(PLUGIN_ARCHIVE_PROPERTY);
		assertNotNull("Missing system property " + PLUGIN_ARCHIVE_PROPERTY
				+ ". Run this test through the Maven package lifecycle.", value);

		Path pluginArchive = Paths.get(value).toAbsolutePath().normalize();
		assertTrue("Expected assembled plugin archive at " + pluginArchive, Files.isRegularFile(pluginArchive));
		return pluginArchive;
	}

	private void assertArchiveContains(ZipFile archive, String jarName) {
		assertNotNull("Expected assembled plugin to contain " + jarName,
				archive.getEntry(PLUGIN_DIRECTORY + jarName));
	}

	private void assertArchiveDoesNotContain(ZipFile archive, String jarName) {
		assertNull("Expected assembled plugin not to contain " + jarName,
				archive.getEntry(PLUGIN_DIRECTORY + jarName));
	}
}
