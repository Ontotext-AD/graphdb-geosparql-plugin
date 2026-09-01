package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.sdk.PluginException;
import org.apache.sis.referencing.CRS;
import org.opengis.metadata.citation.Citation;
import org.opengis.util.FactoryException;
import org.opengis.util.InternationalString;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Identifies the CRS transformation inputs used to derive persisted candidate envelopes. */
final class CrsEnvironmentFingerprint {
	private static final String CANDIDATE_PROJECTION_COMPATIBILITY =
			"crs84-conservative-envelope-v3-jena-cleanup-3d-horizontal-union";
	private static final String SIS_DATA = "SIS_DATA";
	private static final String DATUM_CHANGES_DIRECTORY = "DatumChanges";

	private CrsEnvironmentFingerprint() {
	}

	static String current() {
		String configuredDataDirectory = System.getenv(SIS_DATA);
		Path sisData = null;
		if (configuredDataDirectory != null && !configuredDataDirectory.trim().isEmpty()) {
			try {
				sisData = Path.of(configuredDataDirectory.trim());
			} catch (InvalidPathException e) {
				// An invalid data directory cannot contribute usable transformation grids.
			}
		}
		try {
			return compute(sisImplementationVersion(), epsgDatasetIdentity(), sisData);
		} catch (IOException e) {
			throw new PluginException("Unable to fingerprint Apache SIS datum transformation grids.", e);
		}
	}

	static String compute(String sisVersion, String epsgDatasetIdentity, Path sisData) throws IOException {
		MessageDigest digest = sha256();
		update(digest, "candidate-projection", CANDIDATE_PROJECTION_COMPATIBILITY);
		update(digest, "apache-sis", sisVersion);
		update(digest, "epsg-dataset", epsgDatasetIdentity);
		updateDatumChanges(digest, sisData);
		return "sha256:" + HexFormat.of().formatHex(digest.digest());
	}

	private static String sisImplementationVersion() {
		String version = CRS.class.getPackage().getImplementationVersion();
		return version == null ? "unknown" : version;
	}

	private static String epsgDatasetIdentity() {
		try {
			Citation authority = CRS.getAuthorityFactory("EPSG").getAuthority();
			Date editionDate = authority.getEditionDate();
			return "title=" + internationalString(authority.getTitle())
					+ ";edition=" + internationalString(authority.getEdition())
					+ ";editionDate=" + (editionDate == null ? "unknown" : editionDate.getTime());
		} catch (FactoryException e) {
			return "unavailable:" + e.getClass().getName();
		}
	}

	private static String internationalString(InternationalString value) {
		return value == null ? "unknown" : value.toString(Locale.ROOT);
	}

	private static void updateDatumChanges(MessageDigest digest, Path sisData) throws IOException {
		if (sisData == null) {
			update(digest, "datum-changes", "not-configured");
			return;
		}
		Path datumChanges = sisData.resolve(DATUM_CHANGES_DIRECTORY);
		if (!Files.isDirectory(datumChanges)) {
			update(digest, "datum-changes", "absent");
			return;
		}

		List<Path> files;
		try (Stream<Path> paths = Files.walk(datumChanges, FileVisitOption.FOLLOW_LINKS)) {
			files = paths.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(path -> normalizedRelativePath(datumChanges, path)))
					.toList();
		}
		update(digest, "datum-changes-file-count", Integer.toString(files.size()));
		for (Path file : files) {
			update(digest, "datum-change-path", normalizedRelativePath(datumChanges, file));
			digest.update(ByteBuffer.allocate(Long.BYTES).putLong(Files.size(file)).array());
			try (InputStream input = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = input.read(buffer)) != -1) {
					digest.update(buffer, 0, count);
				}
			}
		}
	}

	private static String normalizedRelativePath(Path root, Path file) {
		return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
	}

	private static void update(MessageDigest digest, String name, String value) {
		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
		digest.update(nameBytes);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
		digest.update(valueBytes);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable.", e);
		}
	}
}
