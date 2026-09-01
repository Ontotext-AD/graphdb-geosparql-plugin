package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.implementation.vocabulary.SRS_URI;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.Before;
import org.junit.Test;
import org.locationtech.jts.geom.Dimension;
import org.locationtech.jts.geom.Envelope;

import static org.junit.Assert.*;

public class JenaGeometryAdapterTest {
	private static final ValueFactory VALUE_FACTORY = SimpleValueFactory.getInstance();
	private static final String CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84";
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";
	private static final String RDF_XML_LITERAL = "http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral";
	private static final String GML_LEGACY_NAMESPACE = "http://www.opengis.net/gml";
	private static final String PROJECTED_POINT_WKT = "<" + EPSG_32634 + "> POINT(799997.80 4589779.63)";
	private static final double PROJECTED_POINT_CRS84_X = 24.5887755;
	private static final double PROJECTED_POINT_CRS84_Y = 41.4035958;

	@Before
	public void initializeAdapter() {
		JenaGeometryAdapter.initialize();
	}

	@Test
	public void wktWithoutExplicitCrsUsesCrs84AndPreservesLexicalForm() {
		SourceGeometryLiteral geometry = SourceGeometryLiteral.fromWkt("POINT(1 2)");

		assertEquals("POINT(1 2)", geometry.lexicalForm());
		assertEquals(GeoConstants.GEO_WKT_LITERAL, geometry.datatype());
		assertEquals(GeoConstants.GEO_WKT_LITERAL, geometry.jenaDatatype());
		assertEquals(CRS84, geometry.effectiveCrsUri());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void adapterPinsJenaCalculationPrecision() {
		GeoSPARQLConfig.DECIMAL_PLACES_PRECISION = JenaCalculationPrecision.DECIMAL_PLACES + 3;

		JenaGeometryAdapter.initialize();

		assertEquals(JenaCalculationPrecision.DECIMAL_PLACES,
				GeoSPARQLConfig.DECIMAL_PLACES_PRECISION);
	}

	@Test
	public void wktWithExplicitCrsPreservesStoredCrsMetadata() {
		SourceGeometryLiteral geometry = SourceGeometryLiteral.fromWkt("<" + CRS84 + "> POINT(1 2)");

		assertEquals(CRS84, geometry.effectiveCrsUri());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void plainStringLiteralCanUseWktFallbackDatatype() {
		Literal literal = VALUE_FACTORY.createLiteral("POINT(3 4)", XSD.STRING);

		SourceGeometryLiteral geometry = JenaGeometryAdapter.toSourceGeometryLiteral(literal,
				GeoConstants.GEO_WKT_LITERAL);

		assertEquals(GeoConstants.GEO_WKT_LITERAL, geometry.datatype());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void gmlLiteralPreservesSrsNameAndParses() {
		String gml = "<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\" srsName=\"" + CRS84
				+ "\"><gml:pos>1 2</gml:pos></gml:Point>";
		Literal literal = VALUE_FACTORY.createLiteral(gml, GeoConstants.GEO_GML_LITERAL);

		SourceGeometryLiteral geometry = JenaGeometryAdapter.toSourceGeometryLiteral(literal);

		assertEquals(CRS84, geometry.effectiveCrsUri());
		assertEquals(GeoConstants.GEO_GML_LITERAL, geometry.datatype());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void malformedGmlFailsAsControlledGeometryError() {
		String malformedGml = "<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\" srsName=\""
				+ CRS84 + "\"><gml:pos>1 2</gml:pos>";
		SourceGeometryLiteral source = JenaGeometryAdapter.toSourceGeometryLiteral(
				VALUE_FACTORY.createLiteral(malformedGml, GeoConstants.GEO_GML_LITERAL));

		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> JenaGeometryAdapter.toIndexGeometry(source));

		assertEquals(malformedGml, source.lexicalForm());
		assertTrue(exception.getMessage().contains("Invalid GeoSPARQL geometry literal"));
	}

	@Test
	public void xmlLiteralCanUseGmlFallbackDatatype() {
		String gml = legacyGmlWithDoubleQuotedNamespaceAndCrs();
		Literal literal = VALUE_FACTORY.createLiteral(gml, VALUE_FACTORY.createIRI(RDF_XML_LITERAL));

		SourceGeometryLiteral geometry = JenaGeometryAdapter.toSourceGeometryLiteral(literal,
				GeoConstants.GEO_GML_LITERAL);

		assertEquals(gml, geometry.lexicalForm());
		assertEquals(GeoConstants.GEO_GML_LITERAL, geometry.datatype());
		assertEquals(CRS84, geometry.effectiveCrsUri());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void xmlLiteralWithoutGmlFallbackIsRejected() {
		Literal literal = VALUE_FACTORY.createLiteral(legacyGmlWithDoubleQuotedNamespaceAndCrs(),
				VALUE_FACTORY.createIRI(RDF_XML_LITERAL));

		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> JenaGeometryAdapter.toSourceGeometryLiteral(literal));

		assertTrue(exception.getMessage().contains("Unsupported GeoSPARQL geometry datatype"));
	}

	@Test
	public void xmlLiteralCannotUseWktFallbackDatatype() {
		Literal literal = VALUE_FACTORY.createLiteral(legacyGmlWithDoubleQuotedNamespaceAndCrs(),
				VALUE_FACTORY.createIRI(RDF_XML_LITERAL));

		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> JenaGeometryAdapter.toSourceGeometryLiteral(literal, GeoConstants.GEO_WKT_LITERAL));

		assertTrue(exception.getMessage().contains("Unsupported GeoSPARQL geometry datatype"));
	}

	@Test
	public void legacyGmlNamespaceParsesAndPreservesSourceLexicalForm() {
		String gml = legacyGmlWithDoubleQuotedNamespaceAndCrs();
		Literal literal = VALUE_FACTORY.createLiteral(gml, GeoConstants.GEO_GML_LITERAL);

		SourceGeometryLiteral geometry = JenaGeometryAdapter.toSourceGeometryLiteral(literal);

		assertEquals(gml, geometry.lexicalForm());
		assertTrue(geometry.lexicalForm().contains("xmlns:gml=\"" + GML_LEGACY_NAMESPACE + "\""));
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void legacyGmlNamespaceWithSingleQuotesParses() {
		String gml = "<gml:Point xmlns:gml='" + GML_LEGACY_NAMESPACE
				+ "'><gml:pos>1 2</gml:pos></gml:Point>";
		Literal literal = VALUE_FACTORY.createLiteral(gml, GeoConstants.GEO_GML_LITERAL);

		SourceGeometryLiteral geometry = JenaGeometryAdapter.toSourceGeometryLiteral(literal);

		assertEquals(gml, geometry.lexicalForm());
		assertEquals(SRS_URI.DEFAULT_WKT_CRS84, geometry.asGeometryWrapper().getSrsURI());
	}

	@Test
	public void projectedWktPreservesSourceCrsAndUsesLocalIndexEnvelope() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(PROJECTED_POINT_WKT);

		IndexGeometry index = TestIndexGeometries.fromSource(source);

		assertEquals(EPSG_32634, source.effectiveCrsUri());
		assertEquals(IndexGeometry.INDEX_CRS, index.indexCrs());
		assertTrue(index.isSpatialCandidate());
		assertFalse(isWorldCrs84Envelope(index.indexEnvelope()));
		assertTrue(index.indexEnvelope().getWidth() < 1.0);
		assertTrue(index.indexEnvelope().getHeight() < 1.0);
	}

	@Test
	public void projectedGmlPreservesSourceCrsAndUsesLocalIndexEnvelope() {
		String gml = "<gml:Point xmlns:gml=\"http://www.opengis.net/gml/3.2\" srsName=\"" + EPSG_32634
				+ "\"><gml:pos>799997.80 4589779.63</gml:pos></gml:Point>";
		Literal literal = VALUE_FACTORY.createLiteral(gml, GeoConstants.GEO_GML_LITERAL);

		SourceGeometryLiteral source = JenaGeometryAdapter.toSourceGeometryLiteral(literal);
		IndexGeometry index = TestIndexGeometries.fromSource(source);

		assertEquals(EPSG_32634, source.effectiveCrsUri());
		assertEquals(GeoConstants.GEO_GML_LITERAL, source.datatype());
		assertEquals(IndexGeometry.INDEX_CRS, index.indexCrs());
		assertTrue(index.isSpatialCandidate());
		assertFalse(isWorldCrs84Envelope(index.indexEnvelope()));
	}

	@Test
	public void genericGmlCollectionProducesOneEnvelopeIndexGeometry() {
		String gml = "<gml:MultiGeometry xmlns:gml=\"http://www.opengis.net/gml/3.2\" srsName=\"" + CRS84
				+ "\"><gml:geometryMember><gml:Point><gml:pos>1 2</gml:pos></gml:Point>"
				+ "</gml:geometryMember><gml:geometryMember><gml:LineString><gml:posList>3 4 5 6</gml:posList>"
				+ "</gml:LineString></gml:geometryMember></gml:MultiGeometry>";
		Literal literal = VALUE_FACTORY.createLiteral(gml, GeoConstants.GEO_GML_LITERAL);

		IndexGeometry envelope = JenaGeometryAdapter.toIndexGeometry(
				JenaGeometryAdapter.toSourceGeometryLiteral(literal));

		assertEnvelope(envelope, 1.0, 5.0, 2.0, 6.0);
		assertEquals(GeoConstants.GEO_GML_LITERAL, envelope.sourceGeometryLiteral().datatype());
	}

	@Test
	public void genericCollectionProducesOneEnvelopeWithCompleteSource() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(
				"GEOMETRYCOLLECTION(POINT(1 2),LINESTRING(3 4,5 6))");

		IndexGeometry envelope = IndexGeometry.fromSourceGeometryLiteral(source);

		assertEquals(source.asGeometryWrapper().getXYGeometry().getEnvelopeInternal(),
				envelope.indexEnvelope());
		assertSame(source, envelope.sourceGeometryLiteral());
		assertTrue(envelope.isSpatialCandidate());
	}

	@Test
	public void nestedCollectionUsesEnvelopeOfEveryMember() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt(
				"GEOMETRYCOLLECTION(GEOMETRYCOLLECTION(POINT(1 2)),MULTIPOINT((3 4),(5 6)))");

		IndexGeometry envelope = IndexGeometry.fromSourceGeometryLiteral(source);

		assertEnvelope(envelope, 1.0, 5.0, 2.0, 6.0);
	}

	@Test
	public void everyEmptyGeometryTypeProducesNonSpatialSentinel() {
		for (String wkt : new String[]{
				"POINT EMPTY",
				"LINESTRING EMPTY",
				"POLYGON EMPTY",
				"MULTIPOINT EMPTY",
				"MULTILINESTRING EMPTY",
				"MULTIPOLYGON EMPTY",
				"GEOMETRYCOLLECTION EMPTY"}) {
			IndexGeometry sentinel = IndexGeometry.fromSourceGeometryLiteral(
					SourceGeometryLiteral.fromWkt(wkt));

			assertTrue(wkt, sentinel.indexEnvelope().isNull());
			assertFalse(wkt, sentinel.isSpatialCandidate());
		}
	}

	@Test
	public void homogeneousMultiGeometryUsesCompleteEnvelope() {
		IndexGeometry multiPoint = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("MULTIPOINT((1 2),(5 6))"));
		IndexGeometry multiLine = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(
						"MULTILINESTRING((1 2,3 4),(4 1,5 6))"));
		IndexGeometry multiPolygon = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt(
						"MULTIPOLYGON(((1 2,1 3,2 3,2 2,1 2)),"
								+ "((4 5,4 6,5 6,5 5,4 5)))"));

		assertEnvelope(multiPoint, 1.0, 5.0, 2.0, 6.0);
		assertEnvelope(multiLine, 1.0, 5.0, 1.0, 6.0);
		assertEnvelope(multiPolygon, 1.0, 5.0, 2.0, 6.0);
	}

	@Test
	public void indexGeometryRetainsSourceTopologicalDimension() {
		assertEquals(Dimension.P, IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POINT(1 2)")).sourceTopologicalDimension());
		assertEquals(Dimension.L, IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("LINESTRING(1 2,3 4)")).sourceTopologicalDimension());
		assertEquals(Dimension.A, IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POLYGON((0 0,0 1,1 1,1 0,0 0))"))
				.sourceTopologicalDimension());
	}

	@Test
	public void projectedSingleMemberCollectionUsesLocalIndexEnvelope() {
		IndexGeometry envelope = IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(
				"<" + EPSG_32634 + "> GEOMETRYCOLLECTION(POINT(799997.80 4589779.63))"));

		assertTrue(envelope.isSpatialCandidate());
		assertFalse(isWorldCrs84Envelope(envelope.indexEnvelope()));
	}

	@Test
	public void nonCrs84GeometryUsesAxisOrderIndexEnvelope() {
		IndexGeometry envelope = IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(
				"<" + EPSG_4326 + "> GEOMETRYCOLLECTION(POINT(50 10),POINT(51 11))"));

		assertTrue(envelope.isSpatialCandidate());
		assertFalse(isWorldCrs84Envelope(envelope.indexEnvelope()));
		assertTrue(envelope.indexEnvelope().getMinX() <= 10.0);
		assertTrue(envelope.indexEnvelope().getMaxX() >= 11.0);
		assertTrue(envelope.indexEnvelope().getMinY() <= 50.0);
		assertTrue(envelope.indexEnvelope().getMaxY() >= 51.0);
	}

	@Test
	public void degeneratePointAndLineEnvelopesRemainSpatialCandidates() {
		IndexGeometry point = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("POINT(1 2)"));
		IndexGeometry verticalLine = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("LINESTRING(3 4,3 8)"));

		assertEnvelope(point, 1.0, 1.0, 2.0, 2.0);
		assertEnvelope(verticalLine, 3.0, 3.0, 4.0, 8.0);
		assertTrue(point.isSpatialCandidate());
		assertTrue(verticalLine.isSpatialCandidate());
	}

	@Test
	public void antimeridianSpanningInputUsesConservativeCompleteEnvelope() {
		IndexGeometry geometry = IndexGeometry.fromSourceGeometryLiteral(
				SourceGeometryLiteral.fromWkt("LINESTRING(170 10,-170 12)"));

		assertEnvelope(geometry, -170.0, 170.0, 10.0, 12.0);
	}

	@Test
	public void collectionExactEvaluationUsesRelateNgUnionSemantics() throws Exception {
		SourceGeometryLiteral collection = SourceGeometryLiteral.fromWkt(
				"GEOMETRYCOLLECTION(POINT(1 1),POINT(2 2))");
		SourceGeometryLiteral polygon = SourceGeometryLiteral.fromWkt(
				"POLYGON((0 0,0 3,3 3,3 0,0 0))");

		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_WITHIN.stringValue(),
				collection, polygon));
		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				collection, polygon));
	}

	@Test
	public void collectionCrossesAppliesToOrderedDimensionPairs() throws Exception {
		Object[][] cases = {
				{"point-line",
						"GEOMETRYCOLLECTION(MULTIPOINT((1 0),(1 1)))",
						"LINESTRING(0 0,2 0)", true, false},
				{"point-area",
						"GEOMETRYCOLLECTION(MULTIPOINT((1 1),(3 3)))",
						"POLYGON((0 0,0 2,2 2,2 0,0 0))", true, false},
				{"line-area",
						"GEOMETRYCOLLECTION(LINESTRING(-1 1,3 1))",
						"POLYGON((0 0,0 2,2 2,2 0,0 0))", true, false},
				{"line-line",
						"GEOMETRYCOLLECTION(LINESTRING(0 0,2 2))",
						"LINESTRING(0 2,2 0)", true, null}
		};

		for (Object[] testCase : cases) {
			String label = (String) testCase[0];
			SourceGeometryLiteral left = SourceGeometryLiteral.fromWkt((String) testCase[1]);
			SourceGeometryLiteral right = SourceGeometryLiteral.fromWkt((String) testCase[2]);

			assertEquals(label, testCase[3], JenaFunctionEvaluator.evaluateTopological(
					GeoConstants.GEOF_SF_CROSSES.stringValue(), left, right));
			if (testCase[4] != null) {
				assertEquals(label + " reverse", testCase[4], JenaFunctionEvaluator.evaluateTopological(
						GeoConstants.GEOF_SF_CROSSES.stringValue(), right, left));
			}
		}
	}

	@Test
	public void collectionTouchesRejectsPointPoint() throws Exception {
		SourceGeometryLiteral collection = SourceGeometryLiteral.fromWkt(
				"GEOMETRYCOLLECTION(POINT(1 1))");
		SourceGeometryLiteral point = SourceGeometryLiteral.fromWkt("POINT(1 1)");

		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_TOUCHES.stringValue(),
				collection, point));
	}

	@Test
	public void mixedCollectionUsesMaximumDimensionAndCompleteUnion() throws Exception {
		SourceGeometryLiteral mixed = SourceGeometryLiteral.fromWkt(
				"GEOMETRYCOLLECTION(POLYGON((0 0,0 2,2 2,2 0,0 0)),POINT(5 5))");
		SourceGeometryLiteral crossingLine = SourceGeometryLiteral.fromWkt("LINESTRING(-1 1,3 1)");
		SourceGeometryLiteral lowerDimensionMember = SourceGeometryLiteral.fromWkt("POINT(5 5)");

		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_CROSSES.stringValue(),
				mixed, crossingLine));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_CROSSES.stringValue(),
				crossingLine, mixed));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_INTERSECTS.stringValue(),
				mixed, lowerDimensionMember));
	}

	@Test
	public void nonCollectionCrossesBehaviorRemainsUnchanged() throws Exception {
		SourceGeometryLiteral points = SourceGeometryLiteral.fromWkt("MULTIPOINT((1 0),(1 1))");
		SourceGeometryLiteral line = SourceGeometryLiteral.fromWkt("LINESTRING(0 0,2 0)");

		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_CROSSES.stringValue(),
				points, line));
		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_CROSSES.stringValue(),
				line, points));
	}

	@Test
	public void collectionRelateFunctionUsesOnlySuppliedPattern() {
		Literal polygon = VALUE_FACTORY.createLiteral(
				"POLYGON((0 0,0 3,3 3,3 0,0 0))", GeoConstants.GEO_WKT_LITERAL);
		Literal collection = VALUE_FACTORY.createLiteral(
				"GEOMETRYCOLLECTION(POINT(1 1))", GeoConstants.GEO_WKT_LITERAL);

		Value namedRelation = JenaFunctionEvaluator.evaluate(VALUE_FACTORY,
				GeoConstants.GEOF_SF_CROSSES.stringValue(), polygon, collection);
		Value suppliedPattern = JenaFunctionEvaluator.evaluate(VALUE_FACTORY, GeoConstants.GEOF_RELATE.stringValue(),
				polygon, collection, VALUE_FACTORY.createLiteral("T********"));

		assertFalse(((Literal) namedRelation).booleanValue());
		assertTrue(((Literal) suppliedPattern).booleanValue());
	}

	@Test
	public void emptyCollectionIsDisjointButNotWithin() throws Exception {
		SourceGeometryLiteral empty = SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION EMPTY");
		SourceGeometryLiteral polygon = SourceGeometryLiteral.fromWkt(
				"POLYGON((0 0,0 3,3 3,3 0,0 0))");

		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				empty, polygon));
		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_WITHIN.stringValue(),
				empty, polygon));
	}

	@Test
	public void emptyOrdinaryGeometriesFollowDe9imDisjointAndIntersects() throws Exception {
		SourceGeometryLiteral emptyPoint = SourceGeometryLiteral.fromWkt("POINT EMPTY");
		SourceGeometryLiteral emptyPolygon = SourceGeometryLiteral.fromWkt("POLYGON EMPTY");
		SourceGeometryLiteral emptyCollection = SourceGeometryLiteral.fromWkt("GEOMETRYCOLLECTION EMPTY");
		SourceGeometryLiteral point = SourceGeometryLiteral.fromWkt("POINT(1 1)");
		SourceGeometryLiteral polygon = SourceGeometryLiteral.fromWkt(
				"POLYGON((0 0,0 3,3 3,3 0,0 0))");

		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				emptyPoint, point));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				point, emptyPoint));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				emptyPolygon, polygon));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				emptyPoint, emptyPoint));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_EH_DISJOINT.stringValue(),
				emptyPoint, point));
		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_INTERSECTS.stringValue(),
				emptyPoint, point));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_DISJOINT.stringValue(),
				emptyCollection, point));
		assertTrue(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_EH_DISJOINT.stringValue(),
				emptyCollection, point));
		assertFalse(JenaFunctionEvaluator.evaluateTopological(GeoConstants.GEOF_SF_INTERSECTS.stringValue(),
				emptyCollection, point));
	}

	@Test
	public void exactEvaluationTransformsMixedCrsArguments() throws Exception {
		Literal crs84Point = VALUE_FACTORY.createLiteral(
				"POINT(" + PROJECTED_POINT_CRS84_X + " " + PROJECTED_POINT_CRS84_Y + ")",
				GeoConstants.GEO_WKT_LITERAL);
		Literal projectedPoint = VALUE_FACTORY.createLiteral(PROJECTED_POINT_WKT, GeoConstants.GEO_WKT_LITERAL);

		Value result = JenaFunctionEvaluator.evaluate(VALUE_FACTORY, GeoConstants.GEOF_DISTANCE.stringValue(),
				crs84Point, projectedPoint, GeoSparqlUnits.URI_METRE);

		assertTrue(result instanceof Literal);
		assertEquals(0d, ((Literal) result).doubleValue(), 0.2d);
	}

	@Test
	public void crs84CoordinatesOutsideDomainFail() {
		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> SourceGeometryLiteral.fromWkt("POINT(200 200)").asGeometryWrapper());

		assertTrue(exception.getMessage().contains("outside the CRS domain"));
	}

	@Test
	public void unsupportedCrsFailsAsControlledGeoSparqlError() {
		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> SourceGeometryLiteral.fromWkt("<http://example.com/crs/unknown> POINT(1 2)").asGeometryWrapper());

		assertTrue(exception.getMessage().contains("Unsupported CRS")
				|| exception.getMessage().contains("Invalid GeoSPARQL geometry literal"));
	}

	@Test
	public void unsupportedCrsCannotProduceIndexGeometry() {
		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> TestIndexGeometries.fromSource(
						SourceGeometryLiteral.fromWkt("<http://example.com/crs/unknown> POINT(1 2)")));

		assertTrue(exception.getMessage().contains("Unsupported CRS")
				|| exception.getMessage().contains("Invalid GeoSPARQL geometry literal"));
	}

	@Test
	public void unsupportedDatatypeFails() {
		Literal literal = VALUE_FACTORY.createLiteral("POINT(1 2)",
				VALUE_FACTORY.createIRI("http://example.com/geometryLiteral"));

		JenaGeoSparqlException exception = assertThrows(JenaGeoSparqlException.class,
				() -> JenaGeometryAdapter.toSourceGeometryLiteral(literal));

		assertTrue(exception.getMessage().contains("Unsupported GeoSPARQL geometry datatype"));
	}

	@Test
	public void rcc8RelationsRequireAreaTopology() throws Exception {
		Object[][] cases = {
				{"disconnected points", "POINT(1 1)", "POINT(2 2)",
						GeoConstants.GEOF_SF_DISJOINT, GeoConstants.GEOF_RCC8_DC,
						GeoSparqlPropertyRelation.RCC8_DC},
				{"externally connected lines", "LINESTRING(0 0, 1 1)", "LINESTRING(1 1, 2 2)",
						GeoConstants.GEOF_SF_TOUCHES, GeoConstants.GEOF_RCC8_EC,
						GeoSparqlPropertyRelation.RCC8_EC}
		};

		for (Object[] testCase : cases) {
			String label = (String) testCase[0];
			SourceGeometryLiteral left = SourceGeometryLiteral.fromWkt((String) testCase[1]);
			SourceGeometryLiteral right = SourceGeometryLiteral.fromWkt((String) testCase[2]);
			IRI simpleFeaturesFunction = (IRI) testCase[3];
			IRI rcc8Function = (IRI) testCase[4];
			GeoSparqlPropertyRelation propertyRelation = (GeoSparqlPropertyRelation) testCase[5];

			assertTrue(label, JenaFunctionEvaluator.evaluateTopological(
					simpleFeaturesFunction.stringValue(), left, right));
			assertFalse(label, JenaFunctionEvaluator.evaluateTopological(
					rcc8Function.stringValue(), left, right));
			assertFalse(label, propertyRelation.evaluate(left, right));
		}
	}

	private static String legacyGmlWithDoubleQuotedNamespaceAndCrs() {
		return "<gml:Point xmlns:gml=\"" + GML_LEGACY_NAMESPACE + "\" srsName=\"" + CRS84
				+ "\"><gml:pos>1 2</gml:pos></gml:Point>";
	}

	private static boolean isWorldCrs84Envelope(Envelope envelope) {
		return envelope.getMinX() == -180.0
				&& envelope.getMaxX() == 180.0
				&& envelope.getMinY() == -90.0
				&& envelope.getMaxY() == 90.0;
	}

	private static void assertEnvelope(IndexGeometry geometry, double minX, double maxX,
			double minY, double maxY) {
		Envelope envelope = geometry.indexEnvelope();
		assertEquals(minX, envelope.getMinX(), 1e-9);
		assertEquals(maxX, envelope.getMaxX(), 1e-9);
		assertEquals(minY, envelope.getMinY(), 1e-9);
		assertEquals(maxY, envelope.getMaxY(), 1e-9);
	}
}
