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
import org.locationtech.spatial4j.shape.Rectangle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lucene GeoSPARQL document schema v2.
 */
final class LuceneGeoDocumentSchema {
	/** Per-document GeoSPARQL Lucene schema version. */
	static final int SCHEMA_VERSION = 2;
	/** GraphDB entity id, stored for reconstruction and indexed/doc-valued for lookup and grouping. */
	static final String FIELD_ID = "id";
	/** Indexed terms for one CRS84 source envelope; absent on empty sentinels. */
	static final String FIELD_SPATIAL_PREFIX = "geoData1";
	/** Indexed exact lower X bound used only for safe envelope-containment pruning. */
	static final String FIELD_ENVELOPE_MIN_X = "geoEnvelopeMinX";
	/** Indexed exact upper X bound used only for safe envelope-containment pruning. */
	static final String FIELD_ENVELOPE_MAX_X = "geoEnvelopeMaxX";
	/** Indexed exact lower Y bound used only for safe envelope-containment pruning. */
	static final String FIELD_ENVELOPE_MIN_Y = "geoEnvelopeMinY";
	/** Indexed exact upper Y bound used only for safe envelope-containment pruning. */
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
	/** Stored CRS URI used to derive the index envelope in geoData1; currently always CRS84. */
	static final String FIELD_INDEX_CRS = "geoIndexCrs";
	/** Indexed and stored marker separating spatial envelope documents from non-spatial empty sentinels. */
	static final String FIELD_HAS_ENVELOPE = "geoHasEnvelope";
	/** Indexed and stored candidate-envelope provenance. */
	static final String FIELD_CANDIDATE_BOUNDS_KIND = "geoCandidateBoundsKind";
	static final String HAS_ENVELOPE_VALUE = "1";
	static final String NO_ENVELOPE_VALUE = "0";
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
			Envelope envelope = geometry.indexEnvelope();
			Rectangle shape = ctx.getShapeFactory().rect(
					envelope.getMinX(), envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY());
			for (Field field : strategy.createIndexableFields(shape)) {
				doc.add(field);
			}
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

	static Query sourceCrsQuery(String sourceCrsUri) {
		return new TermQuery(new Term(FIELD_SOURCE_CRS, sourceCrsUri));
	}

	static Query envelopeWithinQuery(Envelope boundEnvelope) {
		return new BooleanQuery.Builder()
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_X,
						boundEnvelope.getMinX(), Double.POSITIVE_INFINITY),
						BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_X,
						Double.NEGATIVE_INFINITY, boundEnvelope.getMaxX()),
						BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MIN_Y,
						boundEnvelope.getMinY(), Double.POSITIVE_INFINITY),
						BooleanClause.Occur.FILTER)
				.add(DoublePoint.newRangeQuery(FIELD_ENVELOPE_MAX_Y,
						Double.NEGATIVE_INFINITY, boundEnvelope.getMaxY()),
						BooleanClause.Occur.FILTER)
				.build();
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
