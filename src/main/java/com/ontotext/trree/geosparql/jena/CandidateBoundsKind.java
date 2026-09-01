package com.ontotext.trree.geosparql.jena;

/**
 * How an {@link IndexGeometry} CRS84 candidate envelope was produced.
 *
 * <p>{@link #EMPTY} is the non-spatial sentinel for an empty source. {@link #NATIVE_CRS84} is the source envelope of
 * a CRS84 geometry. {@link #TRANSFORMED} is a selective envelope produced by the SIS {@code CoordinateOperation}
 * envelope transformation, including combined direct and horizontal source-to-CRS84 operations for sources with a
 * three-dimensional CRS and horizontal operations for other transformable non-CRS84 sources. Its use for candidate
 * pruning relies on the conservative-envelope engineering assumption documented by
 * {@link ConservativeCrs84EnvelopeProjector}.
 * {@code TRANSFORMED} does not mean mathematically proven complete. {@link #WORLD_FALLBACK} is the geographic world
 * rectangle used only when that envelope cannot be stored as one Lucene rectangle, the source has no usable
 * horizontal CRS, or transform construction fails.
 *
 * <p>The kind is persisted with each Lucene source document as candidate-envelope provenance.
 */
public enum CandidateBoundsKind {
	EMPTY,
	NATIVE_CRS84,
	TRANSFORMED,
	WORLD_FALLBACK
}
