package com.ontotext.trree.geosparql.lucene;

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
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.util.BytesRef;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Rectangle;
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
	/** Per-document GeoSPARQL Lucene schema version. */
	static final int SCHEMA_VERSION = 2;
	/** GraphDB entity id, stored for reconstruction and indexed/doc-valued for lookup and grouping. */
	static final String FIELD_ID = "id";
	/** Prefix cells of the CRS84 index envelope; absent on empty sentinels. */
	static final String FIELD_SPATIAL_PREFIX = "geoData1";
	/**
	 * Boxes that together contain the source, in its own CRS: several points of four dimensions in the ordinate
	 * order of {@link #FIELD_SOURCE_ENVELOPE}. Absent where the source's envelope describes it well enough.
	 */
	static final String FIELD_SOURCE_COVER = "geoSourceCover";
	/** Indexed marker present on exactly the documents that carry cover boxes. */
	static final String FIELD_HAS_SOURCE_COVER = "geoHasSourceCover";
	/** Indexed marker present on exactly the spatial documents of area collections; see {@link IndexGeometry}. */
	static final String FIELD_AREA_COLLECTION = "geoAreaCollection";
	/**
	 * XY envelope of the source in its own CRS, as one point of four dimensions: minimum X, maximum X, minimum Y
	 * and maximum Y. It serves lookup in the CRS where Jena evaluates a pair. Absent on empty sentinels.
	 */
	static final String FIELD_SOURCE_ENVELOPE = "geoSourceEnvelope";
	/**
	 * CRS84 index envelope of a source in another CRS, as one point of the same four dimensions. A CRS84 source's
	 * index envelope is its own envelope and is not stored a second time.
	 */
	static final String FIELD_INDEX_ENVELOPE = "geoIndexEnvelope";
	private static final int MIN_X = 0;
	private static final int MAX_X = 1;
	private static final int MIN_Y = 2;
	private static final int MAX_Y = 3;
	/** Stored WKB for the complete native-CRS source geometry used to construct Jena exact-evaluation input. */
	static final String FIELD_SOURCE_GEOMETRY = "geoData";
	/**
	 * Stored marker on exactly the documents whose WKB does not read back as the geometry Jena parses; exact
	 * evaluation parses their stored lexical form instead. See {@link SourceGeometryWkbCodec#reproduces}.
	 */
	static final String FIELD_SOURCE_FROM_LEXICAL_FORM = "geoSourceFromLexicalForm";
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
	static final String HAS_ENVELOPE_VALUE = "1";
	static final String NO_ENVELOPE_VALUE = "0";
	static final String HAS_SOURCE_COVER_VALUE = "1";
	static final String AREA_COLLECTION_VALUE = "1";
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
	/**
	 * Commit metadata value requiring source WKB, the envelope marker, indexed source CRS, each spatial source's
	 * envelope in its own CRS, the CRS84 index envelope of each source in another CRS, cover boxes for large
	 * sources, the area collection marker, the marker for sources parsed from their lexical form, and a CRS84 index
	 * envelope checked against Jena's transformation.
	 */
	static final String COMMIT_SCHEMA_LAYOUT_VALUE =
			"prefix-envelope-ordinate-preserving-source-wkb-envelope-marker-topology-dv-indexed-source-crs-source-envelope-index-envelope-source-cover-area-collection-lexical-sources-jena-crs84-envelope";
	/** Commit metadata key identifying the CRS transformation inputs used for candidate envelopes. */
	static final String COMMIT_CRS_ENVIRONMENT_FINGERPRINT_KEY = "geosparql.crsEnvironmentFingerprint";
	static final String SCHEMA_MISMATCH_MESSAGE =
			"Existing GeoSPARQL Lucene index does not match the required schema v2 layout. "
			+ "Jena-backed CRS-correct evaluation requires a full GeoSPARQL reindex. "
			+ "Queries are unavailable until reindex completes; run the documented force-reindex control or command.";

	private LuceneGeoDocumentSchema() {
	}

	static List<Document> toDocuments(long entityId, List<IndexGeometry> geometries,
			SpatialStrategy strategy, SpatialContext ctx) {
		Map<SourceGeometryLiteral, IndexGeometry> distinctSources = new LinkedHashMap<>();
		for (IndexGeometry geometry : geometries) {
			distinctSources.putIfAbsent(geometry.sourceGeometryLiteral(), geometry);
		}
		List<Document> documents = new ArrayList<>(distinctSources.size());
		for (IndexGeometry geometry : distinctSources.values()) {
			documents.add(toDocument(entityId, geometry, strategy, ctx));
		}
		return documents;
	}

	static Document toDocument(long entityId, IndexGeometry geometry,
			SpatialStrategy strategy, SpatialContext ctx) {
		SourceGeometryLiteral source = geometry.sourceGeometryLiteral();
		byte[] sourceGeometryWkb = SourceGeometryWkbCodec.encode(source);
		DimensionInfo dimensions = source.asGeometryWrapper().getDimensionInfo();

		Document doc = new Document();
		doc.add(new LongPoint(FIELD_ID, entityId));
		doc.add(new StoredField(FIELD_ID, entityId));
		doc.add(new NumericDocValuesField(FIELD_ID, entityId));

		if (geometry.isSpatialCandidate()) {
			Envelope envelope = geometry.storedIndexEnvelope();
			Rectangle shape = ctx.getShapeFactory().rect(
					envelope.getMinX(), envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY());
			for (Field field : strategy.createIndexableFields(shape)) {
				doc.add(field);
			}
			Envelope sourceEnvelope = geometry.sourceEnvelope();
			doc.add(new DoublePoint(FIELD_SOURCE_ENVELOPE, sourceEnvelope.getMinX(), sourceEnvelope.getMaxX(),
					sourceEnvelope.getMinY(), sourceEnvelope.getMaxY()));
			addSourceCover(doc, geometry);
			if (geometry.isAreaCollection()) {
				doc.add(new StringField(FIELD_AREA_COLLECTION, AREA_COLLECTION_VALUE, Field.Store.NO));
			}
			if (!IndexGeometry.INDEX_CRS.equals(source.effectiveCrsUri())) {
				doc.add(new DoublePoint(FIELD_INDEX_ENVELOPE, envelope.getMinX(), envelope.getMaxX(),
						envelope.getMinY(), envelope.getMaxY()));
			}
		}
		doc.add(new StringField(FIELD_HAS_ENVELOPE,
				geometry.isSpatialCandidate() ? HAS_ENVELOPE_VALUE : NO_ENVELOPE_VALUE, Field.Store.YES));
		// Empty source geometry literals still need an entity-bearing document so full scans can reconstruct
		// them for exact predicate evaluation. Their sentinel document deliberately has no Lucene spatial fields.

		doc.add(new StoredField(FIELD_SOURCE_GEOMETRY, sourceGeometryWkb));
		if (!SourceGeometryWkbCodec.reproduces(source, sourceGeometryWkb)) {
			doc.add(new StoredField(FIELD_SOURCE_FROM_LEXICAL_FORM, 1));
		}
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

	/**
	 * Adds the boxes that together contain a source and the marker identifying documents that carry them. A source
	 * gets neither, and is described by its envelope alone, when it has no boxes or they cannot be built: a marker
	 * without boxes would name a source that no bound can meet.
	 */
	private static void addSourceCover(Document doc, IndexGeometry geometry) {
		List<Envelope> boxes;
		try {
			boxes = SourceCoverBoxes.of(geometry.sourceGeometryLiteral().asGeometryWrapper().getXYGeometry());
		} catch (RuntimeException e) {
			LOGGER.debug("Indexing a source without cover boxes", e);
			return;
		}
		if (boxes.isEmpty()) {
			return;
		}
		for (Envelope box : boxes) {
			doc.add(new DoublePoint(FIELD_SOURCE_COVER, box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY()));
		}
		doc.add(new StringField(FIELD_HAS_SOURCE_COVER, HAS_SOURCE_COVER_VALUE, Field.Store.NO));
	}

	static SourceGeometryLiteral sourceGeometryLiteral(Document doc, SourceGeometryLiteralResolver sourceResolver) {
		assertCurrentSchemaDocument(doc);
		byte[] sourceGeometryWkb = binaryFieldValue(doc, FIELD_SOURCE_GEOMETRY);
		DimensionInfo dimensions = sourceDimensionInfo(doc);
		StoredSourceGeometryIdentity identity = new StoredSourceGeometryIdentity(
				doc.get(FIELD_SOURCE_LEXICAL_FORM),
				doc.get(FIELD_SOURCE_DATATYPE),
				doc.get(FIELD_SOURCE_CRS));
		return sourceResolver.resolve(identity, sourceGeometryWkb, dimensions,
				doc.getField(FIELD_SOURCE_FROM_LEXICAL_FORM) != null);
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

	/** Whether any document of the reader is an area collection. */
	static boolean hasAreaCollections(IndexReader reader) throws IOException {
		return reader.docFreq(new Term(FIELD_AREA_COLLECTION, AREA_COLLECTION_VALUE)) > 0;
	}

	static Query areaCollectionQuery() {
		return new TermQuery(new Term(FIELD_AREA_COLLECTION, AREA_COLLECTION_VALUE));
	}

	/** Whether any document of the reader carries cover boxes. */
	static boolean hasSourceCovers(IndexReader reader) throws IOException {
		return reader.docFreq(new Term(FIELD_HAS_SOURCE_COVER, HAS_SOURCE_COVER_VALUE)) > 0;
	}

	/**
	 * Matches sources that carry cover boxes of which none meets the closed envelope, both taken in one CRS. Such a
	 * source shares no point with a geometry inside that envelope.
	 */
	static Query sourceCoverApartQuery(Envelope envelope) {
		return new BooleanQuery.Builder()
				.add(new TermQuery(new Term(FIELD_HAS_SOURCE_COVER, HAS_SOURCE_COVER_VALUE)), BooleanClause.Occur.FILTER)
				.add(boxesMeetingQuery(FIELD_SOURCE_COVER, envelope), BooleanClause.Occur.MUST_NOT)
				.build();
	}

	/** Returns the source CRS URIs of the reader's documents. */
	static List<String> sourceCrsUris(IndexReader reader) throws IOException {
		List<String> crsUris = new ArrayList<>();
		Terms terms = MultiTerms.getTerms(reader, FIELD_SOURCE_CRS);
		if (terms == null) {
			return crsUris;
		}
		TermsEnum iterator = terms.iterator();
		for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
			crsUris.add(term.utf8ToString());
		}
		return crsUris;
	}

	/** Matches sources whose own envelopes meet the closed envelope, both taken in one CRS. */
	static Query sourceEnvelopeIntersectsQuery(Envelope envelope) {
		return boxesMeetingQuery(FIELD_SOURCE_ENVELOPE, envelope);
	}

	/** Matches documents with a box in the field that meets the closed envelope. */
	private static Query boxesMeetingQuery(String field, Envelope envelope) {
		return DoublePoint.newRangeQuery(field,
				new double[]{Double.NEGATIVE_INFINITY, lowerBound(envelope.getMinX()),
						Double.NEGATIVE_INFINITY, lowerBound(envelope.getMinY())},
				new double[]{upperBound(envelope.getMaxX()), Double.POSITIVE_INFINITY,
						upperBound(envelope.getMaxY()), Double.POSITIVE_INFINITY});
	}

	/**
	 * Matches sources whose own envelopes equal the envelope, both taken in one CRS, and sources whose envelopes are
	 * unbounded. Every source matches an unbounded envelope; see {@link IndexGeometry#comparableEnvelope}.
	 */
	static Query sourceEnvelopeEqualsQuery(Envelope envelope) {
		if (IndexGeometry.isUnbounded(envelope)) {
			return sourceEnvelopeIntersectsQuery(envelope);
		}
		return new BooleanQuery.Builder()
				.add(equalEnvelopesQuery(envelope), BooleanClause.Occur.SHOULD)
				.add(equalEnvelopesQuery(IndexGeometry.unboundedEnvelope()), BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	private static Query equalEnvelopesQuery(Envelope envelope) {
		return DoublePoint.newRangeQuery(FIELD_SOURCE_ENVELOPE,
				new double[]{lowerBound(envelope.getMinX()), lowerBound(envelope.getMaxX()),
						lowerBound(envelope.getMinY()), lowerBound(envelope.getMaxY())},
				new double[]{upperBound(envelope.getMinX()), upperBound(envelope.getMaxX()),
						upperBound(envelope.getMinY()), upperBound(envelope.getMaxY())});
	}

	/** Matches sources whose own envelopes lie inside the closed envelope, both taken in one CRS. */
	static Query sourceEnvelopeWithinQuery(Envelope envelope) {
		return DoublePoint.newRangeQuery(FIELD_SOURCE_ENVELOPE,
				new double[]{lowerBound(envelope.getMinX()), Double.NEGATIVE_INFINITY,
						lowerBound(envelope.getMinY()), Double.NEGATIVE_INFINITY},
				new double[]{Double.POSITIVE_INFINITY, upperBound(envelope.getMaxX()),
						Double.POSITIVE_INFINITY, upperBound(envelope.getMaxY())});
	}

	// Numeric point ranges distinguish signed zeros, while geometric coordinate comparisons do not.
	private static double lowerBound(double ordinate) {
		return ordinate == 0.0 ? -0.0 : ordinate;
	}

	private static double upperBound(double ordinate) {
		return ordinate == 0.0 ? 0.0 : ordinate;
	}

	static Query sourceCrsQuery(String sourceCrsUri) {
		return new TermQuery(new Term(FIELD_SOURCE_CRS, sourceCrsUri));
	}

	/**
	 * Matches sources whose CRS84 index envelopes meet the closed bound, including those that meet it despite their
	 * longitudes.
	 */
	static Query envelopeIntersectsQuery(Envelope boundEnvelope) {
		double[] lower = unbounded(Double.NEGATIVE_INFINITY);
		double[] upper = unbounded(Double.POSITIVE_INFINITY);
		upper[MIN_X] = upperBound(boundEnvelope.getMaxX());
		lower[MAX_X] = lowerBound(boundEnvelope.getMinX());
		upper[MIN_Y] = upperBound(boundEnvelope.getMaxY());
		lower[MAX_Y] = lowerBound(boundEnvelope.getMinY());
		Query overlapping = indexEnvelopeInBox(lower, upper);
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

	/**
	 * Matches CRS84 index envelopes strictly separated from the bound on at least one axis, except those meeting it
	 * across the antimeridian or at a pole.
	 */
	static Query envelopeDisjointQuery(Envelope boundEnvelope) {
		// Mathematical nextDown/nextUp exclude both signed zeros when the bound is zero.
		Query separated = new BooleanQuery.Builder()
				.add(indexEnvelopeOrdinateAtMost(MAX_X, Math.nextDown(boundEnvelope.getMinX())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtLeast(MIN_X, Math.nextUp(boundEnvelope.getMaxX())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtMost(MAX_Y, Math.nextDown(boundEnvelope.getMinY())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtLeast(MIN_Y, Math.nextUp(boundEnvelope.getMaxY())),
						BooleanClause.Occur.SHOULD)
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
	 * Matches CRS84 index envelopes that can meet the bound although their longitude ranges are apart, or returns
	 * null for a bound away from the antimeridian and the poles.
	 */
	static Query envelopeMeetsDespiteLongitudeQuery(Envelope boundEnvelope) {
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
			sameCap.add(indexEnvelopeOrdinateAtMost(MIN_Y, southernCap), BooleanClause.Occur.SHOULD);
		}
		if (boundReachesNorthernCap) {
			sameCap.add(indexEnvelopeOrdinateAtLeast(MAX_Y, northernCap), BooleanClause.Occur.SHOULD);
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
			oppositeEnd.add(latitudesOverlapping(boundEnvelope, MIN_X, Double.NEGATIVE_INFINITY, westernEnd),
					BooleanClause.Occur.SHOULD);
		}
		if (boundReachesWesternEnd) {
			oppositeEnd.add(latitudesOverlapping(boundEnvelope, MAX_X, easternEnd, Double.POSITIVE_INFINITY),
					BooleanClause.Occur.SHOULD);
		}
		return oppositeEnd.build();
	}

	/** Matches index envelopes with one ordinate in a range whose closed latitude range overlaps the bound's. */
	private static Query latitudesOverlapping(Envelope boundEnvelope, int ordinate, double from, double to) {
		double[] lower = unbounded(Double.NEGATIVE_INFINITY);
		double[] upper = unbounded(Double.POSITIVE_INFINITY);
		lower[ordinate] = from;
		upper[ordinate] = to;
		upper[MIN_Y] = upperBound(boundEnvelope.getMaxY());
		lower[MAX_Y] = lowerBound(boundEnvelope.getMinY());
		return indexEnvelopeInBox(lower, upper);
	}

	/**
	 * Matches sources whose CRS84 index envelope is the {@link IndexGeometry#worldFallbackEnvelope world fallback
	 * envelope}, which says nothing of where they lie.
	 */
	static Query worldFallbackIndexEnvelopeQuery() {
		Envelope world = IndexGeometry.worldFallbackEnvelope();
		double[] ordinates = {world.getMinX(), world.getMaxX(), world.getMinY(), world.getMaxY()};
		return DoublePoint.newRangeQuery(FIELD_INDEX_ENVELOPE, ordinates, ordinates);
	}

	/** Matches CRS84 index envelopes extending beyond at least one side of the closed bound. */
	static Query envelopeNotWithinQuery(Envelope boundEnvelope) {
		return new BooleanQuery.Builder()
				.add(indexEnvelopeOrdinateAtMost(MIN_X, Math.nextDown(boundEnvelope.getMinX())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtLeast(MAX_X, Math.nextUp(boundEnvelope.getMaxX())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtMost(MIN_Y, Math.nextDown(boundEnvelope.getMinY())),
						BooleanClause.Occur.SHOULD)
				.add(indexEnvelopeOrdinateAtLeast(MAX_Y, Math.nextUp(boundEnvelope.getMaxY())),
						BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	private static Query indexEnvelopeOrdinateAtMost(int ordinate, double value) {
		double[] upper = unbounded(Double.POSITIVE_INFINITY);
		upper[ordinate] = value;
		return indexEnvelopeInBox(unbounded(Double.NEGATIVE_INFINITY), upper);
	}

	private static Query indexEnvelopeOrdinateAtLeast(int ordinate, double value) {
		double[] lower = unbounded(Double.NEGATIVE_INFINITY);
		lower[ordinate] = value;
		return indexEnvelopeInBox(lower, unbounded(Double.POSITIVE_INFINITY));
	}

	/**
	 * Matches sources whose CRS84 index envelope, as (minimum X, maximum X, minimum Y, maximum Y), lies in a closed
	 * box. A CRS84 source's index envelope is its own envelope, which is the one it stores.
	 */
	private static Query indexEnvelopeInBox(double[] lower, double[] upper) {
		Query ownEnvelopeOfCrs84Source = new BooleanQuery.Builder()
				.add(sourceCrsQuery(IndexGeometry.INDEX_CRS), BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_SOURCE_ENVELOPE, lower, upper), BooleanClause.Occur.FILTER)
				.build();
		return new BooleanQuery.Builder()
				.add(DoublePoint.newRangeQuery(FIELD_INDEX_ENVELOPE, lower, upper), BooleanClause.Occur.SHOULD)
				.add(ownEnvelopeOfCrs84Source, BooleanClause.Occur.SHOULD)
				.setMinimumNumberShouldMatch(1)
				.build();
	}

	private static double[] unbounded(double value) {
		return new double[]{value, value, value, value};
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
