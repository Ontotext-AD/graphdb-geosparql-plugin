package com.ontotext.trree.geosparql.gml;

import com.ontotext.trree.geosparql.conversion.CRSUtil;
import com.ontotext.trree.geosparql.conversion.TransformCoordinateFilter;
import com.useekm.types.exception.InvalidGeometryException;
import org.jvnet.ogc.gml.v_3_1_1.ObjectFactory;
import org.locationtech.jts.geom.Geometry;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.MathTransform;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Utility class for converting GML literals to JTS Geometry objects.
 *
 * Any known coordinate systems will be converted to CRS84.
 */
public class GmlConverter {
	private Unmarshaller unmarshaller;
	private Marshaller marshaller;

	public GmlConverter() throws JAXBException {
		try {
			ClassLoader cl = ObjectFactory.class.getClassLoader();
			JAXBContext context = JAXBContext.newInstance("org.jvnet.ogc.gml.v_3_1_1.jts", cl);
			unmarshaller = context.createUnmarshaller();
			marshaller = context.createMarshaller();
		} catch (Throwable e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	public Geometry gmlToGeometry(String literalValue) throws InvalidGeometryException {
		try {
			Geometry g = (Geometry) unmarshaller.unmarshal(new StringReader(literalValue));
			String srsName = (String) g.getUserData();
			if (srsName != null) {
				String shortId = CRSUtil.crsUriToShortId(srsName);
				if (shortId != null && !shortId.equals(CRSUtil.TARGET_CRS)) {
					MathTransform mathTransform = null;
					try {
						mathTransform = CRSUtil.findMathTransform(shortId, CRSUtil.TARGET_CRS);
					} catch (FactoryException e) {
						throw new InvalidGeometryException("Unable to find SRS", e);
					}
					if (mathTransform != null) {
						g.apply(new TransformCoordinateFilter(mathTransform));
						g.setSRID(4326);
					}
				}
			}
			return g;
		} catch (JAXBException e) {
			throw new InvalidGeometryException("Invalid GML geometry", e);
		}
	}

	public String geometryToGml(Geometry g) {
		try {
			StringWriter sw = new StringWriter();
			marshaller.marshal(g, sw);
			return sw.toString();
		} catch (JAXBException e) {
			throw new RuntimeException("Unable to serialize to GML", e);
		}
	}
}
