package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestGeoSparqlPluginWithRDFStarData extends AbstractGeoSparqlPluginTest {
	private static final String EMBEDDED_A_TRIPLE = "<<http://example.org/ApplicationSchema#A http://www.w3.org/1999/02/22-rdf-syntax-ns#type http://example.org/ApplicationSchema#PlaceOfInterest>>";
	private static final String EMBEDDED_B_TRIPLE = "<<http://example.org/ApplicationSchema#B http://www.w3.org/1999/02/22-rdf-syntax-ns#type http://example.org/ApplicationSchema#PlaceOfInterest>>";

	private static final String WITHIN_FROM_BOUND_EMBEDDED_SUBJECT =
			"PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
			"PREFIX my: <http://example.org/ApplicationSchema#>\n" +
			"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
			"SELECT ?feature WHERE {\n" +
			"  <<my:B rdf:type my:PlaceOfInterest>> geo:sfWithin ?feature .\n" +
			"}";

	private static final String WITHIN_FROM_BOUND_EMBEDDED_OBJECT =
			"PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n" +
			"PREFIX my: <http://example.org/ApplicationSchema#>\n" +
			"PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
			"SELECT ?feature WHERE {\n" +
			"  ?feature geo:sfWithin <<my:A rdf:type my:PlaceOfInterest>> .\n" +
			"}";

	@Before
	public void setupConn() throws Exception {
		importData("geosparql-rdf-star-example.ttls", RDFFormat.TURTLESTAR);
		enablePlugin();
	}

	@Test
	public void boundEmbeddedSubjectUsesIndexedWithinRelation() throws Exception {
		assertEmbeddedFeatureResults(WITHIN_FROM_BOUND_EMBEDDED_SUBJECT);
	}

	@Test
	public void boundEmbeddedObjectUsesIndexedWithinRelation() throws Exception {
		assertEmbeddedFeatureResults(WITHIN_FROM_BOUND_EMBEDDED_OBJECT);
	}

	private void assertEmbeddedFeatureResults(String query) throws Exception {
		List<Value> result = executeSparqlQueryWithResult(query, "feature");
		List<String> embeddedFeatures = new ArrayList<>();
		for (Value value : result) {
			if (value instanceof Triple) {
				embeddedFeatures.add(value.stringValue());
			}
		}
		Collections.sort(embeddedFeatures);
		Assert.assertEquals(List.of(EMBEDDED_A_TRIPLE, EMBEDDED_B_TRIPLE), embeddedFeatures);
	}
}
