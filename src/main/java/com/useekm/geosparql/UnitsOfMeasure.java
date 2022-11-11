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
package com.useekm.geosparql;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import si.uom.SI;

import javax.measure.MetricPrefix;
import javax.measure.quantity.Length;
import javax.measure.Unit;
import java.util.HashMap;
import java.util.Map;

public final class UnitsOfMeasure {
    public static final String NS_OGC = "http://www.opengis.net/def/uom/OGC/1.0/";
    public static final String NS_EXT_LENGTH = "http://rdf.useekm.com/uom/length/";

    public static final String CENTIMETRE = "cm";
    public static final IRI URI_CENTIMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + CENTIMETRE);
    public static final String KILOMETRE = "km";
    public static final IRI URI_KILOMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + KILOMETRE);
    public static final String MILLIMETRE = "mm";
    public static final IRI URI_MILLIMETRE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + MILLIMETRE);
    public static final String METRE = "metre";
    public static final IRI URI_METRE = SimpleValueFactory.getInstance().createIRI(NS_OGC + METRE);
    public static final String FOOT = "ft";
    public static final IRI URI_FOOT = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + FOOT);
    public static final String US_SURVEY_FOOT = "US_survey_ft";
    public static final IRI URI_US_SURVEY_FOOT = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + US_SURVEY_FOOT);
    public static final String INCH = "inch";
    public static final IRI URI_INCH = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + INCH);
    public static final String LIGHT_YEAR = "ly";
    public static final IRI URI_LIGHT_YEAR = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + LIGHT_YEAR);
    public static final String MILE = "mile";
    public static final IRI URI_MILE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + MILE);
    public static final String NAUTICAL_MILE = "NM";
    public static final IRI URI_NAUTICAL_MILE = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + NAUTICAL_MILE);
    public static final String YARD = "yd";
    public static final IRI URI_YARD = SimpleValueFactory.getInstance().createIRI(NS_EXT_LENGTH + YARD);

    private static final Map<String, Unit<Length>> UNITS = new HashMap<String, Unit<Length>>();
    static {
        UNITS.put(URI_CENTIMETRE.stringValue(), MetricPrefix.CENTI(SI.METRE));
        UNITS.put(URI_KILOMETRE.stringValue(), MetricPrefix.KILO(SI.METRE));
        UNITS.put(URI_MILLIMETRE.stringValue(), MetricPrefix.MILLI(SI.METRE));
        UNITS.put(URI_METRE.stringValue(), SI.METRE);
        UNITS.put(URI_FOOT.stringValue(), SI.METRE.multiply(0.3048D));
        UNITS.put(URI_US_SURVEY_FOOT.stringValue(), SI.METRE.multiply(1200D/3937D));
        UNITS.put(URI_INCH.stringValue(), SI.METRE.multiply(0.0254D));
        UNITS.put(URI_LIGHT_YEAR.stringValue(), SI.METRE.multiply(9.460528405E15D));
        UNITS.put(URI_MILE.stringValue(), SI.METRE.multiply(1609.344D));
        UNITS.put(URI_NAUTICAL_MILE.stringValue(), SI.METRE.multiply(1852D));
        UNITS.put(URI_YARD.stringValue(), SI.METRE.multiply(0.9144D));
    }

    /**
     * @return The unit of measure for the given URI, or null if the provided value is not an uri, or is not an uri of a known unit of measure.
     */
    public static Unit<Length> getUnit(Value unitOfMeasure) {
        return unitOfMeasure instanceof IRI ? UNITS.get(unitOfMeasure.stringValue()) : null;
    }
}
