package com.ontotext.trree.geosparql.jena;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

public final class GeoSparqlUnits {
    public static final String NS_OGC = "http://www.opengis.net/def/uom/OGC/1.0/";
    public static final String NS_EXT_LENGTH = "http://rdf.useekm.com/uom/length/";

    public static final IRI URI_CENTIMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "cm");
    public static final IRI URI_KILOMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "km");
    public static final IRI URI_MILLIMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "mm");
    public static final IRI URI_DEGREE = SimpleValueFactory.getInstance().createIRI(NS_OGC + "degree");
    public static final IRI URI_GRID_SPACING = SimpleValueFactory.getInstance().createIRI(NS_OGC + "GridSpacing");
    public static final IRI URI_METRE = SimpleValueFactory.getInstance().createIRI(NS_OGC + "metre");
    public static final IRI URI_RADIAN = SimpleValueFactory.getInstance().createIRI(NS_OGC + "radian");
    public static final IRI URI_UNITY = SimpleValueFactory.getInstance().createIRI(NS_OGC + "unity");
    public static final IRI URI_FOOT = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "ft");
    public static final IRI URI_US_SURVEY_FOOT = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "US_survey_ft");
    public static final IRI URI_INCH = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "inch");
    public static final IRI URI_LIGHT_YEAR = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "ly");
    public static final IRI URI_MILE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "mile");
    public static final IRI URI_NAUTICAL_MILE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "NM");
    public static final IRI URI_YARD = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + "yd");

    private GeoSparqlUnits() {
    }
}
