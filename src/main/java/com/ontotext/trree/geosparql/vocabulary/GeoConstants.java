/*
 * Copyright 2011 by TalkingTrends (Amsterdam, The Netherlands)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://opensahara.com/licenses/apache-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.ontotext.trree.geosparql.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

public interface GeoConstants {
    String NS_GEO = "http://www.opengis.net/ont/geosparql#";
    String NS_GEOF = "http://www.opengis.net/def/function/geosparql/";
    String NS_EXT = "http://rdf.useekm.com/ext#";

    IRI XMLSCHEMA_SPATIAL_TEXT = SimpleValueFactory.getInstance().createIRI("http://rdf.opensahara.com/type/geo/wkt");
    IRI XMLSCHEMA_SPATIAL_TEXTGZ = SimpleValueFactory.getInstance().createIRI("http://rdf.opensahara.com/type/geo/wkt.gz");
    IRI XMLSCHEMA_SPATIAL_BIN = SimpleValueFactory.getInstance().createIRI("http://rdf.opensahara.com/type/geo/wkb");
    IRI XMLSCHEMA_SPATIAL_BINGZ = SimpleValueFactory.getInstance().createIRI("http://rdf.opensahara.com/type/geo/wkb.gz");
    IRI XMLSCHEMA_OGC_WKT = SimpleValueFactory.getInstance().createIRI(NS_GEO + "wktLiteral");

    //URI[] GEO_SUPPORTED = {XMLSCHEMA_OGC_WKT, XMLSCHEMA_SPATIAL_BIN, XMLSCHEMA_SPATIAL_BINGZ, XMLSCHEMA_SPATIAL_TEXT, XMLSCHEMA_SPATIAL_TEXTGZ};

    //URI GEO_SPATIAL_OBJECT = SimpleValueFactory.getInstance().createIRI(NS_GEO + "SpatialObject");
    //URI GEO_FEATURE = SimpleValueFactory.getInstance().createIRI(NS_GEO + "Feature");
    //URI GEO_GEOMETRY = SimpleValueFactory.getInstance().createIRI(NS_GEO + "Geometry");
    IRI GEO_WKT_LITERAL = SimpleValueFactory.getInstance().createIRI(NS_GEO + "wktLiteral");
    IRI GEO_GML_LITERAL = SimpleValueFactory.getInstance().createIRI(NS_GEO + "gmlLiteral");

    IRI GEO_SF_EQUALS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfEquals");
    IRI GEO_SF_DISJOINT = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfDisjoint");
    IRI GEO_SF_INTERSECTS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfIntersects");
    IRI GEO_SF_TOUCHES = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfTouches");
    IRI GEO_SF_CROSSES = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfCrosses");
    IRI GEO_SF_WITHIN = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfWithin");
    IRI GEO_SF_CONTAINS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfContains");
    IRI GEO_SF_OVERLAPS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "sfOverlaps");

    IRI GEO_EH_EQUALS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehEquals");
    IRI GEO_EH_DISJOINT = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehDisjoint");
    IRI GEO_EH_MEET = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehMeet");
    IRI GEO_EH_OVERLAP = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehOverlap");
    IRI GEO_EH_COVERS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehCovers");
    IRI GEO_EH_COVERED_BY = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehCoveredBy");
    IRI GEO_EH_INSIDE = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehInside");
    IRI GEO_EH_CONTAINS = SimpleValueFactory.getInstance().createIRI(NS_GEO + "ehContains");

    IRI GEO_RCC8_EQ = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8eq");
    IRI GEO_RCC8_DC = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8dc");
    IRI GEO_RCC8_EC = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8ec");
    IRI GEO_RCC8_PO = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8po");
    IRI GEO_RCC8_TPPI = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8tppi");
    IRI GEO_RCC8_TPP = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8tpp");
    IRI GEO_RCC8_NTPP = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8ntpp");
    IRI GEO_RCC8_NTPPI = SimpleValueFactory.getInstance().createIRI(NS_GEO + "rcc8ntppi");

    IRI GEOF_RELATE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "relate");

    IRI GEOF_SF_EQUALS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfEquals");
    IRI GEOF_SF_DISJOINT = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfDisjoint");
    IRI GEOF_SF_INTERSECTS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfIntersects");
    IRI GEOF_SF_TOUCHES = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfTouches");
    IRI GEOF_SF_CROSSES = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfCrosses");
    IRI GEOF_SF_WITHIN = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfWithin");
    IRI GEOF_SF_CONTAINS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfContains");
    IRI GEOF_SF_OVERLAPS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "sfOverlaps");

    IRI GEOF_EH_EQUALS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehEquals");
    IRI GEOF_EH_DISJOINT = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehDisjoint");
    IRI GEOF_EH_MEET = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehMeet");
    IRI GEOF_EH_OVERLAP = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehOverlap");
    IRI GEOF_EH_COVERS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehCovers");
    IRI GEOF_EH_COVERED_BY = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehCoveredBy");
    IRI GEOF_EH_INSIDE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehInside");
    IRI GEOF_EH_CONTAINS = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "ehContains");

    IRI GEOF_RCC8_EQ = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8eq");
    IRI GEOF_RCC8_DC = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8dc");
    IRI GEOF_RCC8_EC = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8ec");
    IRI GEOF_RCC8_PO = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8po");
    IRI GEOF_RCC8_TPPI = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8tppi");
    IRI GEOF_RCC8_TPP = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8tpp");
    IRI GEOF_RCC8_NTPP = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8ntpp");
    IRI GEOF_RCC8_NTPPI = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "rcc8ntppi");

    IRI GEOF_DISTANCE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "distance");
    IRI GEOF_BUFFER = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "buffer");
    IRI GEOF_CONVEX_HULL = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "convexHull");
    IRI GEOF_INTERSECTION = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "intersection");
    IRI GEOF_UNION = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "union");
    IRI GEOF_DIFFERENCE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "difference");
    IRI GEOF_SYM_DIFFERENCE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "symDifference");
    IRI GEOF_ENVELOPE = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "envelope");
    IRI GEOF_BOUNDARY = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "boundary");
    IRI GEOF_GETSRID = SimpleValueFactory.getInstance().createIRI(NS_GEOF + "getSRID");

    IRI EXT_AREA = SimpleValueFactory.getInstance().createIRI(NS_EXT + "area");
    IRI EXT_CLOSEST_POINT = SimpleValueFactory.getInstance().createIRI(NS_EXT + "closestPoint");
    IRI EXT_CONTAINS_PROPERLY = SimpleValueFactory.getInstance().createIRI(NS_EXT + "containsProperly");
    IRI EXT_COVERED_BY = SimpleValueFactory.getInstance().createIRI(NS_EXT + "coveredBy");
    IRI EXT_COVERS = SimpleValueFactory.getInstance().createIRI(NS_EXT + "covers");
    IRI EXT_HAUSDORFF_DISTANCE = SimpleValueFactory.getInstance().createIRI(NS_EXT + "hausdorffDistance");
    IRI EXT_SHORTEST_LINE = SimpleValueFactory.getInstance().createIRI(NS_EXT + "shortestLine");
    IRI EXT_SIMPLIFY = SimpleValueFactory.getInstance().createIRI(NS_EXT + "simplify");
    IRI EXT_SIMPLIFY_PRESERVE_TOPOLOGY = SimpleValueFactory.getInstance().createIRI(NS_EXT + "simplifyPreserveTopology");
    IRI EXT_IS_VALID = SimpleValueFactory.getInstance().createIRI(NS_EXT + "isValid");

    IRI GEO_AS_WKT = SimpleValueFactory.getInstance().createIRI(NS_GEO + "asWKT");
    IRI GEO_AS_GML = SimpleValueFactory.getInstance().createIRI(NS_GEO + "asGML");

    IRI GEO_DIMENSION = SimpleValueFactory.getInstance().createIRI(NS_GEO + "dimension");
    IRI GEO_COORDINATE_DIMENSION = SimpleValueFactory.getInstance().createIRI(NS_GEO + "coordinateDimension");
    IRI GEO_SPATIAL_DIMENSION = SimpleValueFactory.getInstance().createIRI(NS_GEO + "spatialDimension");
    IRI GEO_IS_EMPTY = SimpleValueFactory.getInstance().createIRI(NS_GEO + "isEmpty");
    IRI GEO_IS_SIMPLE = SimpleValueFactory.getInstance().createIRI(NS_GEO + "isSimple");
    IRI GEO_HAS_SERIALIZATION = SimpleValueFactory.getInstance().createIRI(NS_GEO + "hasSerialization");
    IRI GEO_HAS_DEFAULT_GEOMETRY = SimpleValueFactory.getInstance().createIRI(NS_GEO + "hasDefaultGeometry");

    IRI GEO_GEOMETRY = SimpleValueFactory.getInstance().createIRI(NS_GEO + "Geometry");
    IRI GEO_FEATURE = SimpleValueFactory.getInstance().createIRI(NS_GEO + "Feature");
}
