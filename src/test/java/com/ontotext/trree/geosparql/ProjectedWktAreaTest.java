package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the issue report in {@code docs/issues/GDB-10773-area/GDB-10773.md}.
 *
 * <p>The issue report and supplied RDF data are retained verbatim in {@code docs/issues/GDB-10773-area/}. The
 * executable copy of the supplied data is {@code src/test/resources/issues/epsg-25833-metric-areas.ttl}.
 *
 * <p>The supplied data also contains {@code CURVEPOLYGON}, {@code COMPOUNDCURVE}, and {@code MULTISURFACE} geometries
 * outside the parcel query. Index setup uses {@code ignoreErrors} because Jena does not support those WKT geometry
 * types; all query-relevant parcel polygons remain present and are evaluated.
 */
public class ProjectedWktAreaTest extends AbstractGeoSparqlPluginTest {
	private static final double GDAL_AREA_TOLERANCE_SQUARE_METRES = 0.01;

	/**
	 * Reproduces the ticket's Tegel parcel query and verifies that {@code geoext:area} evaluates each EPSG:25833 WKT
	 * literal in its projected CRS and matches the {@code geo:hasMetricArea} value calculated by GDAL for every result.
	 */
	@Test
	public void projectedWktAreasMatchStoredGdalAreas() throws Exception {
		importData("issues/epsg-25833-metric-areas.ttl", RDFFormat.TURTLE);
		executePluginControl(GeoSparqlPlugin.IGNORE_ERRORS_PREDICATE_IRI, VF.createLiteral(true));
		enablePlugin();

		String query = """
				PREFIX geo: <http://www.opengis.net/ont/geosparql#>
				PREFIX geoext: <http://rdf.useekm.com/ext#>
				PREFIX xp: <https://graphdb.accordproject.eu/resource/xplanung/>

				SELECT ?f ?plotCoverage ?gdalArea ?gdbArea ?deltaGdal WHERE {
				  ?f geo:hasDefaultGeometry ?g ;
				     xp:plotCoverage ?plotCoverage .
				  ?g geo:asWKT ?wkt ;
				     geo:hasMetricArea ?gdalArea .
				  BIND(geoext:area(?wkt) AS ?gdbArea)
				  BIND(abs(?gdbArea - ?gdalArea) AS ?deltaGdal)
				}
				ORDER BY DESC(?deltaGdal)
				""";

		int evaluatedParcels = 0;
		try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			while (result.hasNext()) {
				BindingSet bindings = result.next();
				assertTrue(bindings.hasBinding("gdbArea"));
				assertTrue(bindings.hasBinding("deltaGdal"));

				double gdbArea = ((Literal) bindings.getValue("gdbArea")).doubleValue();
				double gdalArea = ((Literal) bindings.getValue("gdalArea")).doubleValue();
				assertEquals(gdalArea, gdbArea, GDAL_AREA_TOLERANCE_SQUARE_METRES);
				evaluatedParcels++;
			}
		}

		assertEquals(47, evaluatedParcels);
	}
}
