package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.XSD;

/**
 * Compatibility rules for geometry literals accepted by the pre-Jena plugin and existing fixture data.
 *
 * SourceGeometryLiteral keeps the original RDF4J lexical form/datatype for storage and result fidelity, while this
 * helper derives the datatype and lexical form that Apache Jena's GeoSPARQL parser can consume.
 */
final class GeometryLiteralCompatibility {
	private static final String RDF_XML_LITERAL = "http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral";
	private static final String GML_LEGACY_NAMESPACE = "http://www.opengis.net/gml";
	private static final String GML_32_NAMESPACE = "http://www.opengis.net/gml/3.2";

	private GeometryLiteralCompatibility() {
	}

	static IRI datatypeOrFallback(IRI datatype, IRI fallbackDatatype) {
		return shouldUseFallbackDatatype(datatype, fallbackDatatype) ? fallbackDatatype : datatype;
	}

	/**
	 * Jena's GML parser expects the GML 3.2 namespace, but existing data can use the older GML namespace.
	 */
	static String toJenaLexicalForm(String lexicalForm, IRI jenaDatatype) {
		if (!GeoConstants.GEO_GML_LITERAL.equals(jenaDatatype)) {
			return lexicalForm;
		}
		return lexicalForm
				.replace("=\"" + GML_LEGACY_NAMESPACE + "\"", "=\"" + GML_32_NAMESPACE + "\"")
				.replace("='" + GML_LEGACY_NAMESPACE + "'", "='" + GML_32_NAMESPACE + "'");
	}

	private static boolean shouldUseFallbackDatatype(IRI datatype, IRI fallbackDatatype) {
		if (fallbackDatatype == null) {
			return false;
		}
		if (isPlainString(datatype)) {
			return true;
		}
		return GeoConstants.GEO_GML_LITERAL.equals(fallbackDatatype)
				&& datatype != null
				&& RDF_XML_LITERAL.equals(datatype.stringValue());
	}

	private static boolean isPlainString(IRI datatype) {
		return datatype == null || XSD.STRING.equals(datatype);
	}
}
