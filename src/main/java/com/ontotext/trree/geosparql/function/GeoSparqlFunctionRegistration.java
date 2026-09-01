package com.ontotext.trree.geosparql.function;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;

import java.util.List;

public final class GeoSparqlFunctionRegistration {
    private static final List<String> SUPPORTED_FUNCTION_URIS = List.of(
            GeoConstants.GEOF_SF_EQUALS.stringValue(),
            GeoConstants.GEOF_SF_DISJOINT.stringValue(),
            GeoConstants.GEOF_SF_INTERSECTS.stringValue(),
            GeoConstants.GEOF_SF_TOUCHES.stringValue(),
            GeoConstants.GEOF_SF_CROSSES.stringValue(),
            GeoConstants.GEOF_SF_WITHIN.stringValue(),
            GeoConstants.GEOF_SF_CONTAINS.stringValue(),
            GeoConstants.GEOF_SF_OVERLAPS.stringValue(),
            GeoConstants.GEOF_EH_EQUALS.stringValue(),
            GeoConstants.GEOF_EH_DISJOINT.stringValue(),
            GeoConstants.GEOF_EH_MEET.stringValue(),
            GeoConstants.GEOF_EH_OVERLAP.stringValue(),
            GeoConstants.GEOF_EH_COVERS.stringValue(),
            GeoConstants.GEOF_EH_COVERED_BY.stringValue(),
            GeoConstants.GEOF_EH_INSIDE.stringValue(),
            GeoConstants.GEOF_EH_CONTAINS.stringValue(),
            GeoConstants.GEOF_RCC8_EQ.stringValue(),
            GeoConstants.GEOF_RCC8_DC.stringValue(),
            GeoConstants.GEOF_RCC8_EC.stringValue(),
            GeoConstants.GEOF_RCC8_PO.stringValue(),
            GeoConstants.GEOF_RCC8_TPPI.stringValue(),
            GeoConstants.GEOF_RCC8_TPP.stringValue(),
            GeoConstants.GEOF_RCC8_NTPP.stringValue(),
            GeoConstants.GEOF_RCC8_NTPPI.stringValue(),
            GeoConstants.GEOF_RELATE.stringValue(),
            GeoConstants.GEOF_DISTANCE.stringValue(),
            GeoConstants.GEOF_BUFFER.stringValue(),
            GeoConstants.GEOF_CONVEX_HULL.stringValue(),
            GeoConstants.GEOF_INTERSECTION.stringValue(),
            GeoConstants.GEOF_UNION.stringValue(),
            GeoConstants.GEOF_DIFFERENCE.stringValue(),
            GeoConstants.GEOF_SYM_DIFFERENCE.stringValue(),
            GeoConstants.GEOF_ENVELOPE.stringValue(),
            GeoConstants.GEOF_BOUNDARY.stringValue(),
            GeoConstants.GEOF_GETSRID.stringValue(),
            GeoConstants.GEO_DIMENSION.stringValue(),
            GeoConstants.GEO_COORDINATE_DIMENSION.stringValue(),
            GeoConstants.GEO_SPATIAL_DIMENSION.stringValue(),
            GeoConstants.GEO_IS_EMPTY.stringValue(),
            GeoConstants.GEO_IS_SIMPLE.stringValue(),
            GeoConstants.EXT_AREA.stringValue(),
            GeoConstants.EXT_CLOSEST_POINT.stringValue(),
            GeoConstants.EXT_CONTAINS_PROPERLY.stringValue(),
            GeoConstants.EXT_COVERED_BY.stringValue(),
            GeoConstants.EXT_COVERS.stringValue(),
            GeoConstants.EXT_HAUSDORFF_DISTANCE.stringValue(),
            GeoConstants.EXT_SHORTEST_LINE.stringValue(),
            GeoConstants.EXT_SIMPLIFY.stringValue(),
            GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY.stringValue(),
            GeoConstants.EXT_IS_VALID.stringValue()
    );

    private GeoSparqlFunctionRegistration() {
    }

    public static void registerAll() {
        FunctionRegistry registry = FunctionRegistry.getInstance();
        for (String uri : SUPPORTED_FUNCTION_URIS) {
            registry.add(new GeoSparqlRdf4jFunction(uri));
        }
    }

    static List<String> supportedFunctionUris() {
        return SUPPORTED_FUNCTION_URIS;
    }
}
