package com.ontotext.trree.geosparql.util;

import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.sdk.PluginException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * @author Tsvetan Dimitrov <tsvetan.dimitrov@ontotext.com>
 * @since 01 Oct 2015.
 */
public class GeoSparqlUtilsTest {
    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void configRoundTripPreservesAllSettings() {
        assertConfigRoundTrip(true, GeoSparqlConfig.PrefixTree.QUAD, 10,
                GeoSparqlConfig.PrefixTree.GEOHASH, 5, true);
        assertConfigRoundTrip(false, GeoSparqlConfig.PrefixTree.GEOHASH, 7,
                GeoSparqlConfig.PrefixTree.QUAD, 12, false);
    }

    @Test
    public void legacyEnabledConfigMigratesDisabledForV2Reindex() throws Exception {
        Path pluginDataDir = tmpFolder.newFolder("legacy-config-migration").toPath();
        Path legacyConfigPath = GeoSparqlConfig.resolveLegacyConfigPath(pluginDataDir);
        Files.createDirectories(legacyConfigPath.getParent());

        GeoSparqlConfig legacyConfig = new GeoSparqlConfig();
        legacyConfig.setEnabled(true);
        legacyConfig.setPrefixTree(GeoSparqlConfig.PrefixTree.GEOHASH);
        legacyConfig.setPrecision(7);
        legacyConfig.setIgnoreErrors(true);
        try (OutputStream output = Files.newOutputStream(legacyConfigPath)) {
            legacyConfig.getAsProperties().store(output, "legacy GeoSPARQL configuration");
        }

        GeoSparqlUtils.migrateConfig(pluginDataDir, LoggerFactory.getLogger(GeoSparqlUtilsTest.class));

        assertTrue(Files.isReadable(GeoSparqlConfig.resolveConfigPath(pluginDataDir)));
        GeoSparqlConfig migrated = GeoSparqlUtils.readConfig(pluginDataDir);
        assertFalse("An incompatible pre-v2 index must not remain enabled", migrated.isEnabled());
        assertEquals(GeoSparqlConfig.PrefixTree.GEOHASH, migrated.getPrefixTree());
        assertEquals(7, migrated.getPrecision());
        assertTrue(migrated.isIgnoreErrors());
    }

    @Test
    public void failedConfigReplacementLeavesPreviousFileReadable() throws Exception {
        Path pluginDataDir = tmpFolder.newFolder("failed-config-replacement").toPath();
        GeoSparqlConfig previous = new GeoSparqlConfig();
        GeoSparqlUtils.saveConfig(previous, pluginDataDir);

        GeoSparqlConfig replacement = new GeoSparqlConfig();
        replacement.setEnabled(true);

        assertThrows(PluginException.class,
                () -> GeoSparqlUtils.saveConfig(replacement, pluginDataDir, new FailingAtomicMove()));
        assertFalse(GeoSparqlUtils.readConfig(pluginDataDir).isEnabled());
    }

    private void assertConfigRoundTrip(boolean enabled, GeoSparqlConfig.PrefixTree prefixTree, int precision,
                      GeoSparqlConfig.PrefixTree currentPrefixTree, int currentPrecision, boolean ignoreErrors) {
        GeoSparqlConfig config1 = new GeoSparqlConfig();

        config1.setEnabled(enabled);
        config1.setPrefixTree(currentPrefixTree);
        config1.setPrecision(currentPrecision);
        config1.updateCurrentSettings();
        config1.setPrefixTree(prefixTree);
        config1.setPrecision(precision);
        config1.setIgnoreErrors(ignoreErrors);

        GeoSparqlUtils.saveConfig(config1, tmpFolder.getRoot().toPath());

        GeoSparqlConfig config2 = GeoSparqlUtils.readConfig(tmpFolder.getRoot().toPath());

        assertEquals(enabled, config2.isEnabled());
        assertEquals(prefixTree, config2.getPrefixTree());
        assertEquals(precision, config2.getPrecision());
        assertEquals(currentPrefixTree, config2.getCurrentPrefixTree());
        assertEquals(currentPrecision, config2.getCurrentPrecision());
        assertEquals(ignoreErrors, config2.isIgnoreErrors());
    }

    private static final class FailingAtomicMove extends DurableFileOperations {
        @Override
        protected void replaceAtomically(Path stagingPath, Path targetPath) throws IOException {
            throw new IOException("Simulated atomic replacement failure");
        }
    }
}
