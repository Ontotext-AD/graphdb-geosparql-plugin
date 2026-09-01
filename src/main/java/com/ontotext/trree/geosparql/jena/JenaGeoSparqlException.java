package com.ontotext.trree.geosparql.jena;

/**
 * Runtime exception used at the RDF4J/Jena boundary.
 */
public class JenaGeoSparqlException extends RuntimeException {
	private final boolean unsupportedCrs;

	public JenaGeoSparqlException(String message) {
		this(message, null, false);
	}

	public JenaGeoSparqlException(String message, Throwable cause) {
		this(message, cause, false);
	}

	public JenaGeoSparqlException(String message, Throwable cause, boolean unsupportedCrs) {
		super(message, cause);
		this.unsupportedCrs = unsupportedCrs;
	}

	public boolean isUnsupportedCrs() {
		return unsupportedCrs;
	}
}
