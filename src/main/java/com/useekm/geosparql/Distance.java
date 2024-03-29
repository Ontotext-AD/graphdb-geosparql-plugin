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
package com.useekm.geosparql;

import com.useekm.indexing.GeoConstants;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.referencing.GeodeticCalculator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.opengis.referencing.operation.TransformException;
import si.uom.SI;

import javax.measure.Unit;
import javax.measure.quantity.Length;

/**
 * Computes the minimum distance between two geometries, the distance is 0 if either of the input geometries is empty.
 * 
 * Accepts two geometries, and an optional third argument that specifies the unit of distance such as http://www.opengis.net/def/uom/OGC/1.0/metre. If no unit of distance if given,
 * it returns the cartesian distance between the two points, ignoring any coordinate reference system information.
 */
public class Distance extends AbstractLiteralBinaryFunction {
    @Override public String getURI() {
        return GeoConstants.GEOF_DISTANCE.stringValue();
    }

    @Override protected Literal evaluate(ValueFactory valueFactory, IRI getype, Geometry geom1, Geometry geom2, Value... allArgs) throws ValueExprEvaluationException {
        if (allArgs.length == 3)
            return computeDistanceinUnits(valueFactory, geom1, geom2, allArgs);
        else if (allArgs.length == 2)
            return valueFactory.createLiteral(geom1.distance(geom2));
        throw new ValueExprEvaluationException("Too many arguments for distance filter");
    }

    /**
     * @return The distance in the units provided by the third argument
     * 
     * @throws ValueExprEvaluationException For invalid units or geometries for which the distance can not be calculated
     */
    public Literal computeDistanceinUnits(ValueFactory valueFactory, Geometry geom1, Geometry geom2, Value... allArgs) throws ValueExprEvaluationException {
        Value unitOfMeasure = allArgs[2];
        Unit<Length> unit = UnitsOfMeasure.getUnit(unitOfMeasure);
        if (unit == null)
            throw new ValueExprEvaluationException("Not a valid length measure unit: " + unitOfMeasure);

        Coordinate[] points = DistanceOp.nearestPoints(geom1, geom2);
        //TODO: pick up the correct CRS for use in gc, and for each DirectPosition
        GeodeticCalculator gc = new GeodeticCalculator();
        try {
            gc.setStartingPosition(new DirectPosition2D(points[0].x, points[0].y));
            gc.setDestinationPosition(new DirectPosition2D(points[1].x, points[1].y));
            double distance = gc.getOrthodromicDistance();
            return valueFactory.createLiteral(unit == SI.METRE ? distance : SI.METRE.getConverterTo(unit).convert(distance));
        } catch (TransformException e) {
            throw new ValueExprEvaluationException(e);
        } catch (IllegalArgumentException e) {
            throw new ValueExprEvaluationException(e);
        }
    }
}