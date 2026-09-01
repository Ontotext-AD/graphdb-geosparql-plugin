package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.implementation.index.GeometryTransformIndex;

import java.math.BigDecimal;

/** Defines the Jena coordinate-cleanup precision used by indexing and exact evaluation. */
final class JenaCalculationPrecision {
	static final int DECIMAL_PLACES = 6;
	private static final double MAXIMUM_CLEANUP_DISPLACEMENT = Math.nextUp(
			BigDecimal.ONE.scaleByPowerOfTen(-DECIMAL_PLACES)
					.divide(BigDecimal.valueOf(2))
					.doubleValue());

	private JenaCalculationPrecision() {
	}

	static synchronized void configure() {
		if (GeoSPARQLConfig.DECIMAL_PLACES_PRECISION != DECIMAL_PLACES) {
			GeoSPARQLConfig.DECIMAL_PLACES_PRECISION = DECIMAL_PLACES;
			GeometryTransformIndex.clear();
		}
	}

	static double maximumCleanupDisplacement() {
		return MAXIMUM_CLEANUP_DISPLACEMENT;
	}
}
