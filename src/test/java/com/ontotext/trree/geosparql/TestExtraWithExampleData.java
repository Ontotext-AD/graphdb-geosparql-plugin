package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * Uses sample data and sample queries from Annex B of the GeoSPARQL specification.
 */
public class TestExtraWithExampleData extends AbstractGeoSparqlPluginTest {
	@Before
	public void setupConn() throws Exception {
        importData("simple_features_geometries.rdf", RDFFormat.RDFXML);
        importData("geosparql-example.rdf", RDFFormat.RDFXML);

        enablePlugin();
	}

	// Test query that provides the geometry as a literal in a pattern (custom extension)
	@Test
	public void literalPropertyRelationResultsSurviveIndexRebuild() throws Exception {
		assertLiteralPropertyRelationResults();

		restartRepositoryAndDeleteIndex();
		enablePlugin();

		assertLiteralPropertyRelationResults();
	}

	private void assertLiteralPropertyRelationResults() throws Exception {
		List<Value> result = executeSparqlQueryWithResultFromFile("testLiteral", "f");
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#D")));
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#DExactGeom")));
		Assert.assertEquals(2, result.size());
	}

	// Test disjoint no match using index
	@Test
	public void disjointPropertyRelationReturnsNoMatchForOverlappingGeometry() throws Exception {
		List<Value> result = executeSparqlQueryWithResultFromFile("testDisjoint1", "f");
		Assert.assertTrue(result.isEmpty());
	}

	// Test disjoint match using index
	@Test
	public void disjointPropertyRelationReturnsEverySeparatedFeature() throws Exception {
		assertDisjointPropertyRelationResults();

		restartRepositoryAndDeleteIndex();
		enablePlugin();

		assertDisjointPropertyRelationResults();
	}

	private void assertDisjointPropertyRelationResults() throws Exception {
		List<Value> result = executeSparqlQueryWithResultFromFile("testDisjoint2", "f");
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#B")));
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#D")));
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#E")));
		Assert.assertTrue(result.contains(SimpleValueFactory.getInstance().createIRI("http://example.org/ApplicationSchema#F")));
		Assert.assertEquals(4, result.size());
	}
}
