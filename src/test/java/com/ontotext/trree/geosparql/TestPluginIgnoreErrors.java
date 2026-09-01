package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TestPluginIgnoreErrors extends AbstractGeoSparqlPluginTest {
	private static final String EX_NS = "http://example.com/ignore-errors/";
	private static final IRI VALID_WKT = VF.createIRI(EX_NS, "validWkt");
	private static final IRI INVALID_WKT = VF.createIRI(EX_NS, "invalidWkt");
	private static final IRI VALID_GML = VF.createIRI(EX_NS, "validGml");
	private static final IRI INVALID_GML = VF.createIRI(EX_NS, "invalidGml");

	@Before
	public void setupConn() throws Exception {
		importData("geosparql-ignore-errors.ttl", RDFFormat.TURTLE);
	}

	@Test
	public void strictIndexBuildRejectsInvalidRepositoryGeometry() {
		RepositoryException exception = assertThrows(RepositoryException.class, this::enablePlugin);

		assertCauseChainContains(exception, "Could not index GeoSPARQL geometry");
		assertCauseChainContains(exception, "ignoreErrors = true");
	}

	@Test
	public void ignoreErrorsSkipsInvalidWktAndGmlWhileKeepingValidGeometryQueryable() throws Exception {
		executePluginControl(GeoSparqlPlugin.IGNORE_ERRORS_PREDICATE_IRI, VF.createLiteral(true));
		enablePlugin();

		List<Value> result = executeSparqlQueryWithResult("""
				PREFIX geo: <http://www.opengis.net/ont/geosparql#>
				PREFIX ex: <http://example.com/ignore-errors/>
				SELECT ?geometry WHERE {
				    ?geometry geo:sfWithin ex:container .
				    FILTER(?geometry IN (ex:validWkt, ex:invalidWkt, ex:validGml, ex:invalidGml))
				}
				""", "geometry");

		assertEquals(2, result.size());
		assertEquals(Set.of(VALID_WKT, VALID_GML), new HashSet<>(result));
		assertTrue(connection.hasStatement(INVALID_WKT, GeoConstants.GEO_AS_WKT, null, false));
		assertTrue(connection.hasStatement(INVALID_GML, GeoConstants.GEO_AS_GML, null, false));
	}

	private static void assertCauseChainContains(Throwable throwable, String expectedText) {
		for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains(expectedText)) {
				return;
			}
		}
		throw new AssertionError("Expected exception chain to contain: " + expectedText, throwable);
	}
}
