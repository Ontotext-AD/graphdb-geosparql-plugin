package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.jena.geosparql.implementation.SRSInfo;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.jena.geosparql.implementation.vocabulary.SRS_URI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JenaGeometryAdapterInitializationTest {

	private static final ValueFactory VALUE_FACTORY = SimpleValueFactory.getInstance();
	private static final Literal POINT = VALUE_FACTORY.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL);
	private static final Literal AREA = VALUE_FACTORY.createLiteral("POLYGON((0 0,2 0,2 2,0 2,0 0))",
			GeoConstants.GEO_WKT_LITERAL);

	@Test
	public void scalarEvaluationReusesDefaultCrsRegistrations() {
		JenaGeometryAdapter.initialize();
		SRSInfo defaultCrs = SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84);
		SRSInfo legacyCrs = SRSRegistry.getSRSInfo(SRS_URI.WGS84_CRS_GEOSPARQL_LEGACY);

		for (int i = 0; i < 4; i++) {
			assertPointWithinArea();
			JenaGeometryAdapter.toSourceGeometryLiteral(POINT);
			assertSame(defaultCrs, SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84));
			assertSame(legacyCrs, SRSRegistry.getSRSInfo(SRS_URI.WGS84_CRS_GEOSPARQL_LEGACY));
		}
	}

	@Test
	public void adapterUsesDefaultsRestoredByRegistryReset() {
		JenaGeometryAdapter.initialize();
		SRSInfo beforeReset = SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84);

		SRSRegistry.reset();
		SRSInfo defaultCrs = SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84);
		SRSInfo legacyCrs = SRSRegistry.getSRSInfo(SRS_URI.WGS84_CRS_GEOSPARQL_LEGACY);
		assertNotSame(beforeReset, defaultCrs);

		assertPointWithinArea();
		Literal legacyPoint = VALUE_FACTORY.createLiteral("<" + SRS_URI.WGS84_CRS_GEOSPARQL_LEGACY
				+ "> POINT(1 1)", GeoConstants.GEO_WKT_LITERAL);
		assertTrue(((Literal) JenaFunctionEvaluator.evaluate(VALUE_FACTORY,
				GeoConstants.GEOF_SF_INTERSECTS.stringValue(), legacyPoint, AREA)).booleanValue());
		assertSame(defaultCrs, SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84));
		assertSame(legacyCrs, SRSRegistry.getSRSInfo(SRS_URI.WGS84_CRS_GEOSPARQL_LEGACY));
	}

	@Test
	public void concurrentScalarEvaluationsShareDefaultCrsRegistrations() throws Exception {
		ExecutorService workers = Executors.newFixedThreadPool(8);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<SRSInfo>> results = new ArrayList<>();
		try {
			for (int i = 0; i < 8; i++) {
				results.add(workers.submit(() -> {
					start.await();
					for (int j = 0; j < 16; j++) {
						assertPointWithinArea();
					}
					return SRSRegistry.getSRSInfo(SRS_URI.DEFAULT_WKT_CRS84);
				}));
			}
			start.countDown();
			SRSInfo defaultCrs = results.get(0).get(30, TimeUnit.SECONDS);
			for (Future<SRSInfo> result : results) {
				assertSame(defaultCrs, result.get(30, TimeUnit.SECONDS));
			}
		} finally {
			start.countDown();
			workers.shutdownNow();
		}
	}

	private static void assertPointWithinArea() {
		assertTrue(((Literal) JenaFunctionEvaluator.evaluate(VALUE_FACTORY,
				GeoConstants.GEOF_SF_WITHIN.stringValue(), POINT, AREA)).booleanValue());
	}
}
