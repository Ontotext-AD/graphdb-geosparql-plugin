/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */
package com.ontotext.trree.geosparql.jena.query;

import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.datatype.GeometryDatatype;
import org.apache.jena.geosparql.implementation.datatype.WKTDatatype;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opengis.referencing.operation.TransformException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GeometryWithinTest {
	private static final String CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84";
	private static final String EPSG4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
	private static final String POLYGON = "POLYGON((0 0,10 0,10 10,0 10,0 0),(3 3,3 7,7 7,7 3,3 3))";

	@BeforeClass
	public static void setupGeometryDatatypes() {
		SRSRegistry.setupDefaultSRS();
		GeometryDatatype.registerDatatypes();
	}

	@Test
	public void pointWithinPolygonRespectsInteriorBoundaryHolesAndExterior() throws Exception {
		GeometryWrapper polygon = geometry(POLYGON);
		assertTrue(GeometryWithin.calculate(geometry("POINT(1 1)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(0 5)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(3 5)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(5 5)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(11 5)"), polygon));
	}

	@Test
	public void pointWithinMultiPolygonUsesComponentInteriors() throws Exception {
		GeometryWrapper polygon = geometry("MULTIPOLYGON(((0 0,10 0,10 10,0 10,0 0)),((20 20,30 20,30 30,20 30,20 20)))");
		assertTrue(GeometryWithin.calculate(geometry("POINT(1 1)"), polygon));
		assertTrue(GeometryWithin.calculate(geometry("POINT(21 21)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(15 15)"), polygon));
		assertFalse(GeometryWithin.calculate(geometry("POINT(20 25)"), polygon));
	}

	@Test
	public void emptyPointAndAreaDoNotSatisfyWithin() throws Exception {
		assertFalse(GeometryWithin.calculate(geometry("POINT EMPTY"), geometry(POLYGON)));
		assertFalse(GeometryWithin.calculate(geometry("POINT(1 1)"), geometry("POLYGON EMPTY")));
		assertFalse(GeometryWithin.calculate(geometry("POINT(1 1)"), geometry("MULTIPOLYGON EMPTY")));
		assertFalse(GeometryWithin.calculate(geometry("POINT EMPTY"), geometry("POLYGON EMPTY")));
	}

	@Test
	public void nativePointRetainsFullCoordinatePrecision() throws Exception {
		GeometryWrapper point = geometry("POINT(1.0000004 1)");
		GeometryWrapper polygon = geometry("POLYGON((0 0,1.0000003 0,1.0000003 2,0 2,0 0))");
		assertEquals(1.0000004, point.getXYGeometry().getCoordinate().x, 0);
		assertFalse(GeometryWithin.calculate(point, polygon));
	}

	@Test
	public void targetIsTransformedToTheSourceCrsBeforeWithin() throws Exception {
		int precision = GeoSPARQLConfig.DECIMAL_PLACES_PRECISION;
		try {
			GeoSPARQLConfig.DECIMAL_PLACES_PRECISION = 6;
			GeometryWrapper point = geometry("<" + CRS84 + "> POINT(1.0000004 1)");
			GeometryWrapper polygon = geometry("<" + EPSG4326 + "> POLYGON((0 0,0 1.0000003,2 1.0000003,2 0,0 0))");
			assertFalse(GeometryWithin.calculate(point, polygon));
			assertTrue(polygon.contains(point));
		} finally {
			GeoSPARQLConfig.DECIMAL_PLACES_PRECISION = precision;
		}
	}

	@Test
	public void disabledTargetTransformationIsRejected() throws Exception {
		boolean allowTransformation = GeoSPARQLConfig.ALLOW_GEOMETRY_SRS_TRANSFORMATION;
		try {
			GeoSPARQLConfig.ALLOW_GEOMETRY_SRS_TRANSFORMATION = false;
			GeometryWrapper point = geometry("<" + CRS84 + "> POINT(1 1)");
			GeometryWrapper polygon = geometry("<" + EPSG4326 + "> POLYGON((0 0,0 2,2 2,2 0,0 0))");
			assertThrows(TransformException.class, () -> GeometryWithin.calculate(point, polygon));
		} finally {
			GeoSPARQLConfig.ALLOW_GEOMETRY_SRS_TRANSFORMATION = allowTransformation;
		}
	}

	@Test
	public void genericCollectionWithinPreservesComponentResults() throws Exception {
		GeometryWrapper point = geometry("POINT(1 1)");
		GeometryWrapper collection = geometry("GEOMETRYCOLLECTION(POLYGON((0 0,2 0,2 2,0 2,0 0)))");
		assertTrue(GeometryWithin.calculate(point, collection));
		assertFalse(GeometryWithin.calculate(geometry("POINT(0 1)"), collection));
		GeometryWrapper source = geometry("GEOMETRYCOLLECTION(POINT(1 1))");
		assertTrue(GeometryWithin.calculate(source, geometry(POLYGON)));
	}

	@Test
	public void nonPointSourcesRetainWithinResults() throws Exception {
		GeometryWrapper polygon = geometry(POLYGON);
		GeometryWrapper line = geometry("LINESTRING(1 1,2 2)");
		GeometryWrapper crossingLine = geometry("LINESTRING(1 1,11 1)");
		GeometryWrapper area = geometry("POLYGON((1 1,2 1,2 2,1 2,1 1))");
		assertTrue(GeometryWithin.calculate(line, polygon));
		assertFalse(GeometryWithin.calculate(crossingLine, polygon));
		assertTrue(GeometryWithin.calculate(area, polygon));
		assertFalse(GeometryWithin.calculate(area, geometry("POINT(1 1)")));
	}

	private static GeometryWrapper geometry(String wkt) {
		return GeometryWrapper.extract(wkt, WKTDatatype.URI);
	}
}
