package com.ontotext.trree.geosparql;

import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Handy class to keep all configuration together as well as provided utility methods for converting
 * the configuration to and from Properties.
 */
public class GeoSparqlConfig {
    public enum PrefixTree {
        GEOHASH(SimpleValueFactory.getInstance().createLiteral("geohash")),
        QUAD(SimpleValueFactory.getInstance().createLiteral("quad")),
        S2(SimpleValueFactory.getInstance().createLiteral("s2"));

        private Literal literal;

        PrefixTree(Literal literal) {
            this.literal = literal;
        }

        public Literal toLiteral() {
            return literal;
        }
    }

    private final static String VERSION = "3";
    private final static String LEGACY_VERSION = "2";
    private final static String PLUGIN_CONFIG_FILENAME = "config.properties";
    private final static String INDEX_DIRECTORY = "index";
    final static String ENABLED_KEY = "enabled";
    final static String CURRENT_PREFIXTREE_KEY = "prefixtree.current";
    final static String CURRENT_PRECISION = "precision.current";
    final static String PREFIXTREE_KEY = "prefixtree";
    final static String PRECISION_KEY = "precision";
    final static String IGNORE_ERRORS_KEY = "ignoreErrors";

    // Both fields are used for tuning Lucene IndexWriter
    final static String MAX_BUFFERED_DOCS_KEY = "maxBufferedDocs";
    final static String RAM_BUFFER_SIZE_MB_KEY = "ramBufferSizeMB";

    final static boolean ENABLED_DEFAULT = false;
    final static PrefixTree PREFIXTREE_DEFAULT = PrefixTree.QUAD;
    final static int PRECISION_DEFAULT = 11;
    final static boolean IGNORE_ERRORS_DEFAULT = false;
    final static int MAX_BUFFERED_DOCS_DEFAULT = 1000;

    final static double RAM_BUFFER_SIZE_MB_DEFAULT = 32.0;

    // Hardcoded min value. Setting value lower than this will slowdown building,
    // rebuilding of index and writing in it
    private final static double MIN_RAM_BUFFER_SIZE_MB = 16.0;

    // Hardcoded max values. Setting both too high can confuse the merge policy and cause over-merging.
    private final static int MAX_BUFFERED_DOCS = 5000;
    private final static double MAX_RAM_BUFFER_SIZE_MB = 512.0;

    private boolean enabled = ENABLED_DEFAULT;

    private PrefixTree currentPrefixTree = PREFIXTREE_DEFAULT;
    private int currentPrecision = PRECISION_DEFAULT;

    private PrefixTree prefixTree = PREFIXTREE_DEFAULT;
    private int precision = PRECISION_DEFAULT;
    private boolean ignoreErrors = IGNORE_ERRORS_DEFAULT;
    private int maxBufferedDocs = MAX_BUFFERED_DOCS_DEFAULT;
    private double ramBufferSizeMb = RAM_BUFFER_SIZE_MB_DEFAULT;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public PrefixTree getPrefixTree() {
        return prefixTree;
    }

    public void setPrefixTree(PrefixTree prefixTree) {
        this.prefixTree = prefixTree;
    }

    public int getPrecision() {
        return precision;
    }

    public void setPrecision(int precision) {
        this.precision = precision;
    }

    public int getMaxBufferedDocs() {
        return maxBufferedDocs;
    }

    public void setMaxBufferedDocs(int maxBufferedDocs) {
        if (maxBufferedDocs > 0 && maxBufferedDocs <= MAX_BUFFERED_DOCS) {
            this.maxBufferedDocs = maxBufferedDocs;
        } else {
            throw new PluginException("MaxBufferedDocs value should not be greater than " + MAX_BUFFERED_DOCS);
        }
    }

    public double getRamBufferSizeMb() {
        return ramBufferSizeMb;
    }

    public void setRamBufferSizeMb(double ramBufferSizeMb) {
        if (ramBufferSizeMb >= MIN_RAM_BUFFER_SIZE_MB && ramBufferSizeMb <= MAX_RAM_BUFFER_SIZE_MB) {
            this.ramBufferSizeMb = ramBufferSizeMb;
        } else {
            throw new PluginException("RamBufferSizeMb value should be in range of " + MIN_RAM_BUFFER_SIZE_MB + " to " + MAX_RAM_BUFFER_SIZE_MB);
        }
    }

    public PrefixTree getCurrentPrefixTree() {
        return currentPrefixTree;
    }

    public int getCurrentPrecision() {
        return currentPrecision;
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors;
    }

    public void setIgnoreErrors(boolean ignoreErrors) {
        this.ignoreErrors = ignoreErrors;
    }

    public void setFromProperties(Properties properties) {
        enabled = Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, Boolean.toString(ENABLED_DEFAULT)));
        ignoreErrors = Boolean.parseBoolean(properties.getProperty(IGNORE_ERRORS_KEY, Boolean.toString(IGNORE_ERRORS_DEFAULT)));

        try {
            prefixTree = PrefixTree.valueOf(properties.getProperty(PREFIXTREE_KEY, PREFIXTREE_DEFAULT.name()).toUpperCase());
        } catch (IllegalArgumentException e) {
            prefixTree = PREFIXTREE_DEFAULT;
        }

        try {
            precision = Integer.parseInt(properties.getProperty(PRECISION_KEY, Integer.toString(PRECISION_DEFAULT)));
        } catch (NumberFormatException e) {
            precision = PRECISION_DEFAULT;
        }

        try {
            currentPrefixTree = PrefixTree.valueOf(properties.getProperty(CURRENT_PREFIXTREE_KEY, PREFIXTREE_DEFAULT.name()).toUpperCase());
        } catch (IllegalArgumentException e) {
            currentPrefixTree = PREFIXTREE_DEFAULT;
        }

        try {
            currentPrecision = Integer.parseInt(properties.getProperty(CURRENT_PRECISION, Integer.toString(PRECISION_DEFAULT)));
        } catch (NumberFormatException e) {
            currentPrecision = PRECISION_DEFAULT;
        }

        try {
            maxBufferedDocs = Integer.parseInt(properties.getProperty(MAX_BUFFERED_DOCS_KEY, Integer.toString(MAX_BUFFERED_DOCS_DEFAULT)));
        } catch (NumberFormatException e) {
            maxBufferedDocs = MAX_BUFFERED_DOCS_DEFAULT;
        }

        try {
            ramBufferSizeMb = Double.parseDouble(properties.getProperty(RAM_BUFFER_SIZE_MB_KEY, Double.toString(RAM_BUFFER_SIZE_MB_DEFAULT)));
        } catch (NumberFormatException e) {
            ramBufferSizeMb = RAM_BUFFER_SIZE_MB_DEFAULT;
        }

    }

    public Properties getAsProperties() {
        Properties properties = new Properties();
        properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
        properties.setProperty(PREFIXTREE_KEY, prefixTree.name());
        properties.setProperty(PRECISION_KEY, Integer.toString(precision));
        properties.setProperty(CURRENT_PREFIXTREE_KEY, currentPrefixTree.name());
        properties.setProperty(CURRENT_PRECISION, Integer.toString(currentPrecision));
        properties.setProperty(IGNORE_ERRORS_KEY, Boolean.toString(ignoreErrors));
        properties.setProperty(MAX_BUFFERED_DOCS_KEY, Integer.toString(maxBufferedDocs));
        properties.setProperty(RAM_BUFFER_SIZE_MB_KEY, Double.toString(ramBufferSizeMb));

        return properties;
    }

    public void updateCurrentSettings() {
        currentPrecision = precision;
        currentPrefixTree = prefixTree;
    }

    public static Path resolveLegacyConfigPath(Path pluginDataDir) {
        return pluginDataDir.resolve("v" + GeoSparqlConfig.LEGACY_VERSION).resolve(PLUGIN_CONFIG_FILENAME);
    }

    public static Path resolveConfigPath(Path pluginDataDir) {
        return pluginDataDir.resolve("v" + GeoSparqlConfig.VERSION).resolve(PLUGIN_CONFIG_FILENAME);
    }

    public static Path resolveIndexPath(Path pluginDataDir) {
        return pluginDataDir.resolve("v" + GeoSparqlConfig.VERSION).resolve(INDEX_DIRECTORY);
    }
}
