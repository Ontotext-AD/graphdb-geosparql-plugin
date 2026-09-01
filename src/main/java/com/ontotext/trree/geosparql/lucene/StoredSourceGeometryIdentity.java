package com.ontotext.trree.geosparql.lucene;

import java.util.Objects;

/**
 * Persisted identity of a source geometry literal in a Lucene document.
 */
final class StoredSourceGeometryIdentity {
	private final String lexicalForm;
	private final String datatype;
	private final String effectiveSourceCrsUri;

	StoredSourceGeometryIdentity(String lexicalForm, String datatype, String effectiveSourceCrsUri) {
		this.lexicalForm = Objects.requireNonNull(lexicalForm, "lexicalForm");
		this.datatype = Objects.requireNonNull(datatype, "datatype");
		this.effectiveSourceCrsUri = Objects.requireNonNull(effectiveSourceCrsUri, "effectiveSourceCrsUri");
	}

	String lexicalForm() {
		return lexicalForm;
	}

	String datatype() {
		return datatype;
	}

	String effectiveSourceCrsUri() {
		return effectiveSourceCrsUri;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof StoredSourceGeometryIdentity)) {
			return false;
		}
		StoredSourceGeometryIdentity that = (StoredSourceGeometryIdentity) other;
		return lexicalForm.equals(that.lexicalForm)
				&& datatype.equals(that.datatype)
				&& effectiveSourceCrsUri.equals(that.effectiveSourceCrsUri);
	}

	@Override
	public int hashCode() {
		return Objects.hash(lexicalForm, datatype, effectiveSourceCrsUri);
	}
}
