package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestDefaultGeometryIndexing extends AbstractGeoSparqlPluginTest {
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/default-geometry-indexing/>\n";

	@Test
	public void ignoreErrorsDoesNotHideUnsupportedQueryCrs() throws Exception {
		insertContainerAndFeature("POINT(1 1)");
		executePluginControl(GeoSparqlPlugin.IGNORE_ERRORS_PREDICATE_IRI, VF.createLiteral(true));
		enablePlugin();

		RuntimeException exception = assertThrows(RuntimeException.class, () -> ask(
				"ex:thing geo:sfWithin \"<http://www.opengis.net/def/crs/EPSG/0/999999> "
						+ "POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral"));

		assertCauseChainContains(exception, "Unsupported CRS");
		assertCauseChainContains(exception, "999999");
	}

	@Test
	public void mixedCrsPropertyRelationHasNoFalseNegativesInBothBindingDirections() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeom .\n"
				+ "  ex:containerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((24.58 41.39,24.58 41.42,24.60 41.42,24.60 41.39,24.58 41.39))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ; geo:hasDefaultGeometry ex:thingGeom .\n"
				+ "  ex:thingGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"<http://www.opengis.net/def/crs/EPSG/0/32634> POINT(799997.80 4589779.63)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfWithin ex:container .\n"
				+ "  FILTER(?s = ex:thing)\n"
				+ "}"));
		assertEquals(1, count("SELECT ?o WHERE {\n"
				+ "  ex:thing geo:sfWithin ?o .\n"
				+ "  FILTER(?o = ex:container)\n"
				+ "}"));
	}

	@Test
	public void collectionEnvelopeCandidatesUseCompleteSourceForExactEvaluation() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:collection a geo:Feature ; geo:hasDefaultGeometry ex:collectionGeom .\n"
				+ "  ex:collectionGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"GEOMETRYCOLLECTION(POINT(0 0),POINT(10 10))\"^^geo:wktLiteral .\n"
				+ "  ex:envelopeInterior a geo:Feature ; geo:hasDefaultGeometry ex:envelopeInteriorGeom .\n"
				+ "  ex:envelopeInteriorGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertEquals(0, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfIntersects ex:envelopeInterior .\n"
				+ "  FILTER(?s = ex:collection)\n"
				+ "}"));
	}

	@Test
	public void geometryLiteralUpdateReindexesGeometryAndReferencingFeature() throws Exception {
		insertContainerAndFeature("POINT(1 1)");
		enablePlugin();

		assertTrue(ask("ex:thing geo:sfWithin ex:container"));
		assertTrue(ask("ex:thingGeom geo:sfWithin ex:container"));

		executeSparqlUpdateQuery(PREFIXES
				+ "DELETE { ex:thingGeom geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral }\n"
				+ "INSERT { ex:thingGeom geo:asWKT \"POINT(9 9)\"^^geo:wktLiteral }\n"
				+ "WHERE { ex:thingGeom geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral }");

		assertFalse(ask("ex:thing geo:sfWithin ex:container"));
		assertFalse(ask("ex:thingGeom geo:sfWithin ex:container"));
	}

	@Test
	public void rolledBackGeometryUpdateLeavesRdfAndLuceneStateUnchanged() throws Exception {
		insertContainerAndFeature("POINT(1 1)");
		enablePlugin();
		assertTrue(ask("ex:thing geo:sfWithin ex:container"));

		connection.begin();
		try {
			connection.prepareUpdate(QueryLanguage.SPARQL, PREFIXES
					+ "DELETE { ex:thingGeom geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral }\n"
					+ "INSERT { ex:thingGeom geo:asWKT \"POINT(9 9)\"^^geo:wktLiteral }\n"
					+ "WHERE { ex:thingGeom geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral }").execute();
		} finally {
			connection.rollback();
		}

		assertTrue(ask("ex:thing geo:sfWithin ex:container"));
		assertTrue(ask("ex:thingGeom geo:sfWithin ex:container"));
	}

	@Test
	public void defaultGeometryUpdateReindexesFeature() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeom .\n"
				+ "  ex:containerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ; geo:hasDefaultGeometry ex:insideGeom .\n"
				+ "  ex:insideGeom a geo:Geometry ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
				+ "  ex:outsideGeom a geo:Geometry ; geo:asWKT \"POINT(9 9)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertTrue(ask("ex:thing geo:sfWithin ex:container"));

		executeSparqlUpdateQuery(PREFIXES
				+ "DELETE { ex:thing geo:hasDefaultGeometry ex:insideGeom }\n"
				+ "INSERT { ex:thing geo:hasDefaultGeometry ex:outsideGeom }\n"
				+ "WHERE { ex:thing geo:hasDefaultGeometry ex:insideGeom }");

		assertFalse(ask("ex:thing geo:sfWithin ex:container"));
	}

	@Test
	public void propertyRelationReturnsOneRowWhenSubjectHasMultipleMatchingDefaultGeometries() throws Exception {
		insertSubjectWithMultipleMatchingDefaultGeometries();
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfWithin ex:container .\n"
				+ "  FILTER(?s = ex:thing)\n"
				+ "}"));
	}

	@Test
	public void propertyRelationReturnsOneRowWhenObjectHasMultipleMatchingDefaultGeometries() throws Exception {
		insertObjectWithMultipleMatchingDefaultGeometries();
		enablePlugin();

		assertEquals(1, count("SELECT ?o WHERE {\n"
				+ "  ex:thing geo:sfWithin ?o .\n"
				+ "  FILTER(?o = ex:container)\n"
				+ "}"));
	}

	@Test
	public void subjectBoundLookupReturnsOneRowWhenMultipleSubjectGeometriesHitSameObject() throws Exception {
		insertSubjectWithMultipleMatchingDefaultGeometries();
		enablePlugin();

		assertEquals(1, count("SELECT ?o WHERE {\n"
				+ "  ex:thing geo:sfWithin ?o .\n"
				+ "  FILTER(?o = ex:container)\n"
				+ "}"));
	}

	@Test
	public void objectBoundLookupReturnsOneRowWhenMultipleObjectGeometriesHitSameSubject() throws Exception {
		insertObjectWithMultipleMatchingDefaultGeometries();
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfWithin ex:container .\n"
				+ "  FILTER(?s = ex:thing)\n"
				+ "}"));
	}

	@Test
	public void objectBoundLookupReconsidersCandidateAfterEarlierBoundGeometryExactMiss() throws Exception {
		insertObjectBoundCandidateWithEarlierExactMissAndLaterMatch();
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfWithin ex:container .\n"
				+ "  FILTER(?s = ex:thing)\n"
				+ "}"));
	}

	@Test
	public void propertyRelationReturnsOneRowWhenBothSidesAreBoundAndMultipleGeometryPairsMatch() throws Exception {
		insertBothSidesWithOnlyLaterGeometryPairMatching();
		enablePlugin();

		assertEquals(1, count("SELECT ?match WHERE {\n"
				+ "  ex:thing geo:sfWithin ex:container .\n"
				+ "  BIND(\"match\" AS ?match)\n"
				+ "}"));
	}

	@Test
	public void propertyRelationReturnsNoRowsWhenBothSidesAreBoundAndAllGeometryPairsFail() throws Exception {
		insertBothSidesWithNoMatchingGeometryPairs();
		enablePlugin();

		assertEquals(0, count("SELECT ?match WHERE {\n"
				+ "  ex:thing geo:sfWithin ex:container .\n"
				+ "  BIND(\"match\" AS ?match)\n"
				+ "}"));
	}

	@Test
	public void partitionedDisjointReturnsOneRowWhenMultipleGeometryDocumentsMatchSameEntityPair() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeom .\n"
				+ "  ex:containerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:outsideGeom1, ex:outsideGeom2 .\n"
				+ "  ex:outsideGeom1 a geo:Geometry ; geo:asWKT \"POINT(10 10)\"^^geo:wktLiteral .\n"
				+ "  ex:outsideGeom2 a geo:Geometry ; geo:asWKT \"POINT(11 11)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfDisjoint ex:container .\n"
				+ "  FILTER(?s = ex:thing)\n"
				+ "}"));
	}

	@Test
	public void partitionedDisjointRetainsOverlappingEnvelopePairInBothBindingDirections() throws Exception {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:holedArea a geo:Feature ; geo:hasDefaultGeometry ex:holedAreaGeom .\n"
				+ "  ex:holedAreaGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 10,10 10,10 0,0 0),"
				+ "(4 4,6 4,6 6,4 6,4 4))\"^^geo:wktLiteral .\n"
				+ "  ex:pointInHole a geo:Feature ; geo:hasDefaultGeometry ex:pointInHoleGeom .\n"
				+ "  ex:pointInHoleGeom a geo:Geometry ; geo:asWKT \"POINT(5 5)\"^^geo:wktLiteral .\n"
				+ "}");
		enablePlugin();

		assertEquals(1, count("SELECT ?s WHERE {\n"
				+ "  ?s geo:sfDisjoint ex:holedArea .\n"
				+ "  FILTER(?s = ex:pointInHole)\n"
				+ "}"));
		assertEquals(1, count("SELECT ?o WHERE {\n"
				+ "  ex:pointInHole geo:sfDisjoint ?o .\n"
				+ "  FILTER(?o = ex:holedArea)\n"
				+ "}"));
	}

	private void insertContainerAndFeature(String featurePointWkt) {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeom .\n"
				+ "  ex:containerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ; geo:hasDefaultGeometry ex:thingGeom .\n"
				+ "  ex:thingGeom a geo:Geometry ; geo:asWKT \"" + featurePointWkt + "\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void insertSubjectWithMultipleMatchingDefaultGeometries() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeom .\n"
				+ "  ex:containerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:insideGeom1, ex:insideGeom2 .\n"
				+ "  ex:insideGeom1 a geo:Geometry ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
				+ "  ex:insideGeom2 a geo:Geometry ; geo:asWKT \"POINT(2 2)\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void insertObjectWithMultipleMatchingDefaultGeometries() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:containerGeom1, ex:containerGeom2 .\n"
				+ "  ex:containerGeom1 a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:containerGeom2 a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 5,5 5,5 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ; geo:hasDefaultGeometry ex:thingGeom .\n"
				+ "  ex:thingGeom a geo:Geometry ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void insertObjectBoundCandidateWithEarlierExactMissAndLaterMatch() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:containerMissGeom, ex:containerHitGeom .\n"
				+ "  ex:containerMissGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 2,2 2,2 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:containerHitGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((80 80,80 82,82 82,82 80,80 80))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:boundaryGeom, ex:insideHitGeom .\n"
				+ "  ex:boundaryGeom a geo:Geometry ; geo:asWKT \"POINT(2 1)\"^^geo:wktLiteral .\n"
				+ "  ex:insideHitGeom a geo:Geometry ; geo:asWKT \"POINT(81 81)\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void insertBothSidesWithOnlyLaterGeometryPairMatching() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:containerMissGeom, ex:containerHitGeom .\n"
				+ "  ex:containerMissGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((10 10,10 12,12 12,12 10,10 10))\"^^geo:wktLiteral .\n"
				+ "  ex:containerHitGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:outsideGeom, ex:insideGeom .\n"
				+ "  ex:outsideGeom a geo:Geometry ; geo:asWKT \"POINT(8 8)\"^^geo:wktLiteral .\n"
				+ "  ex:insideGeom a geo:Geometry ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void insertBothSidesWithNoMatchingGeometryPairs() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:container a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:containerMissGeom1, ex:containerMissGeom2 .\n"
				+ "  ex:containerMissGeom1 a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((10 10,10 12,12 12,12 10,10 10))\"^^geo:wktLiteral .\n"
				+ "  ex:containerMissGeom2 a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((20 20,20 22,22 22,22 20,20 20))\"^^geo:wktLiteral .\n"
				+ "  ex:thing a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:outsideGeom1, ex:outsideGeom2 .\n"
				+ "  ex:outsideGeom1 a geo:Geometry ; geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
				+ "  ex:outsideGeom2 a geo:Geometry ; geo:asWKT \"POINT(2 2)\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private boolean ask(String pattern) {
		return connection.prepareBooleanQuery(QueryLanguage.SPARQL, PREFIXES + "ASK { " + pattern + " }").evaluate();
	}

	private static void assertCauseChainContains(Throwable throwable, String expectedText) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
				return;
			}
		}
		throw new AssertionError("Expected exception cause containing: " + expectedText, throwable);
	}

	private int count(String query) throws Exception {
		TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, PREFIXES + query).evaluate();
		try {
			int count = 0;
			while (result.hasNext()) {
				result.next();
				count++;
			}
			return count;
		} finally {
			result.close();
		}
	}
}
