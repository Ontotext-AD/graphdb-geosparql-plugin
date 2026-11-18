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
import org.locationtech.jts.geom.GeometryComponentFilter;
import org.locationtech.jts.geom.util.GeometryEditor;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;

import java.util.ServiceConfigurationError;
import java.util.Objects;

/**
 * CRS-preserving source geometry literal used for exact evaluation.
 */
public final class SourceGeometryLiteral {
	/**
	 * Most coordinates that the right operands kept by one source hold together, each geometry component counting
	 * {@link #COMPONENT_COORDINATES} more. An operand in a latitude-first CRS counts twice, since Jena holds it in
	 * both axis orders. A large source evaluated in many CRSs keeps the operands that fit and is transformed again,
	 * as Jena does, for each evaluation in the others.
	 */
	static final int MAX_KEPT_RIGHT_OPERAND_COORDINATES = 250_000;
	/**
	 * Coordinates that one geometry component counts for besides its own: a polygon, ring, line, point or collection,
	 * empty or not, takes about as much memory for its objects and coordinate sequence as four coordinates do.
	 */
	static final int COMPONENT_COORDINATES = 4;
	private final String lexicalForm;
	private final String jenaLexicalForm;
	private final IRI datatype;
	private final IRI jenaDatatype;
	private transient GeometryWrapper geometryWrapper;
	private transient RightOperand rightOperands;
	private transient long keptRightOperandCoordinates;

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
			GeometryWrapper stored = new StoredGeometryWrapper(parsingGeometry, sourceGeometry,
					effectiveCrsUri, jenaDatatype.stringValue(), storedDimensionInfo, source.jenaLexicalForm);
			validate(stored);
			source.geometryWrapper = stored;
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
			GeometryWrapper parsed;
			try {
				parsed = GeometryWrapper.extract(jenaLexicalForm, jenaDatatype.stringValue());
			} catch (DatatypeFormatException e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			} catch (ServiceConfigurationError e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			} catch (RuntimeException e) {
				throw new JenaGeoSparqlException("Invalid GeoSPARQL geometry literal: " + e.getMessage(), e);
			}
			// A source keeps a geometry only once it is valid, so a rejected literal is rejected on every use.
			validate(parsed);
			geometryWrapper = parsed;
		}
		return geometryWrapper;
	}

	/**
	 * Returns this source as the right operand of an exact evaluation with the given left operand.
	 *
	 * <p>Jena evaluates a pair in the left operand's CRS. It transforms a right operand in another CRS and cleans up
	 * its coordinates before every evaluation. This returns the operand Jena produces and keeps it per left CRS, so a
	 * source evaluated against many left operands in one CRS is transformed once. It keeps operands up to
	 * {@link #MAX_KEPT_RIGHT_OPERAND_COORDINATES} in all. A source in the left operand's CRS is its own right operand.
	 */
	GeometryWrapper asRightOperandFor(GeometryWrapper left) throws FactoryException, TransformException {
		return asRightOperand(left.getSrsURI(), left);
	}

	/**
	 * Returns this source as the right operand of an exact evaluation whose left operand is in the given CRS.
	 *
	 * <p>Jena selects and transforms the right operand by the left operand's CRS alone, so an empty geometry in
	 * that CRS stands in for the left operand.
	 */
	GeometryWrapper asRightOperandIn(String leftCrsUri) throws FactoryException, TransformException {
		return asRightOperand(leftCrsUri, null);
	}

	private GeometryWrapper asRightOperand(String leftCrsUri, GeometryWrapper left)
			throws FactoryException, TransformException {
		GeometryWrapper source = asGeometryWrapper();
		if (leftCrsUri.equals(source.getSrsURI())) {
			return source;
		}
		for (RightOperand kept = rightOperands; kept != null; kept = kept.next()) {
			if (kept.leftCrsUri().equals(leftCrsUri)) {
				return kept.operand();
			}
		}
		GeometryWrapper leftOperand = left != null
				? left : new GeometryWrapper(leftCrsUri, source.getGeometryDatatypeURI());
		GeometryWrapper operand = leftOperand.checkTransformSRS(source);
		long coordinates = keptCoordinates(operand.getXYGeometry());
		if (operand.getParsingGeometry() != operand.getXYGeometry()) {
			coordinates += keptCoordinates(operand.getParsingGeometry());
		}
		if (coordinates <= MAX_KEPT_RIGHT_OPERAND_COORDINATES - keptRightOperandCoordinates) {
			keptRightOperandCoordinates += coordinates;
			rightOperands = new RightOperand(leftCrsUri, operand, rightOperands);
		}
		return operand;
	}

	/** The coordinates a geometry counts for against {@link #MAX_KEPT_RIGHT_OPERAND_COORDINATES}. */
	static long keptCoordinates(Geometry geometry) {
		long[] components = {0};
		geometry.apply((GeometryComponentFilter) component -> components[0]++);
		return geometry.getNumPoints() + COMPONENT_COORDINATES * components[0];
	}

	/** One kept right operand and the operands kept before it. A source meets few left CRSs, so a list serves. */
	private record RightOperand(String leftCrsUri, GeometryWrapper operand, RightOperand next) {
	}

	private static void validate(GeometryWrapper wrapper) {
		if (!wrapper.isSRSRecognised()) {
			throw new JenaGeoSparqlException(unsupportedCrsMessage(wrapper.getSrsURI()), null, true);
		}
		if (wrapper.getSrsInfo().isGeographic()
				&& !wrapper.isEmpty()
				&& !wrapper.getSrsInfo().getDomainEnvelope()
						.contains(wrapper.getXYGeometry().getEnvelopeInternal())) {
			throw new JenaGeoSparqlException("Geometry coordinates are outside the CRS domain for "
					+ wrapper.getSrsURI());
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
