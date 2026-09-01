package com.ontotext.trree.geosparql.function;

import com.ontotext.trree.geosparql.jena.JenaFunctionEvaluator;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.locationtech.jts.geom.TopologyException;

final class GeoSparqlRdf4jFunction implements Function {
    private final String uri;

    GeoSparqlRdf4jFunction(String uri) {
        this.uri = uri;
    }

    @Override
    public String getURI() {
        return uri;
    }

    @Override
    public Value evaluate(ValueFactory valueFactory, Value... args) throws ValueExprEvaluationException {
        try {
            return JenaFunctionEvaluator.evaluate(valueFactory, uri, args);
        } catch (TopologyException e) {
            throw new ValueExprEvaluationException(e);
        }
    }
}
