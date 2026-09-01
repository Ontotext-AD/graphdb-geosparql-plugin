package com.ontotext.trree.geosparql;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Read-only Apache SIS data directory status used for startup diagnostics.
 */
final class CrsDataEnvironment {
	static final String SIS_DATA = "SIS_DATA";

	enum Status {
		UNSET,
		CONFIGURED,
		UNUSABLE
	}

	private final Status status;
	private final String message;

	private CrsDataEnvironment(Status status, String message) {
		this.status = status;
		this.message = message;
	}

	static CrsDataEnvironment inspectSystem() {
		return inspect(System.getenv(SIS_DATA), Files::isDirectory, Files::isReadable);
	}

	static CrsDataEnvironment inspect(String sisData, Predicate<Path> isDirectory, Predicate<Path> isReadable) {
		if (sisData == null || sisData.trim().isEmpty()) {
			return new CrsDataEnvironment(Status.UNSET, "GeoSPARQL CRS data: SIS_DATA is not set. "
					+ "CRS84/default GeoSPARQL data is supported. Projected/EPSG CRS data may require "
					+ "Apache SIS data and grid files; unsupported CRS will fail indexing/query evaluation.");
		}

		String configuredPath = sisData.trim();
		final Path sisDataPath;
		try {
			sisDataPath = Path.of(configuredPath);
		} catch (InvalidPathException e) {
			return unusable(configuredPath, "is not a valid filesystem path");
		}

		if (!isDirectory.test(sisDataPath)) {
			return unusable(configuredPath, "does not point to a directory");
		}
		if (!isReadable.test(sisDataPath)) {
			return unusable(configuredPath, "is not readable");
		}

		return new CrsDataEnvironment(Status.CONFIGURED, "GeoSPARQL CRS data: SIS_DATA=" + configuredPath
				+ " is a readable directory. Projected/EPSG CRS support depends on the CRS definitions "
				+ "and grid files available in that directory. Unsupported CRS will fail indexing/query evaluation.");
	}

	private static CrsDataEnvironment unusable(String configuredPath, String reason) {
		return new CrsDataEnvironment(Status.UNUSABLE, "GeoSPARQL CRS data: SIS_DATA=" + configuredPath + " "
				+ reason + ". CRS84/default GeoSPARQL data is supported, but CRS data from SIS_DATA "
				+ "will not be available. Unsupported CRS will fail indexing/query evaluation.");
	}

	void log(Logger logger) {
		if (status == Status.UNUSABLE) {
			logger.warn(message);
		} else {
			logger.info(message);
		}
	}

	Status status() {
		return status;
	}

	String message() {
		return message;
	}
}
