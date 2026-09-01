package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.StatementIterator;

import java.util.function.Function;

/**
 * Rebuilds the complete GeoSPARQL Lucene index from repository geometry statements.
 *
 * <p>{@link GeoSparqlPlugin} uses this class for enable-time indexing and explicit force reindex. A rebuild starts
 * from a fresh index, then visits {@code geo:asWKT}, {@code geo:asGML}, and Feature
 * {@code geo:hasDefaultGeometry} statements in that order. Each source geometry literal is converted and indexed
 * before the repository iterator advances.
 *
 * <p>Unlike {@link RepositoryGeometrySource}, this traversal does not cache decoded geometries or collect Geometry
 * and Feature identifiers across the repository.
 */
public class GeoSparqlFullIndexer {
	private final GeoSparqlIndexer indexer;
	private final GeoSparqlPlugin plugin;

	public GeoSparqlFullIndexer(GeoSparqlIndexer indexer, GeoSparqlPlugin plugin) {
		this.indexer = indexer;
		this.plugin = plugin;
	}

	/**
	 * Deletes the current spatial index contents and indexes all supported repository geometry statements.
	 *
	 * @param pluginConnection repository connection whose statement and entity views are traversed
	 * @throws Exception if the index cannot be reset for the rebuild
	 */
	public void reindex(PluginConnection pluginConnection) throws Exception {
		indexer.freshIndex();
		Function<Long, String> subjectMapper = subject ->
				pluginConnection.getEntities().get(subject).stringValue();
		indexGeometryResources(pluginConnection, plugin.asWKT, subjectMapper);
		indexGeometryResources(pluginConnection, plugin.asGML, subjectMapper);
		indexFeatures(pluginConnection, subjectMapper);
	}

	private void indexGeometryResources(PluginConnection pluginConnection, long predicate,
			Function<Long, String> subjectMapper) {
		StatementIterator iterator = pluginConnection.getStatements().get(0, predicate, 0);
		try {
			while (iterator.next()) {
				indexLiteral(pluginConnection.getEntities(), iterator.subject, iterator.subject, iterator.object,
						predicate, subjectMapper);
			}
		} finally {
			iterator.close();
		}
	}

	private void indexFeatures(PluginConnection pluginConnection, Function<Long, String> subjectMapper) {
		StatementIterator iterator = pluginConnection.getStatements().get(0, plugin.hasDefaultGeometry, 0);
		try {
			while (iterator.next()) {
				indexFeatureGeometry(pluginConnection, iterator.subject, iterator.object, plugin.asWKT, subjectMapper);
				indexFeatureGeometry(pluginConnection, iterator.subject, iterator.object, plugin.asGML, subjectMapper);
			}
		} finally {
			iterator.close();
		}
	}

	private void indexFeatureGeometry(PluginConnection pluginConnection, long featureId, long geometryResourceId,
			long predicate, Function<Long, String> subjectMapper) {
		StatementIterator iterator = pluginConnection.getStatements().get(geometryResourceId, predicate, 0);
		try {
			while (iterator.next()) {
				indexLiteral(pluginConnection.getEntities(), geometryResourceId, featureId, iterator.object, predicate,
						subjectMapper);
			}
		} finally {
			iterator.close();
		}
	}

	private void indexLiteral(Entities entities, long geometryResourceId, long indexedEntityId, long literalId,
			long predicate, Function<Long, String> subjectMapper) {
		IndexGeometry geometry = plugin.getIndexGeometryFromLiteralId(geometryResourceId, literalId,
				predicate, entities);
		if (geometry != null) {
			indexer.appendGeometry(indexedEntityId, subjectMapper, geometry);
		}
	}
}
