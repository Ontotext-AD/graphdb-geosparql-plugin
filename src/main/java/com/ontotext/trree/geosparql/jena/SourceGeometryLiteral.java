package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.jena.datatypes.DatatypeFormatException;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.apache.jena.geosparql.implementation.jts.CustomCoordinateSequence;
import org.apache.jena.geosparql.implementation.jts.CustomGeometryFactory;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.jena.geosparql.implementation.datatype.GeometryDatatype;
import org.apache.jena.geosparql.implementation.datatype.GMLDatatype;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.util.GeometryEditor;

import java.util.ServiceConfigurationError;
import java.util.Objects;

/**
 * CRS-preserving source geometry literal used for exact evaluation.
 */
public final class SourceGeometryLiteral {
	private final String lexicalForm;
	private final String jenaLexicalForm;
	private final IRI datatype;
	private final IRI jenaDatatype;
	private transient GeometryWrapper geometryWrapper;

	private SourceGeometryLiteral(String lexicalForm, IRI datatype, IRI jenaDatatype) {
		this.lexicalForm = lexicalForm;
		this.jenaLexicalForm = GeometryLiteralCompatibility.toJenaLexicalForm(lexicalForm, jenaDatatype);
		this.datatype = datatype;
		this.jenaDatatype = jenaDatatype;
	}

	public static SourceGeometryLiteral fromValue(Value value, boolean acceptNoType) {
		if (!(value instanceof Literal)) {
			throw new JenaGeoSparqlException("Expected a geometry literal, found: " + value);
		}
		return fromLiteral((Literal) value, acceptNoType ? GeoConstants.GEO_WKT_LITERAL : null);
	}

	public static SourceGeometryLiteral fromLiteral(Literal literal) {
		return fromLiteral(literal, null);
	}

	public static SourceGeometryLiteral fromLiteral(Literal literal, IRI fallbackDatatype) {
		IRI datatype = GeometryLiteralCompatibility.datatypeOrFallback(literal.getDatatype(), fallbackDatatype);
		IRI jenaDatatype = toJenaGeometryDatatype(datatype);
		String lexicalForm = literal.stringValue();
		return new SourceGeometryLiteral(lexicalForm, datatype, jenaDatatype);
	}

	public static SourceGeometryLiteral fromWkt(String lexicalForm) {
		return new SourceGeometryLiteral(lexicalForm, GeoConstants.GEO_WKT_LITERAL, GeoConstants.GEO_WKT_LITERAL);
	}

	/**
	 * Reconstructs a source geometry literal from its authoritative RDF identity and parsed native-CRS geometry cache.
	 */
	public static SourceGeometryLiteral fromStoredGeometry(String lexicalForm, IRI datatype,
			String effectiveCrsUri, Geometry sourceGeometry, DimensionInfo storedDimensionInfo) {
		IRI jenaDatatype = toJenaGeometryDatatype(datatype);
		SourceGeometryLiteral source = new SourceGeometryLiteral(lexicalForm, datatype, jenaDatatype);
		try {
			Geometry parsingGeometry = toParsingCoordinateOrder(sourceGeometry, effectiveCrsUri,
					storedDimensionInfo);
			source.geometryWrapper = new StoredGeometryWrapper(parsingGeometry, sourceGeometry,
					effectiveCrsUri, jenaDatatype.stringValue(), storedDimensionInfo, source.jenaLexicalForm);
			source.validateGeometryWrapper();
			return source;
		} catch (JenaGeoSparqlException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new JenaGeoSparqlException("Invalid stored source geometry: " + e.getMessage(), e);
		}
	}

	private static Geometry toParsingCoordinateOrder(Geometry xyGeometry, String effectiveCrsUri,
			DimensionInfo dimensions) {
		if (SRSRegistry.getAxisXY(effectiveCrsUri)) {
			return xyGeometry;
		}
		GeometryEditor editor = new GeometryEditor(CustomGeometryFactory.theInstance());
		return editor.edit(xyGeometry, new GeometryEditor.CoordinateSequenceOperation() {
			@Override
			public CoordinateSequence edit(CoordinateSequence source, Geometry component) {
				CustomCoordinateSequence target = new CustomCoordinateSequence(source.size(), dimensions.getDimensions());
				for (int i = 0; i < source.size(); i++) {
					target.setOrdinate(i, 0, source.getY(i));
					target.setOrdinate(i, 1, source.getX(i));
					if (dimensions.getSpatial() == 3) {
						target.setOrdinate(i, 2, source.getZ(i));
					}
					if (dimensions.getCoordinate() > dimensions.getSpatial()) {
						target.setOrdinate(i, 3, source.getM(i));
					}
				}
				return target;
			}
		});
	}

	private static final class StoredGeometryWrapper extends GeometryWrapper {
		private StoredGeometryWrapper(Geometry parsingGeometry, Geometry xyGeometry, String srsUri,
				String geometryDatatypeUri, DimensionInfo dimensions, String lexicalForm) {
			super(parsingGeometry, xyGeometry, srsUri, geometryDatatypeUri, dimensions, lexicalForm);
		}
	}

	public String lexicalForm() {
		return lexicalForm;
	}

	public IRI datatype() {
		return datatype;
	}

	public IRI jenaDatatype() {
		return jenaDatatype;
	}

	public String effectiveCrsUri() {
		return asGeometryWrapper().getSrsURI();
	}

	public Node asJenaNode() {
		return NodeFactory.createLiteral(jenaLexicalForm, null, GeometryDatatype.get(jenaDatatype.stringValue()));
	}

	public GeometryWrapper asGeometryWrapper() {
		if (geometryWrapper == null) {
			try {
				geometryWrapper = GeometryWrapper.extract(jenaLexicalForm, jenaDatatype.stringValue());
			} catch (DatatypeFormatException e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			} catch (ServiceConfigurationError e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			} catch (RuntimeException e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			}

			validateGeometryWrapper();
		}
		return geometryWrapper;
	}

	private void validateGeometryWrapper() {
		if (!geometryWrapper.isSRSRecognised()) {
			throw new JenaGeoSparqlException(unsupportedCrsMessage(geometryWrapper.getSrsURI()), null, true);
		}
		if (geometryWrapper.getSrsInfo().isGeographic()
				&& !geometryWrapper.isEmpty()
				&& !geometryWrapper.getSrsInfo().getDomainEnvelope()
						.contains(geometryWrapper.getXYGeometry().getEnvelopeInternal())) {
			throw new JenaGeoSparqlException("Geometry coordinates are outside the CRS domain for "
					+ geometryWrapper.getSrsURI());
		}
	}

	static String unsupportedCrsMessage(String crsUri) {
		return "Unsupported CRS for GeoSPARQL geometry literal: " + crsUri
				+ ". Configure Apache SIS CRS data, for example SIS_DATA, or use a supported CRS.";
	}

	private static IRI toJenaGeometryDatatype(IRI datatype) {
		if (GeoConstants.GEO_GML_LITERAL.equals(datatype)) {
			return GeoConstants.GEO_GML_LITERAL;
		}
		if (GeoConstants.GEO_WKT_LITERAL.equals(datatype)
				|| GeoConstants.XMLSCHEMA_OGC_WKT.equals(datatype)
				|| GeoConstants.XMLSCHEMA_SPATIAL_TEXT.equals(datatype)
				|| XSD.STRING.equals(datatype)) {
			return GeoConstants.GEO_WKT_LITERAL;
		}
		throw new JenaGeoSparqlException("Unsupported GeoSPARQL geometry datatype: " + datatype);
	}

	public static IRI wktDatatype() {
		return SimpleValueFactory.getInstance().createIRI(WKTDatatype.URI);
	}

	public static IRI gmlDatatype() {
		return SimpleValueFactory.getInstance().createIRI(GMLDatatype.URI);
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof SourceGeometryLiteral)) {
			return false;
		}
		SourceGeometryLiteral that = (SourceGeometryLiteral) other;
		return lexicalForm.equals(that.lexicalForm) && datatype.equals(that.datatype);
	}

	@Override
	public int hashCode() {
		return Objects.hash(lexicalForm, datatype);
	}
}
