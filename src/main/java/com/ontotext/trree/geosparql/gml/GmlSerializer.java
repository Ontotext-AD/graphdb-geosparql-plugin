package com.ontotext.trree.geosparql.gml;

import com.useekm.indexing.GeoConstants;
import com.useekm.types.AbstractGeo;
import com.useekm.types.AbstractGeoSerializer;
import com.useekm.types.GeoConvert;
import com.useekm.types.exception.InvalidGeometryException;
import org.eclipse.rdf4j.model.IRI;
import org.locationtech.jts.geom.Geometry;

import jakarta.xml.bind.JAXBException;

/**
 * GML serializer according to what Useekm expects.
 * Needed so that GML literals work when passed directly to GeoSPARQL functions.
 */
public class GmlSerializer extends AbstractGeoSerializer {
    public final static GmlSerializer INSTANCE = new GmlSerializer();

    private final GmlConverter gmlConverter;

    public GmlSerializer() {
        try {
            gmlConverter = new GmlConverter();
        } catch (JAXBException e) {
            throw new RuntimeException("Unable to instantiate GML converter.");
        }
    }

    @Override
    public String toLiteral(Geometry geometry) {
        return gmlConverter.geometryToGml(geometry);
    }

    @Override
    public Geometry toGeometry(String value) throws InvalidGeometryException {
        return value.isEmpty() ? GeoConvert.getEmptyGeometry() : gmlConverter.gmlToGeometry(value);
    }

    @Override
    public AbstractGeo toGeo(String value) {
        return new GeoGml(value);
    }

    @Override
    public IRI getDatatype() {
        return GeoConstants.GEO_GML_LITERAL;
    }

    @Override
    public Class<? extends AbstractGeo> getGeoClass() {
        return GeoGml.class;
    }
}
