package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaFunctionEvaluator;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for the issue report in {@code docs/issues/GDB-9378/GDB-9378.md}.
 *
 * <p>The issue report and supplied RDF data are retained verbatim in {@code docs/issues/GDB-9378/}. The executable
 * Tegel derivative is {@code src/test/resources/issues/GDB-9378-tegel.ttl}.
 *
 * <p>The executable fixtures differ as follows:
 * <ul>
 * <li>XML namespace declarations are present so the GML is valid XML.</li>
 * <li>Each default Geometry resource directly contains its source geometry literal.</li>
 * <li>The Tegel query fixture uses selected coordinates from the supplied data; other fixtures use compact
 * representative geometries.</li>
 * <li>Distance coverage asserts WKT/GML parity rather than a fixed square-root-of-two result.</li>
 * <li>Canonical OGC CRS URIs are used; compatibility spellings require separate explicit coverage.</li>
 * </ul>
 */
public class GmlLiteralEvaluationTest extends AbstractGeoSparqlPluginTest {
	private static final String EX = "http://example.com/gdb-9378/";
	private static final String EPSG_25831 = "http://www.opengis.net/def/crs/EPSG/0/25831";
	private static final String EPSG_25833 = "http://www.opengis.net/def/crs/EPSG/0/25833";
	private static final String PREFIXES = """
			PREFIX geo: <http://www.opengis.net/ont/geosparql#>
			PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
			PREFIX geoext: <http://rdf.useekm.com/ext#>
			PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
			PREFIX ex: <http://example.com/gdb-9378/>
			""";
	private static final String TEGEL_MULTISURFACE = """
			<gml:MultiSurface xmlns:gml="http://www.opengis.net/gml/3.2" srsName="%s">
			  <gml:surfaceMember>
			    <gml:Polygon>
			      <gml:exterior>
			        <gml:LinearRing>
			          <gml:posList>384170 5823960 384180 5823960 384180 5823970 384170 5823970 384170 5823960</gml:posList>
			        </gml:LinearRing>
			      </gml:exterior>
			    </gml:Polygon>
			  </gml:surfaceMember>
			</gml:MultiSurface>
			""".formatted(EPSG_25833).strip();
	private static final String TEGEL_INNER_POLYGON = """
			<gml:Polygon xmlns:gml="http://www.opengis.net/gml/3.2" srsName="%s">
			  <gml:exterior>
			    <gml:LinearRing>
			      <gml:posList>384172 5823962 384174 5823962 384174 5823964 384172 5823964 384172 5823962</gml:posList>
			    </gml:LinearRing>
			  </gml:exterior>
			</gml:Polygon>
			""".formatted(EPSG_25833).strip();
	private static final String TEGEL_NOTCHED_POLYGON = """
			<gml:Polygon xmlns:gml="http://www.opengis.net/gml/3.2" srsName="%s">
			  <gml:exterior>
			    <gml:LinearRing>
			      <gml:posList>
			        384170 5823960 384180 5823960 384180 5823970 384175 5823965 384170 5823970 384170 5823960
			      </gml:posList>
			    </gml:LinearRing>
			  </gml:exterior>
			</gml:Polygon>
			""".formatted(EPSG_25833).strip();
	private static final String MALGRAT_MULTISURFACE = """
			<gml:MultiSurface xmlns:gml="http://www.opengis.net/gml/3.2" srsName="%s">
			  <gml:surfaceMember>
			    <gml:Surface>
			      <gml:patches>
			        <gml:PolygonPatch>
			          <gml:exterior>
			            <gml:LinearRing>
			              <gml:posList srsDimension="2" count="5">
			                480000 4610000 480010 4610000 480010 4610010 480000 4610010 480000 4610000
			              </gml:posList>
			            </gml:LinearRing>
			          </gml:exterior>
			        </gml:PolygonPatch>
			      </gml:patches>
			    </gml:Surface>
			  </gml:surfaceMember>
			</gml:MultiSurface>
			""".formatted(EPSG_25831).strip();

	/**
	 * Verifies the ticket's topological-function case: {@code geof:sfEquals} and {@code geof:sfContains} return bound
	 * true values when their arguments are valid {@code geo:gmlLiteral} values.
	 */
	@Test
	public void gmlLiteralsWorkWithTopologicalFunctions() {
		Literal outer = VF.createLiteral(TEGEL_MULTISURFACE, GeoConstants.GEO_GML_LITERAL);
		Literal inner = VF.createLiteral(TEGEL_INNER_POLYGON, GeoConstants.GEO_GML_LITERAL);
		String query = PREFIXES + """
				SELECT ?equals ?contains WHERE {
				  BIND(geof:sfEquals(?outer, ?outer) AS ?equals)
				  BIND(geof:sfContains(?outer, ?inner) AS ?contains)
				}
				""";
		TupleQuery tupleQuery = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
		tupleQuery.setBinding("outer", outer);
		tupleQuery.setBinding("inner", inner);

		try (TupleQueryResult result = tupleQuery.evaluate()) {
			assertTrue(result.hasNext());
			BindingSet bindings = result.next();
			assertFalse(result.hasNext());
			assertEquals(VF.createLiteral(true), bindings.getValue("equals"));
			assertEquals(VF.createLiteral(true), bindings.getValue("contains"));
		}
	}

	/**
	 * Reproduces the ACCORD Tegel query shape from the ticket and verifies that a GML literal reached through
	 * {@code geo:hasDefaultGeometry/geo:asGML} produces bound {@code geoext:area} and {@code geof:envelope} values.
	 */
	@Test
	public void tegelGmlFromDefaultGeometryBindsAreaAndEnvelope() throws Exception {
		enablePlugin();
		importData("issues/GDB-9378-tegel.ttl", RDFFormat.TURTLE);

		String query = PREFIXES + """
				PREFIX xp: <https://graphdb.accordproject.eu/resource/xplanung/>

				select * {
				  ?x a xp:BuildingSubArea ; geo:hasDefaultGeometry/geo:asGML ?geo .
				  bind(geoext:area(?geo) as ?area)
				  bind(geof:envelope(?geo) as ?bbox)
				}
				""";

		try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			assertTrue(result.hasNext());
			BindingSet bindings = result.next();
			assertFalse(result.hasNext());
			assertEquals(VF.createIRI("https://graphdb.accordproject.eu/resource/tegel/ifc/bldg1"),
					bindings.getValue("x"));
			assertTrue(bindings.hasBinding("geo"));
			Literal sourceGeometry = (Literal) bindings.getValue("geo");
			assertEquals(GeoConstants.GEO_GML_LITERAL, sourceGeometry.getDatatype());
			assertTrue(bindings.hasBinding("area"));
			assertTrue(bindings.hasBinding("bbox"));
			Literal bbox = (Literal) bindings.getValue("bbox");
			assertEquals(GeoConstants.GEO_GML_LITERAL, bbox.getDatatype());
			assertEquals(EPSG_25833, sourceGeometryLiteral(bbox.stringValue()).effectiveCrsUri());
		}
	}

	/**
	 * Reproduces the ticket's docs-style distance oracle and verifies that inline WKT and GML point literals both
	 * produce bound {@code geof:distance} values with the same result.
	 */
	@Test
	public void gmlDistanceMatchesWktDistance() {
		String query = """
				PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
				PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
				PREFIX geo: <http://www.opengis.net/ont/geosparql#>

				select * WHERE {
				  BIND (geof:distance('''Point(1 1)'''^^geo:wktLiteral,
				                      '''Point(2 2)'''^^geo:wktLiteral, uom:metre) as ?distWkt) .
				  BIND (geof:distance('''<gml:Point xmlns:gml="http://www.opengis.net/gml/3.2"><gml:pos>1 1</gml:pos></gml:Point>'''^^geo:gmlLiteral,
				                      '''<gml:Point xmlns:gml="http://www.opengis.net/gml/3.2"><gml:pos>2 2</gml:pos></gml:Point>'''^^geo:gmlLiteral, uom:metre) as ?distGml) .
				}
				""";

		try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			assertTrue(result.hasNext());
			BindingSet bindings = result.next();
			assertFalse(result.hasNext());
			assertTrue(bindings.hasBinding("distWkt"));
			assertTrue(bindings.hasBinding("distGml"));
			assertEquals(((Literal) bindings.getValue("distWkt")).doubleValue(),
					((Literal) bindings.getValue("distGml")).doubleValue(), 1e-6);
		}
	}

	/**
	 * Verifies the ticket's GeoSPARQL property relation cases: default Geometries containing projected GML participate
	 * in {@code geo:sfEquals} and {@code geo:sfContains} during incremental indexing and after a full reindex.
	 */
	@Test
	public void gmlDefaultGeometriesWorkWithPropertyRelations() {
		enablePlugin();
		addFeatureGeometry("tegel", "tegelGeometry", TEGEL_MULTISURFACE);
		addFeatureGeometry("tegelCopy", "tegelCopyGeometry", TEGEL_MULTISURFACE);
		addFeatureGeometry("tegelInner", "tegelInnerGeometry", TEGEL_INNER_POLYGON);
		addFeatureGeometry("tegelNotched", "tegelNotchedGeometry", TEGEL_NOTCHED_POLYGON);
		addFeatureGeometry("malgrat", "malgratGeometry", MALGRAT_MULTISURFACE);
		addFeatureGeometry("malgratCopy", "malgratCopyGeometry", MALGRAT_MULTISURFACE);

		assertPropertyRelations();

		forceReindex();

		assertPropertyRelations();
	}

	/**
	 * Verifies the ticket's projected and complex-GML requirements: EPSG:25833 {@code MultiSurface/Polygon} and
	 * EPSG:25831 {@code MultiSurface/Surface/PolygonPatch} literals preserve their source CRS while deriving CRS84
	 * index geometries, and exact evaluation continues to use the source geometry literal.
	 */
	@Test
	public void projectedGmlPreservesSourceCrsAndUsesCrs84IndexGeometry() throws Exception {
		assertProjectedGmlGeometry(TEGEL_MULTISURFACE, EPSG_25833);
		assertProjectedGmlGeometry(MALGRAT_MULTISURFACE, EPSG_25831);

		SourceGeometryLiteral tegel = sourceGeometryLiteral(TEGEL_MULTISURFACE);
		SourceGeometryLiteral tegelNotched = sourceGeometryLiteral(TEGEL_NOTCHED_POLYGON);
		assertEquals(JenaGeometryAdapter.toIndexGeometry(tegel).indexEnvelope(),
				JenaGeometryAdapter.toIndexGeometry(tegelNotched).indexEnvelope());
		assertFalse(JenaFunctionEvaluator.evaluateTopological(
				GeoConstants.GEOF_SF_EQUALS.stringValue(), tegel, tegelNotched));
	}

	private void addFeatureGeometry(String featureName, String geometryName, String lexicalForm) {
		IRI feature = VF.createIRI(EX, featureName);
		IRI geometry = VF.createIRI(EX, geometryName);
		Literal literal = VF.createLiteral(lexicalForm, GeoConstants.GEO_GML_LITERAL);
		connection.begin();
		connection.add(feature, RDF.TYPE, GeoConstants.GEO_FEATURE);
		connection.add(feature, GeoConstants.GEO_HAS_DEFAULT_GEOMETRY, geometry);
		connection.add(geometry, RDF.TYPE, GeoConstants.GEO_GEOMETRY);
		connection.add(geometry, GeoConstants.GEO_AS_GML, literal);
		connection.commit();
	}

	private void assertPropertyRelations() {
		assertAsk("ex:tegel geo:sfEquals ex:tegelCopy");
		assertAsk("ex:tegel geo:sfContains ex:tegelInner");
		assertNotAsk("ex:tegel geo:sfEquals ex:tegelNotched");
		assertAsk("ex:malgrat geo:sfEquals ex:malgratCopy");
	}

	private void assertAsk(String triplePattern) {
		assertTrue(connection.prepareBooleanQuery(QueryLanguage.SPARQL,
				PREFIXES + "ASK { " + triplePattern + " }").evaluate());
	}

	private void assertNotAsk(String triplePattern) {
		assertFalse(connection.prepareBooleanQuery(QueryLanguage.SPARQL,
				PREFIXES + "ASK { " + triplePattern + " }").evaluate());
	}

	private SourceGeometryLiteral sourceGeometryLiteral(String lexicalForm) {
		return JenaGeometryAdapter.toSourceGeometryLiteral(
				VF.createLiteral(lexicalForm, GeoConstants.GEO_GML_LITERAL));
	}

	private void assertProjectedGmlGeometry(String lexicalForm, String expectedSourceCrs) throws Exception {
		SourceGeometryLiteral source = sourceGeometryLiteral(lexicalForm);
		IndexGeometry index = JenaGeometryAdapter.toIndexGeometry(source);

		assertEquals(lexicalForm, source.lexicalForm());
		assertEquals(GeoConstants.GEO_GML_LITERAL, source.datatype());
		assertEquals(expectedSourceCrs, source.effectiveCrsUri());
		assertEquals(IndexGeometry.INDEX_CRS, index.indexCrs());
		assertSame(source, index.sourceGeometryLiteral());
		assertTrue(JenaFunctionEvaluator.evaluateTopological(
				GeoConstants.GEOF_SF_EQUALS.stringValue(), source, source));
	}
}
