package com.ontotext.trree.geosparql;

import com.ontotext.trree.OwlimSchemaRepository;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.hamcrest.CoreMatchers;
import org.junit.Before;
import org.junit.Test;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

/**
 * Verifies that the prefix tree and the precision can be changed and that the changes affect the index on the next
 * full indexing operation. When force reindex fails, transaction abort restores the prior runtime configuration and
 * Lucene index before removing the pending marker. Regression provenance:
 * https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/2.
 */
public class TestChangeSettings extends AbstractGeoSparqlPluginTest {
    private static final String SPATIAL_PREFIX_FIELD = "geoData1";

    @Before
    public void setupConn() throws Exception {
        importData("simple_features_geometries.rdf", RDFFormat.RDFXML);
        importData("geosparql-example.rdf", RDFFormat.RDFXML);
    }

    private List<Value> executeExampleQuery(String number) throws Exception {
        return executeSparqlQueryWithResultFromFile("example" + number, "f");
    }

    private void assertQuery() throws Exception {
        List<Value> result = executeExampleQuery("1i");
        assertEquals(2, result.size());
        assertEquals(Set.of(
                SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#B"),
                SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#F")),
                new HashSet<>(result));
    }

    private String getSetting(String settingName) {
        try (RepositoryConnection connection = repository.getConnection()) {
            TupleQuery tq = connection.prepareTupleQuery(
                    String.format("PREFIX : <http://www.ontotext.com/plugins/geosparql#>\n" +
                            "\n" +
                            "SELECT ?setting WHERE {\n" +
                            "    _:s :%s ?setting;\n" +
                            "}", settingName));
            try (TupleQueryResult tqr = tq.evaluate()) {
                return tqr.next().getBinding("setting").getValue().stringValue();
            }
        }
    }

    private String getSettingFromFile(String settingName) throws IOException {
        Path settingsFile = Paths.get(((OwlimSchemaRepository)((SailRepository)repository).getSail()).getStorageFolder(), "GeoSPARQL", "v3", "config.properties");
        Properties properties = new Properties();
        try (FileReader reader = new FileReader(settingsFile.toFile())) {
            properties.load(reader);
        }
        String value = properties.getProperty(settingName);
        return value == null ? null : value.toLowerCase();
    }

    private void assertSetting(String settingNameIRI, String settingNameFile, String settingValue) throws IOException {
        assertEquals(settingValue, getSetting(settingNameIRI));
        if (settingNameFile != null) {
            assertEquals(settingValue, getSettingFromFile(settingNameFile));
        }
    }

    private void setSetting(String settingName, String settingValue) {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.prepareUpdate(String.format("PREFIX : <http://www.ontotext.com/plugins/geosparql#>\n" +
                    "INSERT DATA { _:s :%s '''%s''' }", settingName, settingValue)).execute();
        }
    }

	private void setMultipleSettings(String settingName, String settingValue, String settingName1, String settingValue1) {
		try (RepositoryConnection connection = repository.getConnection()) {
			connection.prepareUpdate(String.format("PREFIX : <http://www.ontotext.com/plugins/geosparql#>\n" +
					"INSERT DATA { _:s :%s '''%s'''; :%s '''%s'''. }", settingName, settingValue, settingName1, settingValue1)).execute();
		}
	}

    private void assertPrefixTreeSettings(String requested, String current, boolean persisted) throws IOException {
        assertSetting("prefixTree", persisted ? "prefixtree" : null, requested);
        assertSetting("currentPrefixTree", persisted ? "prefixtree.current" : null, current);
    }

    private void assertPrecisionSettings(String requested, String current, boolean persisted) throws IOException {
        assertSetting("precision", persisted ? "precision" : null, requested);
        assertSetting("currentPrecision", persisted ? "precision.current" : null, current);
    }

    private Set<BytesRef> readSpatialTerms() throws IOException {
        Path indexPath = GeoSparqlConfig.resolveIndexPath(getGeoSparqlStorageDir().toPath());
        Set<BytesRef> result = new LinkedHashSet<>();
        try (FSDirectory directory = FSDirectory.open(indexPath);
                DirectoryReader reader = DirectoryReader.open(directory)) {
            Terms terms = MultiTerms.getTerms(reader, SPATIAL_PREFIX_FIELD);
            assertNotNull("The index must contain spatial prefix terms", terms);
            TermsEnum termsIterator = terms.iterator();
            for (BytesRef term = termsIterator.next(); term != null; term = termsIterator.next()) {
                result.add(BytesRef.deepCopyOf(term));
            }
        }
        assertFalse("The index must contain spatial prefix terms", result.isEmpty());
        return result;
    }

    @Test
    public void untouchedDefaultsActivateAndPersistWhenPluginIsEnabled() throws Exception {
        assertPrefixTreeSettings("quad", "quad", false);
        assertPrecisionSettings("11", "11", false);

        enablePlugin();
        assertQuery();
        assertPrefixTreeSettings("quad", "quad", true);
        assertPrecisionSettings("11", "11", true);
        readSpatialTerms();

        restartRepository();
        assertQuery();
        assertPrefixTreeSettings("quad", "quad", true);
        assertPrecisionSettings("11", "11", true);
    }

    @Test
    public void prefixTreeChangesActivateOnIndexBuildAndPersistAcrossRestart() throws Exception {
        assertPrefixTreeSettings("quad", "quad", false);

        setSetting("prefixTree", "geohash");
        assertPrefixTreeSettings("geohash", "quad", true);

        enablePlugin();
        assertQuery();
        assertPrefixTreeSettings("geohash", "geohash", true);
        Set<BytesRef> geohashTerms = readSpatialTerms();

        restartRepository();
        assertQuery();
        assertPrefixTreeSettings("geohash", "geohash", true);

        setSetting("prefixTree", "quad");
        assertQuery();
        assertPrefixTreeSettings("quad", "geohash", true);

        restartRepository();
        assertQuery();
        assertPrefixTreeSettings("quad", "geohash", true);
        assertEquals("A pending prefix-tree change must not alter the existing index",
                geohashTerms, readSpatialTerms());

        forceReindex();
        assertQuery();
        assertPrefixTreeSettings("quad", "quad", true);
        assertNotEquals("Rebuilding with a different prefix tree must change the spatial terms",
                geohashTerms, readSpatialTerms());

        restartRepository();
        assertQuery();
        assertPrefixTreeSettings("quad", "quad", true);
    }

    @Test
    public void precisionChangesActivateOnIndexBuildAndPersistAcrossRestart() throws Exception {
        assertPrecisionSettings("11", "11", false);

        setSetting("precision", "20");
        assertPrecisionSettings("20", "11", true);

        enablePlugin();
        assertQuery();
        assertPrecisionSettings("20", "20", true);
        Set<BytesRef> precision20Terms = readSpatialTerms();

        restartRepository();
        assertQuery();
        assertPrecisionSettings("20", "20", true);

        setSetting("precision", "15");
        assertQuery();
        assertPrecisionSettings("15", "20", true);

        restartRepository();
        assertQuery();
        assertPrecisionSettings("15", "20", true);
        assertEquals("A pending precision change must not alter the existing index",
                precision20Terms, readSpatialTerms());

        forceReindex();
        assertQuery();
        assertPrecisionSettings("15", "15", true);
        assertNotEquals("Rebuilding with a different precision must change the spatial terms",
                precision20Terms, readSpatialTerms());

        restartRepository();
        assertQuery();
        assertPrecisionSettings("15", "15", true);
    }

	@Test
	public void failedForceReindexRestoresRuntimeSettingsAndPreviousIndex() throws Exception {
		enablePlugin();
		assertQuery();
		Set<BytesRef> committedTerms = readSpatialTerms();
		setMultipleSettings("prefixTree", "geohash", "precision", "20");

		GeoSparqlPlugin plugin = activePlugin();
		FailingFreshIndexIndexer indexer = new FailingFreshIndexIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		assertThrows(RepositoryException.class, this::forceReindex);

		assertEquals("quad", getSettingFromFile("prefixtree.current"));
		assertEquals("11", getSettingFromFile("precision.current"));
		assertEquals("quad", getSetting("currentPrefixTree"));
		assertEquals("11", getSetting("currentPrecision"));
		assertEquals(committedTerms, readSpatialTerms());
		assertQuery();
		assertFalse(Files.exists(GeoSparqlTransactionMarker.resolvePath(getGeoSparqlStorageDir().toPath())));
	}

    @Test
    public void invalidPrecisionCombinationsReportApplicablePrefixTreeRange() {
        Object[][] cases = {
                {"geohash precision", "prefixTree", "geohash", "precision", "25",
                        "GEOHASH prefix tree requires precision values between 1 and "
                                + GeohashPrefixTree.getMaxLevelsPossible()},
                {"quad precision", "prefixTree", "quad", "precision", "51",
                        "QUAD prefix tree requires precision values between 1 and "
                                + QuadPrefixTree.MAX_LEVELS_POSSIBLE},
                {"stored precision with geohash", "precision", "25", "prefixTree", "geohash",
                        "GEOHASH prefix tree requires precision values between 1 and "
                                + GeohashPrefixTree.getMaxLevelsPossible()},
                {"negative precision", "prefixTree", "quad", "precision", "-1",
                        "QUAD prefix tree requires precision values between 1 and "
                                + QuadPrefixTree.MAX_LEVELS_POSSIBLE}
        };

        for (Object[] testCase : cases) {
            String label = (String) testCase[0];
            setSetting((String) testCase[1], (String) testCase[2]);

            RepositoryException exception = assertThrows(label, RepositoryException.class,
                    () -> setSetting((String) testCase[3], (String) testCase[4]));
            assertThat(label, exception.getMessage(), CoreMatchers.containsString((String) testCase[5]));
        }
    }

	@Test
	public void multipleSettingsUpdatePrefixTreeAndPrecision() throws Exception {
		setMultipleSettings("prefixTree", "quad", "precision", "25");
		setMultipleSettings("prefixTree", "geohash", "precision", "20");

		assertSetting("prefixTree", "prefixtree", "geohash");
		assertSetting("precision", "precision", "20");
	}

	private GeoSparqlPlugin activePlugin() {
		return (GeoSparqlPlugin) ((OwlimSchemaRepository) ((SailRepository) repository).getSail())
				.getPlugin("GeoSPARQL");
	}

	private static final class FailingFreshIndexIndexer extends LuceneGeoIndexer {
		private FailingFreshIndexIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		public void freshIndex() throws Exception {
			super.freshIndex();
			throw new IOException("Simulated GeoSPARQL index rebuild failure.");
		}
	}
}
