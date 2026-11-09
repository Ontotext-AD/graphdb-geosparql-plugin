package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.util.DurableFileOperations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file that exists from the moment a GraphDB transaction starts changing GeoSPARQL state until the plugin knows how
 * that transaction ended. If it's still there at startup, the index may not match the repository, so the plugin
 * requires a force reindex.
 */
public final class GeoSparqlTransactionMarker {
	private static final String FILE_NAME = "pending-graphdb-transaction";
	private static final byte[] CONTENTS =
			"GeoSPARQL plugin state awaiting GraphDB transaction outcome\n".getBytes(StandardCharsets.UTF_8);
	private static final DurableFileOperations DURABLE_FILES = new DurableFileOperations();

	private final Path path;

	public GeoSparqlTransactionMarker(Path pluginDataDir) {
		path = resolvePath(pluginDataDir);
	}

	public static Path resolvePath(Path pluginDataDir) {
		return GeoSparqlConfig.resolveIndexPath(pluginDataDir).resolve(FILE_NAME);
	}

	public boolean exists() {
		return Files.exists(path);
	}

	public void create() throws IOException {
		DURABLE_FILES.write(path, CONTENTS);
	}

	public void remove() throws IOException {
		DURABLE_FILES.delete(path);
	}
}
