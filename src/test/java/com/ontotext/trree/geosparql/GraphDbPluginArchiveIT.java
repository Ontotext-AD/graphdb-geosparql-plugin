package com.ontotext.trree.geosparql;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GraphDbPluginArchiveIT {
	private static final String PLUGIN_ARCHIVE_PROPERTY = "graphdb.pluginArchive";
	private static final String PLUGIN_DIRECTORY = "geosparql-plugin/";

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
