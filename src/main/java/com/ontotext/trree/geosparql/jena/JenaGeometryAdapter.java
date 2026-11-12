package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.implementation.datatype.GeometryDatatype;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;

/**
 * Central RDF4J-to-Jena conversion entry point for geometry values.
 *
 * <p>The adapter initializes Jena's geometry registries, preserves RDF literal datatype and lexical form in
	 * {@link SourceGeometryLiteral}, and derives the CRS84 {@link IndexGeometry} value used by Lucene.
 * Exact relation semantics remain in the Jena evaluator rather than this conversion layer.
 */
public final class JenaGeometryAdapter {
	private static volatile boolean registriesInitialized;

	private JenaGeometryAdapter() {
	}

	public static void initialize() {
		JenaCalculationPrecision.configure();
		if (!registriesInitialized) {
			initializeRegistries();
		}
	}

	private static synchronized void initializeRegistries() {
		if (!registriesInitialized) {
			SRSRegistry.setupDefaultSRS();
			GeometryDatatype.registerDatatypes();
			registriesInitialized = true;
		}
	}

	public static SourceGeometryLiteral toSourceGeometryLiteral(Literal literal) {
		initialize();
		return SourceGeometryLiteral.fromLiteral(literal);
	}

	public static SourceGeometryLiteral toSourceGeometryLiteral(Literal literal, IRI fallbackDatatype) {
		initialize();
		return SourceGeometryLiteral.fromLiteral(literal, fallbackDatatype);
	}

	public static SourceGeometryLiteral toSourceGeometryLiteral(Value value, boolean acceptNoType) {
		initialize();
		return SourceGeometryLiteral.fromValue(value, acceptNoType);
	}

	public static IndexGeometry toIndexGeometry(SourceGeometryLiteral sourceGeometryLiteral) {
		initialize();
		return IndexGeometry.fromSourceGeometryLiteral(sourceGeometryLiteral);
	}

	public static Literal toRdf4jLiteral(ValueFactory valueFactory, org.apache.jena.geosparql.implementation.GeometryWrapper wrapper,
										 IRI datatype) {
		org.apache.jena.rdf.model.Literal literal = wrapper.asLiteral(SourceGeometryLiteral.fromLiteral(
				valueFactory.createLiteral(wrapper.getLexicalForm(), datatype)).jenaDatatype().stringValue());
		return valueFactory.createLiteral(literal.getLexicalForm(), datatype);
	}
}
