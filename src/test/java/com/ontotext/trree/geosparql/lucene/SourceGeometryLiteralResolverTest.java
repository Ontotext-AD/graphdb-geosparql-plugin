package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.junit.Test;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SourceGeometryLiteralResolverTest {
	@Test
	public void identicalPersistedSourceCacheReusesOneSnapshot() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt("POINT(1 2)");
		byte[] sourceWkb = SourceGeometryWkbCodec.encode(source);
		DimensionInfo dimensions = source.asGeometryWrapper().getDimensionInfo();
		StoredSourceGeometryIdentity identity = identity(source);
		SourceGeometryLiteralResolver resolver = new SourceGeometryLiteralResolver();

		assertSame(resolver.resolve(identity, sourceWkb, dimensions),
				resolver.resolve(identity, sourceWkb, dimensions));
	}

	@Test
	public void repeatedIdentityWithDifferentWkbRequiresForceReindex() {
		SourceGeometryLiteral first = SourceGeometryLiteral.fromWkt("POINT(1 2)");
		SourceGeometryLiteral conflicting = SourceGeometryLiteral.fromWkt("POINT(3 4)");
		StoredSourceGeometryIdentity identity = identity(first);
		SourceGeometryLiteralResolver resolver = new SourceGeometryLiteralResolver();
		resolver.resolve(identity, SourceGeometryWkbCodec.encode(first),
				first.asGeometryWrapper().getDimensionInfo());

		PluginException exception = assertThrows(PluginException.class, () -> resolver.resolve(identity,
				SourceGeometryWkbCodec.encode(conflicting), conflicting.asGeometryWrapper().getDimensionInfo()));

		assertTrue(exception.getMessage().contains("force-reindex"));
	}

	@Test
	public void repeatedIdentityWithDifferentDimensionsRequiresForceReindex() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt("POINT(1 2)");
		StoredSourceGeometryIdentity identity = identity(source);
		byte[] sourceWkb = SourceGeometryWkbCodec.encode(source);
		SourceGeometryLiteralResolver resolver = new SourceGeometryLiteralResolver();
		resolver.resolve(identity, sourceWkb, source.asGeometryWrapper().getDimensionInfo());

		PluginException exception = assertThrows(PluginException.class,
				() -> resolver.resolve(identity, sourceWkb, new DimensionInfo(3, 3, 0)));

		assertTrue(exception.getMessage().contains("force-reindex"));
	}

	@Test
	public void clearingAtEntityBoundaryAllowsIdentityToBeReconstructedAgain() {
		SourceGeometryLiteral first = SourceGeometryLiteral.fromWkt("POINT(1 2)");
		SourceGeometryLiteral second = SourceGeometryLiteral.fromWkt("POINT(3 4)");
		StoredSourceGeometryIdentity identity = identity(first);
		SourceGeometryLiteralResolver resolver = new SourceGeometryLiteralResolver();
		SourceGeometryLiteral firstResolved = resolver.resolve(identity, SourceGeometryWkbCodec.encode(first),
				first.asGeometryWrapper().getDimensionInfo());

		resolver.clear();
		SourceGeometryLiteral secondResolved = resolver.resolve(identity, SourceGeometryWkbCodec.encode(second),
				second.asGeometryWrapper().getDimensionInfo());

		assertNotSame(firstResolved, secondResolved);
		assertTrue(secondResolved.asGeometryWrapper().getXYGeometry()
				.equalsExact(second.asGeometryWrapper().getXYGeometry()));
	}

	private StoredSourceGeometryIdentity identity(SourceGeometryLiteral source) {
		return new StoredSourceGeometryIdentity(source.lexicalForm(), source.datatype().stringValue(),
				source.effectiveCrsUri());
	}
}
