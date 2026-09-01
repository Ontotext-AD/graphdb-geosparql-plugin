package com.ontotext.trree.geosparql.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class DurableFileOperationsTest {
	@Rule
	public TemporaryFolder tmpFolder = new TemporaryFolder();

	@Test
	public void atomicReplacementForcesFileBeforeMoveAndDirectoryAfterMove() throws Exception {
		Path directory = tmpFolder.newFolder("replace-order").toPath();
		RecordingOperations operations = new RecordingOperations(directory.resolve("staging"));

		operations.replace(directory.resolve("config.properties"), new byte[]{1});

		assertEquals(List.of("force-file", "atomic-replace", "force-directory"), operations.events);
	}

	@Test
	public void creatingNestedDirectoriesForcesEachDirectoryEntry() throws Exception {
		Path existingParent = tmpFolder.newFolder("directory-order").toPath();
		Path first = existingParent.resolve("v3");
		Path second = first.resolve("index");
		RecordingOperations operations = new RecordingOperations(second.resolve("marker"));

		operations.write(second.resolve("pending-graphdb-transaction"), new byte[]{1});

		assertEquals(List.of(
				"force-directory:" + existingParent,
				"force-directory:" + first,
				"force-file",
				"force-directory:" + second), operations.events);
	}

	@Test
	public void deletionForcesContainingDirectory() throws Exception {
		Path directory = tmpFolder.newFolder("delete-order").toPath();
		Path marker = Files.write(directory.resolve("marker"), new byte[]{1});
		RecordingOperations operations = new RecordingOperations(directory.resolve("unused"));

		operations.delete(marker);

		assertFalse(Files.exists(marker));
		assertEquals(List.of("force-directory:" + directory), operations.events);
	}

	private static final class RecordingOperations extends DurableFileOperations {
		private final Path stagingPath;
		private final List<String> events = new ArrayList<>();

		private RecordingOperations(Path stagingPath) {
			this.stagingPath = stagingPath;
		}

		@Override
		protected Path createStagingFile(Path directory, String targetFileName) {
			return stagingPath;
		}

		@Override
		protected void writeAndForce(Path path, byte[] contents) {
			events.add("force-file");
		}

		@Override
		protected void replaceAtomically(Path stagingPath, Path targetPath) {
			events.add("atomic-replace");
		}

		@Override
		protected void forceDirectory(Path directory) throws IOException {
			if (events.contains("force-file") && events.contains("atomic-replace")) {
				events.add("force-directory");
			} else {
				events.add("force-directory:" + directory);
			}
		}
	}
}
