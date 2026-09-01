package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.StatementIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Loads GeoSPARQL geometry resources and Feature default geometries for incremental transaction updates.
 *
 * <p>Decoded {@link IndexGeometry} lists are memoized for the lifetime of each instance so a changed Geometry shared
 * by several Features is converted only once within an update. {@link GeoSparqlUpdateListener} creates an instance
 * while committing an incremental transaction and discards it after the affected entities have been reindexed.
 * Complete index builds use {@link GeoSparqlFullIndexer}, which traverses geometry statements without this cache.
 */
final class RepositoryGeometrySource {
	private final GeoSparqlPlugin plugin;
	private final PluginConnection pluginConnection;
	private final Entities entities;
	private final Map<Long, List<IndexGeometry>> geometryResourceCache = new HashMap<>();

	RepositoryGeometrySource(GeoSparqlPlugin plugin, PluginConnection pluginConnection) {
		this.plugin = plugin;
		this.pluginConnection = pluginConnection;
		this.entities = pluginConnection.getEntities();
	}

	Function<Long, String> subjectMapper() {
		return subject -> entities.get(subject).stringValue();
	}

	List<IndexGeometry> geometriesForGeometryResource(long geometryResourceId) {
		return geometryResourceCache.computeIfAbsent(geometryResourceId, this::loadGeometriesForGeometryResource);
	}

	private List<IndexGeometry> loadGeometriesForGeometryResource(long geometryResourceId) {
		List<IndexGeometry> geometries = new ArrayList<>();
		addGeometriesWithPredicate(geometryResourceId, plugin.asWKT, geometries);
		addGeometriesWithPredicate(geometryResourceId, plugin.asGML, geometries);
		return Collections.unmodifiableList(geometries);
	}

	List<IndexGeometry> geometriesForFeature(long featureId) {
		List<IndexGeometry> geometries = new ArrayList<>();
		StatementIterator iterator = pluginConnection.getStatements().get(featureId, plugin.hasDefaultGeometry, 0);
		try {
			while (iterator.next()) {
				geometries.addAll(geometriesForGeometryResource(iterator.object));
			}
		} finally {
			iterator.close();
		}
		return geometries;
	}

	void forEachFeatureUsingGeometry(long geometryResourceId, FeatureConsumer consumer) {
		StatementIterator iterator = pluginConnection.getStatements().get(0, plugin.hasDefaultGeometry,
				geometryResourceId);
		try {
			while (iterator.next()) {
				consumer.accept(iterator.subject, geometriesForFeature(iterator.subject));
			}
		} finally {
			iterator.close();
		}
	}

	private void addGeometriesWithPredicate(long geometryResourceId, long predicate, List<IndexGeometry> geometries) {
		StatementIterator iterator = pluginConnection.getStatements().get(geometryResourceId, predicate, 0);
		try {
			while (iterator.next()) {
				IndexGeometry geometry = plugin.getIndexGeometryFromLiteralId(geometryResourceId, iterator.object,
						predicate, entities);
				if (geometry != null) {
					geometries.add(geometry);
				}
			}
		} finally {
			iterator.close();
		}
	}

}

interface FeatureConsumer {
	void accept(long featureId, List<IndexGeometry> geometries);
}
