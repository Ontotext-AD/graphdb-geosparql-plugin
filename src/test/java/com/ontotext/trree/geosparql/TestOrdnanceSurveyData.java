package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * Some tests with Ordnance Survey data.
 *
 * Tests: GML and British National Grid
 */
@RunWith(Parameterized.class)
public class TestOrdnanceSurveyData extends AbstractGeoSparqlPluginTest {
	private static final Set<String> EXPECTED_RESULTS = Set.of(
			"http://data.ordnancesurvey.co.uk/id/geometry/41543-6",
			"http://data.ordnancesurvey.co.uk/id/7000000000041543");

	@Parameterized.Parameters
	public static Iterable<Object[]> params() {
		return Arrays.asList(new Object[][]{ {false}, {true} });
	}

	private boolean enableFirst;

	public TestOrdnanceSurveyData(boolean enableFirst) {
		this.enableFirst = enableFirst;
	}

	@Before
	public void setupConn() throws Exception {
		File osDir = new File("src/test/resources/ordnancesurvey");
		File[] files = osDir.listFiles();
		Arrays.sort(files, Comparator.comparing(File::getName));

		if (enableFirst) {
			enablePlugin();
		}

		connection.begin();
		for (File f : files) {
			try (FileInputStream fis = new FileInputStream(f)) {
				connection.add(fis, "urn:base", RDFFormat.TURTLE);
			}
		}
		connection.commit();

		if (! enableFirst) {
			enablePlugin();
		}
	}

	@Test
	public void projectedGmlRcc8RelationWorksForIncrementalAndFullIndexing() throws Exception {
		String query = "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
				"SELECT *\n" +
				"WHERE { <http://data.ordnancesurvey.co.uk/id/7000000000041323> geo:rcc8tpp ?f }";
		List<String> results = new ArrayList<>();
		try (TupleQueryResult tqr = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			while(tqr.hasNext()) {
				results.add(tqr.next().getValue("f").stringValue());
			}
		}
		assertEquals(EXPECTED_RESULTS.size(), results.size());
		assertEquals(EXPECTED_RESULTS, new HashSet<>(results));
	}
}
