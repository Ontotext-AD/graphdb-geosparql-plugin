package com.ontotext.trree.geosparql.function;

import com.ontotext.trree.geosparql.jena.GeoSparqlUnits;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GeoSparqlFunctionRegistrationTest {
    private static final ValueFactory VALUE_FACTORY = SimpleValueFactory.getInstance();
    private static final ValueFactoryTripleSource TRIPLE_SOURCE = new ValueFactoryTripleSource(VALUE_FACTORY);

    @Test
    public void registerAllRegistersSupportedFunctionUris() {
        GeoSparqlFunctionRegistration.registerAll();

        FunctionRegistry registry = FunctionRegistry.getInstance();
        for (String uri : GeoSparqlFunctionRegistration.supportedFunctionUris()) {
            assertTrue("Expected registered function URI: " + uri, registry.has(uri));
            assertTrue(registry.get(uri).get() instanceof GeoSparqlRdf4jFunction);
        }
    }

    @Test
    public void registeredDistanceFunctionDelegatesToJenaEvaluator() throws Exception {
        GeoSparqlFunctionRegistration.registerAll();
        Function function = FunctionRegistry.getInstance().get(GeoConstants.GEOF_DISTANCE.stringValue()).get();
        Literal left = VALUE_FACTORY.createLiteral("POINT(24.5887755 41.4035958)",
                GeoConstants.GEO_WKT_LITERAL);
        Literal right = VALUE_FACTORY.createLiteral(
                "<http://www.opengis.net/def/crs/EPSG/0/32634> POINT(799997.80 4589779.63)",
                GeoConstants.GEO_WKT_LITERAL);

		Value result = function.evaluate(TRIPLE_SOURCE, left, right, GeoSparqlUnits.URI_METRE);

        assertTrue(result instanceof Literal);
        assertEquals(0d, ((Literal) result).doubleValue(), 0.2d);
    }

    @Test
    public void registerAllIsIdempotent() {
        GeoSparqlFunctionRegistration.registerAll();
        GeoSparqlFunctionRegistration.registerAll();

        Set<String> supportedUris = GeoSparqlFunctionRegistration.supportedFunctionUris().stream()
                .collect(Collectors.toSet());
        long registeredSupportedUris = FunctionRegistry.getInstance().getKeys().stream()
                .filter(supportedUris::contains)
                .count();

        assertEquals(supportedUris.size(), registeredSupportedUris);
    }
}
