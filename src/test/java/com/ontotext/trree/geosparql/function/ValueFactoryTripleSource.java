package com.ontotext.trree.geosparql.function;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;

final class ValueFactoryTripleSource implements TripleSource {
	private final ValueFactory valueFactory;

	ValueFactoryTripleSource(ValueFactory valueFactory) {
		this.valueFactory = valueFactory;
	}

	@Override
	// CloseableIteration has two type parameters in RDF4J 4 and one in RDF4J 6, so only the raw type compiles
	// against both.
	@SuppressWarnings({"rawtypes", "unchecked"})
	public CloseableIteration getStatements(Resource subject, IRI predicate, Value object,
			Resource... contexts) {
		return TripleSource.EMPTY_ITERATION;
	}

	@Override
	public ValueFactory getValueFactory() {
		return valueFactory;
	}
}
