package com.ontotext.trree.geosparql.util;

import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A basic utils class responsible for handling common operations regarding the initialization and configuration
 * of the plugin.
 *
 * @author Tsvetan Dimitrov <tsvetan.dimitrov@ontotext.com>
 * @since 01 Oct 2015.
 */
public class GeoSparqlUtils {
    private static final DurableFileOperations DURABLE_FILES = new DurableFileOperations();

    public static void migrateConfig(Path pluginDataDir, Logger logger) {
        GeoSparqlConfig config = new GeoSparqlConfig();

        Path configPath = GeoSparqlConfig.resolveConfigPath(pluginDataDir);
        if (!Files.exists(configPath)) {
            Path legacyConfigPath = GeoSparqlConfig.resolveLegacyConfigPath(pluginDataDir);
            if (Files.isReadable(legacyConfigPath)) {
                try (FileInputStream in = new FileInputStream(legacyConfigPath.toFile())) {
                    Properties properties = new Properties();
                    properties.load(in);
                    config.setFromProperties(properties);
                    if (config.isEnabled()) {
                        logger.info("Detected incompatible index from a previous version. Please rebuild the index manually.");
                        config.setEnabled(false);
                    }
                    saveConfig(config, pluginDataDir);
                } catch (IOException e) {
                    throw new PluginException("Cannot load GeoSPARQL configuration file.", e);
                }
            }
        }
    }

    public static GeoSparqlConfig readConfig(Path pluginDataDir) {
        GeoSparqlConfig config = new GeoSparqlConfig();

        Path configPath = GeoSparqlConfig.resolveConfigPath(pluginDataDir);
        if (Files.isReadable(configPath)) {
            try (FileInputStream in = new FileInputStream(configPath.toFile())) {
                Properties properties = new Properties();
                properties.load(in);
                config.setFromProperties(properties);
            } catch (IOException e) {
                throw new PluginException("Cannot load GeoSPARQL configuration file.", e);
            }
        }

        return config;
    }

    public static void saveConfig(GeoSparqlConfig config, Path pluginDataDir) {
        saveConfig(config, pluginDataDir, DURABLE_FILES);
    }

    static void saveConfig(GeoSparqlConfig config, Path pluginDataDir,
            DurableFileOperations durableFiles) {
        Path configPath = GeoSparqlConfig.resolveConfigPath(pluginDataDir);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Properties properties = config.getAsProperties();
            properties.store(output, "GeoSPARQL configuration");
            durableFiles.replace(configPath, output.toByteArray());
        } catch (IOException e) {
            throw new PluginException("Cannot save GeoSPARQL configuration file.", e);
        }
    }

    public static void validateParams(GeoSparqlConfig.PrefixTree prefixTree, int precision) {
        switch (prefixTree) {
            case GEOHASH:
                if (precision <= 0 || precision > GeohashPrefixTree.getMaxLevelsPossible()) {
                    throw new PluginException(constructExceptionMessage(prefixTree));
                }
                break;
            case QUAD:
                if (precision <= 0 || precision > QuadPrefixTree.MAX_LEVELS_POSSIBLE) {
                    throw new PluginException(constructExceptionMessage(prefixTree));
                }
        }
    }

    private static String constructExceptionMessage(GeoSparqlConfig.PrefixTree prefixTree) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefixTree == GeoSparqlConfig.PrefixTree.QUAD ? "QUAD" : "GEOHASH")
                .append(" prefix tree requires precision values between 1 and ")
                .append(prefixTree == GeoSparqlConfig.PrefixTree.QUAD ? QuadPrefixTree.MAX_LEVELS_POSSIBLE : GeohashPrefixTree.getMaxLevelsPossible())
                .append(".");

        return sb.toString();
    }
}
