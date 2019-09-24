package com.ontotext.trree.geosparql;

import org.apache.lucene.spatial.query.SpatialOperation;
import org.locationtech.jts.geom.Geometry;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * GeoSPARQL indexer interface.
 */
public interface GeoSparqlIndexer {
	/**
	 * Initializes the indexer.
	 */
	void initialize() throws Exception;

	/**
	 * Indexes an entity (Geometry) with the predicate asWKT/asGML.
	 *
	 * @param subject  id of the subject
	 * @param geometries geometries that corresponds to asWKT/asGML's object
	 */
	void indexGeometryList(long subject, Function<Long, String> subjectMapper, List<Geometry> geometries);

	/**
	 * Returns an iterator over entities/geometries that matches the provided geometry
	 * using the provided spatial operation.
	 *
	 * @param geometry a geometry
	 * @param spatialOperation the spatial operation to filter by
	 * @return an iterator over entities/geometries
	 */
	EntityGeometryIterator getMatchingObjects(Geometry geometry, SpatialOperation spatialOperation);

	/**
	 * Returns an iterator over all geometries for the provided entity
	 *
	 * @param subject subject id of the entity
	 * @return an iterator over entities/geometries
	 */
	EntityGeometryIterator getGeometriesFor(long subject);

	void initSettings();

	void begin() throws Exception;

	void commit() throws Exception;

	void rollback() throws Exception;

	void indexGeometry(long subject, Function<Long, String> subjectMapper, Geometry geometry);

	void freshIndex() throws Exception;

	Path getIndexDir();
}
