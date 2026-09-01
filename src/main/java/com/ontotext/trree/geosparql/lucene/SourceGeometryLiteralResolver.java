package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Reuses validated source geometry literal snapshots within one entity traversal and rejects conflicting caches.
 */
final class SourceGeometryLiteralResolver {
	private final Map<StoredSourceGeometryIdentity, ResolvedSource> cache = new HashMap<>();

	SourceGeometryLiteral resolve(StoredSourceGeometryIdentity identity, byte[] sourceGeometryWkb,
			DimensionInfo dimensionInfo) {
		ResolvedSource resolved = cache.get(identity);
		if (resolved != null) {
			if (!resolved.matches(sourceGeometryWkb, dimensionInfo)) {
				throw new PluginException(LuceneGeoDocumentSchema.SCHEMA_MISMATCH_MESSAGE);
			}
			return resolved.source;
		}
		SourceGeometryLiteral source = loadAndValidate(identity, sourceGeometryWkb, dimensionInfo);
		cache.put(identity, new ResolvedSource(source, sourceGeometryWkb, dimensionInfo));
		return source;
	}

	void clear() {
		cache.clear();
	}

	private static SourceGeometryLiteral loadAndValidate(StoredSourceGeometryIdentity identity,
			byte[] sourceGeometryWkb, DimensionInfo dimensionInfo) {
		SimpleValueFactory values = SimpleValueFactory.getInstance();
		return SourceGeometryLiteral.fromStoredGeometry(identity.lexicalForm(),
				values.createIRI(identity.datatype()), identity.effectiveSourceCrsUri(),
				SourceGeometryWkbCodec.decode(sourceGeometryWkb), dimensionInfo);
	}

	private static final class ResolvedSource {
		private final SourceGeometryLiteral source;
		private final byte[] sourceGeometryWkb;
		private final int coordinateDimension;
		private final int spatialDimension;
		private final int topologicalDimension;

		private ResolvedSource(SourceGeometryLiteral source, byte[] sourceGeometryWkb, DimensionInfo dimensions) {
			this.source = source;
			this.sourceGeometryWkb = sourceGeometryWkb;
			this.coordinateDimension = dimensions.getCoordinate();
			this.spatialDimension = dimensions.getSpatial();
			this.topologicalDimension = dimensions.getTopological();
		}

		private boolean matches(byte[] otherSourceGeometryWkb, DimensionInfo otherDimensions) {
			return Arrays.equals(sourceGeometryWkb, otherSourceGeometryWkb)
					&& coordinateDimension == otherDimensions.getCoordinate()
					&& spatialDimension == otherDimensions.getSpatial()
					&& topologicalDimension == otherDimensions.getTopological();
		}
	}
}
