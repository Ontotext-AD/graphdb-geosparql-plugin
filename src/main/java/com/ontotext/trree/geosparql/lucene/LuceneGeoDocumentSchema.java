package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.context.jts.JtsSpatialContext;
import org.locationtech.spatial4j.context.jts.JtsSpatialContextFactory;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lucene GeoSPARQL document schema v2.
 */
final class LuceneGeoDocumentSchema {
	private static final Logger LOGGER = LoggerFactory.getLogger(LuceneGeoDocumentSchema.class);
	private static final JtsSpatialContext PLANAR_COVER_CONTEXT = planarCoverContext();
	/** Per-document GeoSPARQL Lucene schema version. */
	static final int SCHEMA_VERSION = 2;
	/** GraphDB entity id, stored for reconstruction and indexed/doc-valued for lookup and grouping. */
	static final String FIELD_ID = "id";
	/** Prefix cells of the CRS84 index envelope; absent on empty sentinels. */
	static final String FIELD_SPATIAL_PREFIX = "geoData1";
	/** Prefix cells of the planar convex hull of a native CRS84 source; absent where no hull was indexed. */
	static final String FIELD_NATIVE_HULL_PREFIX = "geoNativeHullCells";
	/** Indexed marker present on exactly the documents that carry native hull cells. */
	static final String FIELD_HAS_NATIVE_HULL_CELLS = "geoHasNativeHullCells";
	/** Indexed exact lower X bound used for envelope intersection and containment pruning. */
	static final String FIELD_ENVELOPE_MIN_X = "geoEnvelopeMinX";
	/** Indexed exact upper X bound used for envelope intersection and containment pruning. */
	static final String FIELD_ENVELOPE_MAX_X = "geoEnvelopeMaxX";
	/** Indexed exact lower Y bound used for envelope intersection and containment pruning. */
	static final String FIELD_ENVELOPE_MIN_Y = "geoEnvelopeMinY";
	/** Indexed exact upper Y bound used for envelope intersection and containment pruning. */
	static final String FIELD_ENVELOPE_MAX_Y = "geoEnvelopeMaxY";
	/** Stored WKB for the complete native-CRS source geometry used to construct Jena exact-evaluation input. */
	static final String FIELD_SOURCE_GEOMETRY = "geoData";
	/** Stored per-document schema number used for defensive validation after the commit-level check. */
	static final String FIELD_SCHEMA_VERSION = "geoSchemaVersion";
	/** Stored authoritative RDF lexical form; the compatibility name does not denote a CRS84 geometry. */
	static final String FIELD_SOURCE_LEXICAL_FORM = "geoExactLexicalForm";
	/** Stored original RDF datatype IRI used to recover the source geometry literal's semantics. */
	static final String FIELD_SOURCE_DATATYPE = "geoExactDatatype";
	/** Indexed and stored effective source CRS URI associated with the native-CRS WKB and exact evaluation. */
	static final String FIELD_SOURCE_CRS = "geoExactCrs";
	/** Stored source coordinate dimension used to reconstruct dimension metadata, including for empty geometries. */
	static final String FIELD_SOURCE_COORDINATE_DIMENSION = "geoSourceCoordinateDimension";
	/** Stored source spatial dimension used with the coordinate dimension to distinguish XYZ from XYM. */
	static final String FIELD_SOURCE_SPATIAL_DIMENSION = "geoSourceSpatialDimension";
	/** Stored and doc-valued source topological dimension preserving relation-family applicability. */
	static final String FIELD_SOURCE_TOPOLOGICAL_DIMENSION = "geoSourceTopologicalDimension";
	/** Stored CRS URI of numeric envelopes and prefix covers; currently always CRS84. */
	static final String FIELD_INDEX_CRS = "geoIndexCrs";
	/** Indexed and stored marker separating spatial envelope documents from non-spatial empty sentinels. */
	static final String FIELD_HAS_ENVELOPE = "geoHasEnvelope";
	/** Indexed and stored candidate-envelope provenance. */
	static final String FIELD_CANDIDATE_BOUNDS_KIND = "geoCandidateBoundsKind";
	static final String HAS_ENVELOPE_VALUE = "1";
	static final String NO_ENVELOPE_VALUE = "0";
	static final String HAS_NATIVE_HULL_CELLS_VALUE = "1";
	/** Ends of the CRS84 longitude range, which name the same meridian. */
	private static final double MINIMUM_LONGITUDE = -180.0;
	private static final double MAXIMUM_LONGITUDE = 180.0;
	/** Latitudes of the poles, where every meridian meets. */
	private static final double SOUTH_POLE_LATITUDE = -90.0;
	private static final double NORTH_POLE_LATITUDE = 90.0;
	/** Commit metadata key used for the constant-time index-level schema compatibility check. */
	static final String COMMIT_SCHEMA_VERSION_KEY = "geosparql.luceneSchemaVersion";
	/** Commit metadata value for the required schema version. */
	static final String COMMIT_SCHEMA_VERSION_VALUE = Integer.toString(SCHEMA_VERSION);
	/** Commit metadata key identifying the concrete field layout within the schema version. */
	static final String COMMIT_SCHEMA_LAYOUT_KEY = "geosparql.luceneSchemaLayout";
	/** Commit metadata value requiring source WKB, exact envelope bounds, provenance, and indexed source CRS. */
	static final String COMMIT_SCHEMA_LAYOUT_VALUE =
			"prefix-envelope-ordinate-preserving-source-wkb-envelope-marker-topology-dv-envelope-points-bounds-kind-indexed-source-crs";
	/** Commit metadata key identifying the CRS transformation inputs used for candidate envelopes. */
	static final String COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY = "geosparql.crsEnvironmentFingerprint";
	static final String SCHEMA_MISMATCH_MESSAGE =
			"Existing GeoSPARQL Lucene index does not match the required schema v2 layout. "
			+ "Jena-backed CRS-correct evaluation requires a full GeoSPARQL reindex. "
			+ "Queries are unavailable until reindex completes; run the documented force-reindex control or command.";

	private LuceneGeoDocumentSchema() {
	}

	static List<Document> toDocuments(long entityId, List<IndexGeometry> geometries,
			SpatialStrategy strategy, SpatialStrategy nativeHullStrategy, SpatialContext ctx) {
		Map<SourceGeometryLiteral, IndexGeometry> distinctSources = new LinkedHashMap<>();
		for (IndexGeometry geometry : geometries) {
			distinctSources.putIfAbsent(geometry.sourceGeometryLiteral(), geometry);
		}
		List<Document> documents = new ArrayList<>(distinctSources.size());
		for (IndexGeometry geometry : distinctSources.values()) {
			documents.add(toDocument(entityId, geometry, strategy, nativeHullStrategy, ctx));
		}
		return documents;
	}

	static Document toDocument(long entityId, IndexGeometry geometry,
			SpatialStrategy strategy, SpatialStrategy nativeHullStrategy, SpatialContext ctx) {
		SourceGeometryLiteral source = geometry.sourceGeometryLiteral();
		byte[] sourceGeometryWkb = SourceGeometryWkbCodec.encode(source);
		DimensionInfo dimensions = source.asGeometryWrapper().getDimensionInfo();

		Document doc = new Document();
		doc.add(new LongPoint(FIELD_ID, entityId));
		doc.add(new StoredField(FIELD_ID, entityId));
		doc.add(new NumericDocValuesField(FIELD_ID, entityId));

		if (geometry.isSpatialCandidate()) {
			Envelope envelope = geometry.indexEnvelope();
			Rectangle shape = ctx.getShapeFactory().rect(
					envelope.getMinX(), envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY());
			for (Field field : strategy.createIndexableFields(shape)) {
				doc.add(field);
			}
			addNativeHullCells(doc, geometry, nativeHullStrategy);
			doc.add(new DoublePoint(FIELD_ENVELOPE_MIN_X, envelope.getMinX()));
			doc.add(new DoublePoint(FIELD_ENVELOPE_MAX_X, envelope.getMaxX()));
			doc.add(new DoublePoint(FIELD_ENVELOPE_MIN_Y, envelope.getMinY()));
			doc.add(new DoublePoint(FIELD_ENVELOPE_MAX_Y, envelope.getMaxY()));
		}
		doc.add(new StringField(FIELD_HAS_ENVELOPE,
				geometry.isSpatialCandidate() ? HAS_ENVELOPE_VALUE : NO_ENVELOPE_VALUE, Field.Store.YES));
		doc.add(new StringField(FIELD_CANDIDATE_BOUNDS_KIND,
				geometry.candidateBoundsKind().name(), Field.Store.YES));
		// Empty source geometry literals still need an entity-bearing document so full scans can reconstruct
		// them for exact predicate evaluation. Their sentinel document deliberately has no Lucene spatial fields.

		doc.add(new StoredField(FIELD_SOURCE_GEOMETRY, sourceGeometryWkb));
		doc.add(new StoredField(FIELD_SCHEMA_VERSION, SCHEMA_VERSION));
		doc.add(new StoredField(FIELD_SOURCE_LEXICAL_FORM, source.lexicalForm()));
		doc.add(new StoredField(FIELD_SOURCE_DATATYPE, source.datatype().stringValue()));
		doc.add(new StringField(FIELD_SOURCE_CRS, source.effectiveCrsUri(), Field.Store.YES));
		doc.add(new StoredField(FIELD_SOURCE_COORDINATE_DIMENSION, dimensions.getCoordinate()));
		doc.add(new StoredField(FIELD_SOURCE_SPATIAL_DIMENSION, dimensions.getSpatial()));
		doc.add(new StoredField(FIELD_SOURCE_TOPOLOGICAL_DIMENSION, dimensions.getTopological()));
		doc.add(new NumericDocValuesField(FIELD_SOURCE_TOPOLOGICAL_DIMENSION, dimensions.getTopological()));
		doc.add(new StoredField(FIELD_INDEX_CRS, geometry.indexCrs()));

		return doc;
	}

	private static JtsSpatialContext planarCoverContext() {
		JtsSpatialContextFactory factory = new JtsSpatialContextFactory();
		factory.geo = false;
		return factory.newSpatialContext();
	}

	/**
	 * Adds the prefix cells of the planar convex hull of a native CRS84 source and the marker identifying documents
	 * that carry them. A source gets neither, and remains an envelope candidate, when its hull is unavailable or
	 * fills its envelope: the hull of a point, an axis-parallel line or a rectangle separates no bound that the
	 * numeric envelope ranges admit.
	 */
	private static void addNativeHullCells(Document doc, IndexGeometry geometry, SpatialStrategy nativeHullStrategy) {
		if (geometry.candidateBoundsKind() != CandidateBoundsKind.NATIVE_CRS84) {
			return;
		}
		Field[] hullCells;
		try {
			// Native CRS84 topology is planar. Geographic wrapping would change the covered point set.
			var hull = geometry.sourceGeometryLiteral().asGeometryWrapper().getXYGeometry().convexHull();
			boolean fillsEnvelope = hull.getEnvelopeInternal().getArea() == 0 || hull.isRectangle();
			if (hull.isEmpty() || !hull.isValid() || fillsEnvelope) {
				return;
			}
			JtsGeometry cover = new JtsGeometry(hull, PLANAR_COVER_CONTEXT, false, false);
			cover.index();
			hullCells = nativeHullStrategy.createIndexableFields(cover);
		} catch (RuntimeException e) {
			// Hull cells are an optional refinement; a source without them remains an envelope candidate.
			LOGGER.debug("Indexing a native CRS84 source without convex hull cells", e);
			return;
		}
		for (Field cell : hullCells) {
			doc.add(cell);
		}
		doc.add(new StringField(FIELD_HAS_NATIVE_HULL_CELLS, HAS_NATIVE_HULL_CELLS_VALUE, Field.Store.NO));
	}

	static SourceGeometryLiteral sourceGeometryLiteral(Document doc, SourceGeometryLiteralResolver sourceResolver) {
		assertCurrentSchemaDocument(doc);
		byte[] sourceGeometryWkb = binaryFieldValue(doc, FIELD_SOURCE_GEOMETRY);
		DimensionInfo dimensions = sourceDimensionInfo(doc);
		StoredSourceGeometryIdentity identity = new StoredSourceGeometryIdentity(
				doc.get(FIELD_SOURCE_LEXICAL_FORM),
				doc.get(FIELD_SOURCE_DATATYPE),
				doc.get(FIELD_SOURCE_CRS));
		return sourceResolver.resolve(identity, sourceGeometryWkb, dimensions);
	}

	static long entityId(Document doc) {
		IndexableField field = doc.getField(FIELD_ID);
		if (field == null || field.numericValue() == null) {
			throw new PluginException("GeoSPARQL Lucene document is missing entity id.");
		}
		return field.numericValue().longValue();
	}

	static boolean isCurrentSchemaDocument(Document doc) {
		IndexableField schemaVersion = doc.getField(FIELD_SCHEMA_VERSION);
		return schemaVersion != null
				&& schemaVersion.numericValue() != null
				&& schemaVersion.numericValue().intValue() == SCHEMA_VERSION
				&& doc.getField(FIELD_SOURCE_GEOMETRY) != null
				&& doc.getField(FIELD_SOURCE_GEOMETRY).binaryValue() != null
				&& doc.get(FIELD_SOURCE_LEXICAL_FORM) != null
				&& doc.get(FIELD_SOURCE_DATATYPE) != null
				&& hasIndexedSourceCrs(doc)
				&& numericFieldValue(doc, FIELD_SOURCE_COORDINATE_DIMENSION) != null
				&& numericFieldValue(doc, FIELD_SOURCE_SPATIAL_DIMENSION) != null
				&& numericFieldValue(doc, FIELD_SOURCE_TOPOLOGICAL_DIMENSION) != null
				&& hasValidEnvelopeMarker(doc)
				&& hasValidCandidateBoundsKind(doc)
				&& IndexGeometry.INDEX_CRS.equals(doc.get(FIELD_INDEX_CRS));
	}

	static void assertCurrentSchemaDocument(Document doc) {
		if (!isCurrentSchemaDocument(doc)) {
			throw new PluginException(SCHEMA_MISMATCH_MESSAGE);
		}
	}

	static boolean hasCurrentSchemaCommitData(Map<String, String> commitData) {
		return COMMIT_SCHEMA_VERSION_VALUE.equals(commitData.get(COMMIT_SCHEMA_VERSION_KEY))
				&& COMMIT_SCHEMA_LAYOUT_VALUE.equals(commitData.get(COMMIT_SCHEMA_LAYOUT_KEY));
	}

	static Iterable<Map.Entry<String, String>> currentCompatibilityCommitData(
			Iterable<Map.Entry<String, String>> existingCommitData, String crsEnvironmentFingerprint) {
		Map<String, String> commitData = new LinkedHashMap<>();
		if (existingCommitData != null) {
			for (Map.Entry<String, String> entry : existingCommitData) {
				commitData.put(entry.getKey(), entry.getValue());
			}
		}
		commitData.put(COMMIT_SCHEMA_VERSION_KEY, COMMIT_SCHEMA_VERSION_VALUE);
		commitData.put(COMMIT_SCHEMA_LAYOUT_KEY, COMMIT_SCHEMA_LAYOUT_VALUE);
		commitData.put(COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY, crsEnvironmentFingerprint);
		return commitData.entrySet();
	}

	static Query entityIdQuery(long entityId) {
		return LongPoint.newExactQuery(FIELD_ID, entityId);
	}

	static Query entityIdsAtLeastQuery(long entityId) {
		return LongPoint.newRangeQuery(FIELD_ID, entityId, Long.MAX_VALUE);
	}

	static Query allDocumentsQuery() {
		return new MatchAllDocsQuery();
	}

	static Query hasEnvelopeQuery(boolean hasEnvelope) {
		return new TermQuery(new Term(FIELD_HAS_ENVELOPE,
				hasEnvelope ? HAS_ENVELOPE_VALUE : NO_ENVELOPE_VALUE));
	}

	static Query candidateBoundsKindQuery(CandidateBoundsKind kind) {
		return new TermQuery(new Term(FIELD_CANDIDATE_BOUNDS_KIND, kind.name()));
	}

	/** Matches the documents that carry native hull cells. */
	static Query hasNativeHullCellsQuery() {
		return new TermQuery(new Term(FIELD_HAS_NATIVE_HULL_CELLS, HAS_NATIVE_HULL_CELLS_VALUE));
	}

	/** Whether the reader holds a source whose index envelope is transformed or a world fallback. */
	static boolean hasNonNativeBounds(IndexReader reader) throws IOException {
		return reader.docFreq(new Term(FIELD_CANDIDATE_BOUNDS_KIND, CandidateBoundsKind.TRANSFORMED.name())) > 0
				|| reader.docFreq(new Term(FIELD_CANDIDATE_BOUNDS_KIND, CandidateBoundsKind.WORLD_FALLBACK.name())) > 0;
	}

	static Query sourceCrsQuery(String sourceCrsUri) {
		return new TermQuery(new Term(FIELD_SOURCE_CRS, sourceCrsUri));
	}

	static Query envelopeIntersectsQuery(Envelope boundEnvelope) {
		// Numeric point ranges distinguish signed zeros, while geometric coordinate comparisons do not.
		double minX = boundEnvelope.getMinX() == 0.0 ? -0.0 : boundEnvelope.getMinX();
		double maxX = boundEnvelope.getMaxX() == 0.0 ? 0.0 : boundEnvelope.getMaxX();
		double minY = boundEnvelope.getMinY() == 0.0 ? -0.0 : boundEnvelope.getMinY();
		double maxY = boundEnvelope.getMaxY() == 0.0 ? 0.0 : boundEnvelope.getMaxY();
		Query overlapping = new BooleanQuery.Builder()
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_X,
						Double.NEGATIVE_INFINITY, maxX), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_X,
						minX, Double.POSITIVE_INFINITY), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_Y,
						Double.NEGATIVE_INFINITY, maxY), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_Y,
						minY, Double.POSITIVE_INFINITY), BooleanClause.Occur.FILTER)
				.build();
		Query meetingDespiteLongitude = envelopeMeetsDespiteLongitudeQuery(boundEnvelope);
		if (meetingDespiteLongitude == null) {
			return overlapping;
		}
		return new BooleanQuery.Builder()
				.add(overlapping, BooleanClause.Occur.SHOULD)
				.add(meetingDespiteLongitude, BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	static Query envelopeEqualsQuery(Envelope boundEnvelope) {
		return new BooleanQuery.Builder()
				.add(envelopeOrdinateEqualsQuery(FIELD_ENVELOPE_MIN_X, boundEnvelope.getMinX()),
						BooleanClause.Occur.FILTER)
				.add(envelopeOrdinateEqualsQuery(FIELD_ENVELOPE_MAX_X, boundEnvelope.getMaxX()),
						BooleanClause.Occur.FILTER)
				.add(envelopeOrdinateEqualsQuery(FIELD_ENVELOPE_MIN_Y, boundEnvelope.getMinY()),
						BooleanClause.Occur.FILTER)
				.add(envelopeOrdinateEqualsQuery(FIELD_ENVELOPE_MAX_Y, boundEnvelope.getMaxY()),
						BooleanClause.Occur.FILTER)
				.build();
	}

	/**
	 * Matches envelopes strictly separated from the bound on at least one axis, except those meeting it across the
	 * antimeridian or at a pole.
	 */
	static Query envelopeDisjointQuery(Envelope boundEnvelope) {
		Query separated = new BooleanQuery.Builder()
				.add(ordinateLessThanQuery(FIELD_ENVELOPE_MAX_X, boundEnvelope.getMinX()), BooleanClause.Occur.SHOULD)
				.add(ordinateGreaterThanQuery(FIELD_ENVELOPE_MIN_X, boundEnvelope.getMaxX()), BooleanClause.Occur.SHOULD)
				.add(ordinateLessThanQuery(FIELD_ENVELOPE_MAX_Y, boundEnvelope.getMinY()), BooleanClause.Occur.SHOULD)
				.add(ordinateGreaterThanQuery(FIELD_ENVELOPE_MIN_Y, boundEnvelope.getMaxY()), BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
		Query meetingDespiteLongitude = envelopeMeetsDespiteLongitudeQuery(boundEnvelope);
		if (meetingDespiteLongitude == null) {
			return separated;
		}
		return new BooleanQuery.Builder()
				.add(separated, BooleanClause.Occur.FILTER)
				.add(meetingDespiteLongitude, BooleanClause.Occur.MUST_NOT)
				.build();
	}

	/**
	 * Matches envelopes that can meet the bound although their longitude ranges are apart, or returns null for a
	 * bound away from the antimeridian and the poles.
	 */
	private static Query envelopeMeetsDespiteLongitudeQuery(Envelope boundEnvelope) {
		Query acrossAntimeridian = envelopeMeetsAcrossAntimeridianQuery(boundEnvelope);
		Query atPole = envelopeMeetsAtPoleQuery(boundEnvelope);
		if (acrossAntimeridian == null || atPole == null) {
			return acrossAntimeridian == null ? atPole : acrossAntimeridian;
		}
		return new BooleanQuery.Builder()
				.add(acrossAntimeridian, BooleanClause.Occur.SHOULD)
				.add(atPole, BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	/**
	 * Matches envelopes that reach the same polar cap as the bound, or returns null for a bound away from the poles.
	 *
	 * <p>Meridians converge at a pole. Within {@link IndexGeometry#POLAR_CAP} of it, Jena's cleanup of a coordinate
	 * transformed into a projected CRS can place sources from different meridians at one position, so longitude
	 * does not separate envelopes there.
	 */
	private static Query envelopeMeetsAtPoleQuery(Envelope boundEnvelope) {
		double southernCap = SOUTH_POLE_LATITUDE + IndexGeometry.POLAR_CAP;
		double northernCap = NORTH_POLE_LATITUDE - IndexGeometry.POLAR_CAP;
		boolean boundReachesSouthernCap = boundEnvelope.getMinY() <= southernCap;
		boolean boundReachesNorthernCap = boundEnvelope.getMaxY() >= northernCap;
		if (!boundReachesSouthernCap && !boundReachesNorthernCap) {
			return null;
		}
		BooleanQuery.Builder sameCap = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
		if (boundReachesSouthernCap) {
			sameCap.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_Y,
					Double.NEGATIVE_INFINITY, southernCap), BooleanClause.Occur.SHOULD);
		}
		if (boundReachesNorthernCap) {
			sameCap.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_Y,
					northernCap, Double.POSITIVE_INFINITY), BooleanClause.Occur.SHOULD);
		}
		return sameCap.build();
	}

	/**
	 * Matches envelopes that meet the bound across the antimeridian, or returns null for a bound away from it.
	 *
	 * <p>Longitudes +180 and -180 are one meridian, and exact evaluation in a projected CRS treats it as continuous.
	 * Envelopes that end within {@link IndexGeometry#ANTIMERIDIAN_MARGIN} of opposite ends of the longitude range
	 * therefore meet when their closed latitude ranges overlap.
	 */
	private static Query envelopeMeetsAcrossAntimeridianQuery(Envelope boundEnvelope) {
		double westernEnd = MINIMUM_LONGITUDE + IndexGeometry.ANTIMERIDIAN_MARGIN;
		double easternEnd = MAXIMUM_LONGITUDE - IndexGeometry.ANTIMERIDIAN_MARGIN;
		boolean boundReachesEasternEnd = boundEnvelope.getMaxX() >= easternEnd;
		boolean boundReachesWesternEnd = boundEnvelope.getMinX() <= westernEnd;
		if (!boundReachesEasternEnd && !boundReachesWesternEnd) {
			return null;
		}
		BooleanQuery.Builder oppositeEnd = new BooleanQuery.Builder().setMinimumNumberShouldMatch(1);
		if (boundReachesEasternEnd) {
			oppositeEnd.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_X,
					Double.NEGATIVE_INFINITY, westernEnd), BooleanClause.Occur.SHOULD);
		}
		if (boundReachesWesternEnd) {
			oppositeEnd.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_X,
					easternEnd, Double.POSITIVE_INFINITY), BooleanClause.Occur.SHOULD);
		}
		double minY = boundEnvelope.getMinY() == 0.0 ? -0.0 : boundEnvelope.getMinY();
		double maxY = boundEnvelope.getMaxY() == 0.0 ? 0.0 : boundEnvelope.getMaxY();
		return new BooleanQuery.Builder()
				.add(oppositeEnd.build(), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_Y,
						Double.NEGATIVE_INFINITY, maxY), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_Y,
						minY, Double.POSITIVE_INFINITY), BooleanClause.Occur.FILTER)
				.build();
	}

	/** Matches envelopes contained by the closed bound, treating signed zeros as equal. */
	static Query envelopeWithinQuery(Envelope boundEnvelope) {
		double minX = boundEnvelope.getMinX() == 0.0 ? -0.0 : boundEnvelope.getMinX();
		double maxX = boundEnvelope.getMaxX() == 0.0 ? 0.0 : boundEnvelope.getMaxX();
		double minY = boundEnvelope.getMinY() == 0.0 ? -0.0 : boundEnvelope.getMinY();
		double maxY = boundEnvelope.getMaxY() == 0.0 ? 0.0 : boundEnvelope.getMaxY();
		return new BooleanQuery.Builder()
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_X,
						minX, Double.POSITIVE_INFINITY), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_X,
						Double.NEGATIVE_INFINITY, maxX), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_Y,
						minY, Double.POSITIVE_INFINITY), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_Y,
						Double.NEGATIVE_INFINITY, maxY), BooleanClause.Occur.FILTER)
				.build();
	}

	/** Matches envelopes extending beyond at least one side of the closed bound. */
	static Query envelopeNotWithinQuery(Envelope boundEnvelope) {
		return new BooleanQuery.Builder()
				.add(ordinateLessThanQuery(FIELD_ENVELOPE_MIN_X, boundEnvelope.getMinX()), BooleanClause.Occur.SHOULD)
				.add(ordinateGreaterThanQuery(FIELD_ENVELOPE_MAX_X, boundEnvelope.getMaxX()), BooleanClause.Occur.SHOULD)
				.add(ordinateLessThanQuery(FIELD_ENVELOPE_MIN_Y, boundEnvelope.getMinY()), BooleanClause.Occur.SHOULD)
				.add(ordinateGreaterThanQuery(FIELD_ENVELOPE_MAX_Y, boundEnvelope.getMaxY()), BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	private static Query ordinateLessThanQuery(String field, double bound) {
		// Mathematical nextDown/nextUp exclude both signed zeros when the bound is zero.
		return DoublePoint.newRangeQuery(field, Double.NEGATIVE_INFINITY, Math.nextDown(bound));
	}

	private static Query ordinateGreaterThanQuery(String field, double bound) {
		return DoublePoint.newRangeQuery(field, Math.nextUp(bound), Double.POSITIVE_INFINITY);
	}

	private static Query envelopeOrdinateEqualsQuery(String field, double ordinate) {
		return DoublePoint.newRangeQuery(field, ordinate == 0.0 ? -0.0 : ordinate,
				ordinate == 0.0 ? 0.0 : ordinate);
	}


	private static DimensionInfo sourceDimensionInfo(Document doc) {
		Number coordinate = numericFieldValue(doc, FIELD_SOURCE_COORDINATE_DIMENSION);
		Number spatial = numericFieldValue(doc, FIELD_SOURCE_SPATIAL_DIMENSION);
		Number topological = numericFieldValue(doc, FIELD_SOURCE_TOPOLOGICAL_DIMENSION);
		if (coordinate == null || spatial == null || topological == null) {
			throw new PluginException(SCHEMA_MISMATCH_MESSAGE);
		}
		try {
			return new DimensionInfo(coordinate.intValue(), spatial.intValue(), topological.intValue());
		} catch (RuntimeException e) {
			throw new PluginException(SCHEMA_MISMATCH_MESSAGE, e);
		}
	}

	private static boolean hasValidEnvelopeMarker(Document doc) {
		String value = doc.get(FIELD_HAS_ENVELOPE);
		return HAS_ENVELOPE_VALUE.equals(value) || NO_ENVELOPE_VALUE.equals(value);
	}

	private static boolean hasValidCandidateBoundsKind(Document doc) {
		try {
			CandidateBoundsKind.valueOf(doc.get(FIELD_CANDIDATE_BOUNDS_KIND));
			return true;
		} catch (IllegalArgumentException | NullPointerException e) {
			return false;
		}
	}

	private static boolean hasIndexedSourceCrs(Document doc) {
		IndexableField field = doc.getField(FIELD_SOURCE_CRS);
		return field != null
				&& field.stringValue() != null
				&& field.fieldType().indexOptions() != IndexOptions.NONE;
	}

	private static Number numericFieldValue(Document doc, String fieldName) {
		IndexableField field = doc.getField(fieldName);
		return field == null ? null : field.numericValue();
	}

	private static byte[] binaryFieldValue(Document doc, String fieldName) {
		IndexableField field = doc.getField(fieldName);
		if (field == null || field.binaryValue() == null) {
			throw new PluginException(SCHEMA_MISMATCH_MESSAGE);
		}
		return Arrays.copyOfRange(field.binaryValue().bytes, field.binaryValue().offset,
				field.binaryValue().offset + field.binaryValue().length);
	}

}
