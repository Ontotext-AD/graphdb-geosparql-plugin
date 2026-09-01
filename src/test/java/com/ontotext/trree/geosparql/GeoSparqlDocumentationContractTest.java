package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeoSparqlDocumentationContractTest extends AbstractGeoSparqlPluginTest {
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX ex: <http://example.com/geosparql-docs-contract/>\n";
	private static final String DOCUMENTED_FORCE_REINDEX_UPDATE = ""
			+ "PREFIX plugin: <http://www.ontotext.com/plugins/geosparql#>\n"
			+ "\n"
			+ "INSERT DATA {\n"
			+ "  [] plugin:forceReindex true\n"
			+ "}";

	@Test
	public void readmeContainsRunnableForceReindexUpdate() throws IOException {
		String readme = Files.readString(Path.of("README.md"));

		assertTrue(readme.contains(DOCUMENTED_FORCE_REINDEX_UPDATE));
		enablePlugin();
		executeSparqlUpdateQuery(DOCUMENTED_FORCE_REINDEX_UPDATE);
	}

	@Test
	public void geofFunctionsAreAvailableWhenPluginIsDisabled() {
		assertAsk(PREFIXES
				+ "ASK {\n"
				+ "  FILTER(geof:sfWithin(\n"
				+ "    \"POLYGON((1 1,1 2,2 2,2 1,1 1))\"^^geo:wktLiteral,\n"
				+ "    \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral\n"
				+ "  ))\n"
				+ "}");
	}

	@Test
	public void documentedPropertyRelationPredicatesAreRegistered() {
		Set<IRI> expected = Set.of(
				GeoConstants.GEO_SF_EQUALS,
				GeoConstants.GEO_SF_DISJOINT,
				GeoConstants.GEO_SF_INTERSECTS,
				GeoConstants.GEO_SF_TOUCHES,
				GeoConstants.GEO_SF_CROSSES,
				GeoConstants.GEO_SF_WITHIN,
				GeoConstants.GEO_SF_CONTAINS,
				GeoConstants.GEO_SF_OVERLAPS,
				GeoConstants.GEO_EH_EQUALS,
				GeoConstants.GEO_EH_DISJOINT,
				GeoConstants.GEO_EH_MEET,
				GeoConstants.GEO_EH_OVERLAP,
				GeoConstants.GEO_EH_COVERS,
				GeoConstants.GEO_EH_COVERED_BY,
				GeoConstants.GEO_EH_INSIDE,
				GeoConstants.GEO_EH_CONTAINS,
				GeoConstants.GEO_RCC8_EQ,
				GeoConstants.GEO_RCC8_DC,
				GeoConstants.GEO_RCC8_EC,
				GeoConstants.GEO_RCC8_PO,
				GeoConstants.GEO_RCC8_TPPI,
				GeoConstants.GEO_RCC8_TPP,
				GeoConstants.GEO_RCC8_NTPP,
				GeoConstants.GEO_RCC8_NTPPI);

		Set<IRI> actual = Arrays.stream(GeoSparqlPropertyRelation.values())
				.map(GeoSparqlPropertyRelation::getPredicateUri)
				.collect(Collectors.toSet());

		assertEquals(expected, actual);
	}

	@Test
	public void indexedPropertyRelationsCoverSimpleFeaturesEgenhoferAndRcc8() {
		executeSparqlUpdateQuery(PREFIXES
				+ "INSERT DATA {\n"
				+ "  ex:outer a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:outerGeom .\n"
				+ "  ex:outerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "  ex:inner a geo:Feature ;\n"
				+ "    geo:hasDefaultGeometry ex:innerGeom .\n"
				+ "  ex:innerGeom a geo:Geometry ;\n"
				+ "    geo:asWKT \"POLYGON((1 1,1 2,2 2,2 1,1 1))\"^^geo:wktLiteral .\n"
				+ "}");

		enablePlugin();

		assertAsk(PREFIXES + "ASK { ex:inner geo:sfWithin ex:outer }");
		assertAsk(PREFIXES + "ASK { ex:inner geo:ehInside ex:outer }");
		assertAsk(PREFIXES + "ASK { ex:inner geo:rcc8ntpp ex:outer }");
		assertAsk(PREFIXES
				+ "ASK {\n"
				+ "  ex:innerGeom geo:sfWithin\n"
				+ "    \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
				+ "}");
	}

	private void assertAsk(String query) {
		assertTrue(connection.prepareBooleanQuery(QueryLanguage.SPARQL, query).evaluate());
	}
}
