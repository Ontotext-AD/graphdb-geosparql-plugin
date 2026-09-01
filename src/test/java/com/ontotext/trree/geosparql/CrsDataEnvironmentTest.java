package com.ontotext.trree.geosparql;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CrsDataEnvironmentTest {
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void absentSisDataValuesAreUnset() {
		for (String sisData : new String[]{null, "", " \t "}) {
			CrsDataEnvironment environment = CrsDataEnvironment.inspect(sisData, path -> true, path -> true);

			assertEquals(CrsDataEnvironment.Status.UNSET, environment.status());
			assertTrue(environment.message().contains("SIS_DATA is not set"));
			assertTrue(environment.message().contains("CRS84/default GeoSPARQL data is supported"));
		}
	}

	@Test
	public void readableDirectoryIsConfigured() throws Exception {
		File sisDataDirectory = temporaryFolder.newFolder("sis-data");

		CrsDataEnvironment environment = CrsDataEnvironment.inspect(sisDataDirectory.getAbsolutePath(),
				path -> path.toFile().isDirectory(), path -> path.toFile().canRead());

		assertEquals(CrsDataEnvironment.Status.CONFIGURED, environment.status());
		assertTrue(environment.message().contains("SIS_DATA=" + sisDataDirectory.getAbsolutePath()));
		assertTrue(environment.message().contains("readable directory"));
	}

	@Test
	public void unusableSisDataPathsReportTheirFailureClass() throws Exception {
		File sisDataFile = temporaryFolder.newFile("sis-data-file");
		File missingPath = new File(temporaryFolder.getRoot(), "missing");

		assertUnusable(sisDataFile.getAbsolutePath(), path -> path.toFile().isDirectory(),
				path -> path.toFile().canRead(), "does not point to a directory");
		assertUnusable(missingPath.getAbsolutePath(), path -> path.toFile().isDirectory(),
				path -> path.toFile().canRead(), "does not point to a directory");
		assertUnusable("/opt/graphdb/sis-data", path -> true, path -> false, "is not readable");
		assertUnusable("bad\0path", path -> true, path -> true, "not a valid filesystem path");
	}

	private static void assertUnusable(String sisData, Predicate<Path> isDirectory, Predicate<Path> isReadable,
			String expectedMessage) {
		CrsDataEnvironment environment = CrsDataEnvironment.inspect(sisData, isDirectory, isReadable);

		assertEquals(CrsDataEnvironment.Status.UNUSABLE, environment.status());
		assertTrue(environment.message().contains(expectedMessage));
		assertTrue(environment.message().contains("CRS84/default GeoSPARQL data is supported"));
	}
}
