package com.ontotext.trree.geosparql.jena.query;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygonal;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;

/** Evaluates within using prepared polygon containment for point sources while preserving source CRS precision. */
public final class GeometryWithin {
	private GeometryWithin() {
	}

	public static boolean calculate(GeometryWrapper source, GeometryWrapper target)
			throws FactoryException, TransformException {
		if (source.getXYGeometry() instanceof Point && target.getXYGeometry() instanceof Polygonal) {
			// Align the target before inverse containment so coordinate cleanup stays in the source CRS.
			GeometryWrapper alignedTarget = source.checkTransformSRS(target);
			return alignedTarget.contains(source);
		}
		return source.within(target);
	}
}
