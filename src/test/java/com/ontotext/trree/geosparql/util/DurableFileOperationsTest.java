package com.ontotext.trree.geosparql.util;

import org.apache.lucene.util.Constants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

public class DurableFileOperationsTest {
	@Rule
	public TemporaryFolder tmpFolder = new TemporaryFolder();

	@Test
	public void writingFileInExistingDirectoryPersistsContents() throws Exception {
		Path target = tmpFolder.getRoot().toPath().resolve("marker");

		new DurableFileOperations().write(target, new byte[]{1});

		assertArrayEquals(new byte[]{1}, Files.readAllBytes(target));
	}

	@Test
	public void filesCanBeWrittenReplacedAndDeletedInNestedDirectories() throws Exception {
		Path target = tmpFolder.getRoot().toPath().resolve("v3/index/marker");
		DurableFileOperations operations = new DurableFileOperations();

		operations.write(target, new byte[]{1});
		assertArrayEquals(new byte[]{1}, Files.readAllBytes(target));
		operations.replace(target, new byte[]{2});
		assertArrayEquals(new byte[]{2}, Files.readAllBytes(target));
		operations.delete(target);
		assertFalse(Files.exists(target));
	}

	/**
	 * Java can't open a directory as a FileChannel on Windows: Temurin 25.0.5 on Windows Server 2025 throws
	 * AccessDeniedException. That's why DurableFileOperations skips directory syncs there. If this test starts
	 * failing, the JDK has added support, and the plugin could sync directories on Windows too.
	 */
	@Test
	public void windowsDirectoryChannelsRejectReadAccess() {
		assumeTrue(Constants.WINDOWS);

		assertThrows(AccessDeniedException.class, () -> {
			try (FileChannel ignored = FileChannel.open(tmpFolder.getRoot().toPath(), StandardOpenOption.READ)) {
			}
		});
	}

	@Test
	public void directorySynchronizationRequiresExistingDirectory() {
		Path missing = tmpFolder.getRoot().toPath().resolve("missing");

		assertThrows(NoSuchFileException.class, () -> new DurableFileOperations().forceDirectory(missing));
	}

	@Test
	public void writePropagatesFileSynchronizationFailure() {
		IOException failure = new IOException("Simulated file synchronization failure");
		DurableFileOperations operations = new DurableFileOperations() {
			@Override
			protected void writeAndForce(Path path, byte[] contents) throws IOException {
				throw failure;
			}
		};

		assertSame(failure, assertThrows(IOException.class,
				() -> operations.write(tmpFolder.getRoot().toPath().resolve("marker"), new byte[]{1})));
	}

	@Test
	public void writePropagatesDirectorySynchronizationFailure() {
		IOException failure = new IOException("Simulated directory synchronization failure");
		DurableFileOperations operations = new DurableFileOperations() {
			@Override
			protected void forceDirectory(Path directory) throws IOException {
				throw failure;
			}
		};

		assertSame(failure, assertThrows(IOException.class,
				() -> operations.write(tmpFolder.getRoot().toPath().resolve("marker"), new byte[]{1})));
	}

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
