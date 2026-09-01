package com.ontotext.trree.geosparql.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists small state files together with the directory entries that name them.
 */
public class DurableFileOperations {
	public void write(Path path, byte[] contents) throws IOException {
		createDirectoriesDurably(path.getParent());
		writeAndForce(path, contents);
		forceDirectory(path.getParent());
	}

	public void replace(Path path, byte[] contents) throws IOException {
		Path parent = path.getParent();
		createDirectoriesDurably(parent);
		Path stagingPath = createStagingFile(parent, path.getFileName().toString());
		try {
			writeAndForce(stagingPath, contents);
			replaceAtomically(stagingPath, path);
			forceDirectory(parent);
		} finally {
			Files.deleteIfExists(stagingPath);
		}
	}

	public void delete(Path path) throws IOException {
		if (Files.deleteIfExists(path)) {
			forceDirectory(path.getParent());
		}
	}

	protected Path createStagingFile(Path directory, String targetFileName) throws IOException {
		return Files.createTempFile(directory, targetFileName + ".", ".tmp");
	}

	protected void writeAndForce(Path path, byte[] contents) throws IOException {
		try (FileChannel channel = FileChannel.open(path,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			ByteBuffer buffer = ByteBuffer.wrap(contents);
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(true);
		}
	}

	protected void replaceAtomically(Path stagingPath, Path targetPath) throws IOException {
		Files.move(stagingPath, targetPath,
				StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	protected void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}

	private void createDirectoriesDurably(Path directory) throws IOException {
		if (Files.isDirectory(directory)) {
			return;
		}
		List<Path> missingDirectories = new ArrayList<>();
		for (Path current = directory; current != null && !Files.exists(current); current = current.getParent()) {
			missingDirectories.add(current);
		}
		Files.createDirectories(directory);
		Collections.reverse(missingDirectories);
		for (Path createdDirectory : missingDirectories) {
			Path parent = createdDirectory.getParent();
			if (parent != null) {
				forceDirectory(parent);
			}
		}
	}
}
