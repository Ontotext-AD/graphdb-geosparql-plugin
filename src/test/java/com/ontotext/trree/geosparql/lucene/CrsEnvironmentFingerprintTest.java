package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CrsEnvironmentFingerprintTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void fingerprintUsesGridContentButNotDataDirectoryLocationOrDatabaseLogs() throws Exception {
		Path first = tmpFolder.newFolder("first-sis-data").toPath();
		Path second = tmpFolder.newFolder("second-sis-data").toPath();
		write(first.resolve("DatumChanges/grid.bin"), "grid-a");
		write(second.resolve("DatumChanges/grid.bin"), "grid-a");
		write(first.resolve("Databases/SpatialMetadata/db.lck"), "lock-a");
		write(second.resolve("Databases/SpatialMetadata/db.lck"), "lock-b");

		String firstFingerprint = CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", first);
		String secondFingerprint = CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", second);
		assertEquals(firstFingerprint, secondFingerprint);

		Files.writeString(second.resolve("DatumChanges/grid.bin"), "grid-b");
		assertNotEquals(firstFingerprint,
				CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", second));
	}

	/**
	 * Verifies that the fingerprint includes grid files when {@code DatumChanges} is a symbolic link
	 * to a real directory. Apache SIS resolves {@code $SIS_DATA/DatumChanges} through normal
	 * filesystem symlink resolution, so the fingerprint must follow the same path.
	 */
	@Test
	public void fingerprintFollowsRootDatumChangesSymlink() throws Exception {
		assumeSymlinksSupported();

		Path sisData = tmpFolder.newFolder("sis-data").toPath();
		Path realGridDir = tmpFolder.newFolder("real-grids").toPath();
		write(realGridDir.resolve("australia.gsb"), "grid-content-v1");

		Files.createSymbolicLink(sisData.resolve("DatumChanges"), realGridDir);

		String fingerprint = CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", sisData);

		// Changing the grid content behind the symlink must change the fingerprint.
		Files.writeString(realGridDir.resolve("australia.gsb"), "grid-content-v2");
		String updated = CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", sisData);
		assertNotEquals("Fingerprint must detect grid changes behind a root DatumChanges symlink",
				fingerprint, updated);

		// The fingerprint with a symlink must match one computed from a direct directory with the
		// same grid content — the fingerprint reflects content, not link structure.
		Path directSisData = tmpFolder.newFolder("direct-sis-data").toPath();
		write(directSisData.resolve("DatumChanges/australia.gsb"), "grid-content-v2");
		assertEquals("Symlinked and direct layouts with identical content must produce equal fingerprints",
				updated,
				CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", directSisData));
	}

	/**
	 * Verifies that the fingerprint follows symbolic links on subdirectories beneath
	 * {@code DatumChanges}, not only on the root directory itself.
	 */
	@Test
	public void fingerprintFollowsSubdirectorySymlinkUnderDatumChanges() throws Exception {
		assumeSymlinksSupported();

		Path sisData = tmpFolder.newFolder("sis-data-sub").toPath();
		Files.createDirectories(sisData.resolve("DatumChanges"));
		Path realAuDir = tmpFolder.newFolder("real-au-grids").toPath();
		write(realAuDir.resolve("GDA2020_GDA94_conformal.gsb"), "au-grid-v1");

		Files.createSymbolicLink(sisData.resolve("DatumChanges/au"), realAuDir);

		String fingerprint = CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", sisData);

		// Changing the grid content behind the subdirectory symlink must change the fingerprint.
		Files.writeString(realAuDir.resolve("GDA2020_GDA94_conformal.gsb"), "au-grid-v2");
		assertNotEquals("Fingerprint must detect grid changes behind a subdirectory symlink",
				fingerprint,
				CrsEnvironmentFingerprint.compute("SIS 1.6", "EPSG 12.013", sisData));
	}

	private void write(Path path, String contents) throws Exception {
		Files.createDirectories(path.getParent());
		Files.writeString(path, contents);
	}

	private void assumeSymlinksSupported() throws Exception {
		Path probe = tmpFolder.newFolder("symlink-probe").toPath();
		Path target = probe.resolve("target");
		Path link = probe.resolve("link");
		Files.createFile(target);
		try {
			Files.createSymbolicLink(link, target);
		} catch (UnsupportedOperationException | java.io.IOException e) {
			Assume.assumeNoException("Symbolic links not supported on this filesystem", e);
		}
	}
}
