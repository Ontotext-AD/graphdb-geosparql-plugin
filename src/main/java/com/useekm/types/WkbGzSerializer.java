/*
 * Copyright 2013 by TalkingTrends (Amsterdam, The Netherlands)
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
package com.useekm.types;

import com.useekm.indexing.GeoConstants;
import com.useekm.types.exception.InvalidGeometryException;
import org.apache.commons.codec.binary.Base64;
import org.eclipse.rdf4j.model.IRI;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKBWriter;

public final class WkbGzSerializer extends AbstractGeoSerializer {
    public static final WkbGzSerializer INTANCE = new WkbGzSerializer();

    private WkbGzSerializer() {}

    @Override public String toLiteral(Geometry geometry) {
        return new String(Base64.encodeBase64(AbstractGeo.gzip(new WKBWriter().write(geometry))));
    }

    @Override public Geometry toGeometry(String value) throws InvalidGeometryException {
        return value.isEmpty() ? GeoConvert.getEmptyGeometry() : AbstractGeo.binaryToGeometry(AbstractGeo.gunzip(Base64.decodeBase64(value.getBytes())));
    }

    @Override public AbstractGeo toGeo(String value) {
        return new GeoWkbGz(value);
    }

    @Override public IRI getDatatype() {
        return GeoConstants.XMLSCHEMA_SPATIAL_BINGZ;
    }

    @Override public Class<? extends AbstractGeo> getGeoClass() {
        return GeoWkbGz.class;
    }
}
