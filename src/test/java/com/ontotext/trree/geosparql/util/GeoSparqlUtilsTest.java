package com.ontotext.trree.geosparql.util;

import com.ontotext.trree.geosparql.GeoSparqlConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * @author Tsvetan Dimitrov <tsvetan.dimitrov@ontotext.com>
 * @since 01 Oct 2015.
 */
public class GeoSparqlUtilsTest {
    @Rule
    public TemporaryFolder tmpFolder = new TemporaryFolder();

    @Test
    public void testCanSaveAndRead1() {
        test(true, GeoSparqlConfig.PrefixTree.QUAD, 10, GeoSparqlConfig.PrefixTree.GEOHASH, 5, true);
    }

    @Test
    public void testCanSaveAndRead2() {
        test(false, GeoSparqlConfig.PrefixTree.GEOHASH, 7, GeoSparqlConfig.PrefixTree.QUAD, 12, false);
    }

    @Test
    public void testCanSaveAndRead3() {
        test(false, GeoSparqlConfig.PrefixTree.S2, 7, GeoSparqlConfig.PrefixTree.S2, 12, false);
    }

    private void test(boolean enabled, GeoSparqlConfig.PrefixTree prefixTree, int precision,
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
}
