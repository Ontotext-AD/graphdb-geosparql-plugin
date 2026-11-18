package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.vocabulary.SRS_URI;
import org.locationtech.jts.geom.Dimension;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.relateng.RelateNG;
import org.locationtech.jts.operation.relateng.RelatePredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Associates a source geometry literal with its derived Lucene index envelope.
 *
 * <p>Native CRS84 sources use their source envelope. Non-CRS84 sources use Apache SIS
 * {@code Envelopes.transform(CoordinateOperation, Envelope)} on the source bounding box, not the weaker
 * {@code MathTransform} overload. Sources with a three-dimensional CRS combine a direct transformation that retains
 * their vertical range with a height-independent transformation of their horizontal envelope; other sources use
 * their horizontal CRS. The resulting envelope is used only for Lucene candidate lookup. Exact evaluation uses the
 * source geometry literal and its native CRS. SIS does not prove that transform is never smaller than the complete
	 * CRS84 image. Transformed bounds are widened for the pinned Jena post-transform decimal cleanup when CRS84 is the
	 * exact-evaluation target. Candidate lookup uses the index envelope for a pair it cannot look up in the CRS where
	 * Jena evaluates it, and treats the envelope as conservative there. Partitioned disjoint lookup exact-evaluates
	 * such pairs when they cannot be classified from those envelopes because Jena rounds in the left operand's CRS.
	 * Wraparound may broaden that envelope,
 * potentially to full longitude. A representable SIS rectangle is indexed as-is, including ordinary non-CRS84
 * sources such as GDA2020 and projected MGA2020 CRSes. Non-CRS84 geometries are not mapped to the world envelope. The
 * world CRS84 envelope is used only when the result still cannot be stored as one Lucene geographic rectangle, or
 * when transform construction fails. An empty source has a null envelope and is represented by a non-spatial Lucene
 * document so exact traversal can still reconstruct it.
 */
public final class IndexGeometry {
	public static final String INDEX_CRS = SRS_URI.DEFAULT_WKT_CRS84;
	/**
	 * Longitude distance from +180 or -180 within which an index envelope is treated as reaching the antimeridian.
	 * A transformed envelope is clamped to the CRS84 longitude range after cleanup widening, so widening that would
	 * continue on the other side of that meridian is not stored. One cleanup step covers the clamped widening.
	 */
	public static final double ANTIMERIDIAN_MARGIN = 2 * JenaCalculationPrecision.maximumCleanupDisplacement();
	/**
	 * Latitude distance from a pole, about 110 metres, within which longitude does not separate index envelopes.
	 * Meridians converge at a pole. Inside this cap, Jena's cleanup of a coordinate transformed into a metre-based
	 * projected CRS spans more longitude than the cleanup widening of an index envelope.
	 */
	public static final double POLAR_CAP = 0.001;
	private static final Logger LOGGER = LoggerFactory.getLogger(IndexGeometry.class);
	private static final ConservativeCrs84EnvelopeProjector ENVELOPE_PROJECTOR =
			new ConservativeCrs84EnvelopeProjector();

	private final SourceGeometryLiteral sourceGeometryLiteral;
	private final Envelope indexEnvelope;
	// Cache the bound source dimension derived during index-geometry construction,
	// so relation traversal does not need to navigate Jena geometry metadata.
	private final int sourceTopologicalDimension;
	private final CandidateBoundsKind candidateBoundsKind;
	private final String candidateBoundsFallbackReason;

	IndexGeometry(SourceGeometryLiteral sourceGeometryLiteral, Envelope indexEnvelope,
			int sourceTopologicalDimension, CandidateBoundsKind candidateBoundsKind,
			String candidateBoundsFallbackReason) {
		this.sourceGeometryLiteral = sourceGeometryLiteral;
		this.indexEnvelope = new Envelope(indexEnvelope);
		this.sourceTopologicalDimension = sourceTopologicalDimension;
		this.candidateBoundsKind = candidateBoundsKind;
		this.candidateBoundsFallbackReason = candidateBoundsFallbackReason;
	}

	public static IndexGeometry fromSourceGeometryLiteral(SourceGeometryLiteral sourceGeometryLiteral) {
		try {
			GeometryWrapper sourceWrapper = sourceGeometryLiteral.asGeometryWrapper();
			ProjectedCandidateBounds bounds = ENVELOPE_PROJECTOR.projectBounds(sourceGeometryLiteral);
			IndexGeometry indexGeometry = new IndexGeometry(sourceGeometryLiteral, bounds.envelope(),
					sourceWrapper.getDimensionInfo().getTopological(), bounds.kind(), bounds.fallbackReason());
			if (bounds.kind() == CandidateBoundsKind.WORLD_FALLBACK) {
				LOGGER.debug("Using world CRS84 candidate envelope for source CRS {} ({})",
						sourceWrapper.getSrsURI(), bounds.fallbackReason());
			}
			return indexGeometry;
		} catch (JenaGeoSparqlException e) {
			throw e;
		} catch (Exception e) {
			throw new JenaGeoSparqlException("Unable to derive CRS84 index geometry from source geometry literal. "
					+ "Configure Apache SIS CRS data, for example SIS_DATA, if the CRS is supported.", e);
		}
	}

	public SourceGeometryLiteral sourceGeometryLiteral() {
		return sourceGeometryLiteral;
	}

	public Envelope indexEnvelope() {
		return new Envelope(indexEnvelope);
	}

	/**
	 * Returns the CRS84 index envelope to store for this source. Exact evaluation with a CRS84 subject has Jena
	 * transform the source into CRS84, so the stored envelope also contains the geometry Jena produces there, and it
	 * is the world envelope when Jena cannot transform the source. Looking up a bound uses {@link #indexEnvelope()},
	 * which needs no such transformation.
	 */
	public Envelope storedIndexEnvelope() {
		return new Envelope(storedBounds().envelope());
	}

	ProjectedCandidateBounds storedBounds() {
		if (candidateBoundsKind != CandidateBoundsKind.TRANSFORMED) {
			return new ProjectedCandidateBounds(indexEnvelope, candidateBoundsKind, candidateBoundsFallbackReason);
		}
		return ENVELOPE_PROJECTOR.widenToExactTransform(sourceGeometryLiteral, indexEnvelope);
	}

	public String indexCrs() {
		return INDEX_CRS;
	}

	/** Returns the cached source topological dimension used for candidate classification. */
	public int sourceTopologicalDimension() {
		return sourceTopologicalDimension;
	}

	/** Returns how the CRS84 candidate envelope was produced. */
	public CandidateBoundsKind candidateBoundsKind() {
		return candidateBoundsKind;
	}

	/** Returns why the world CRS84 envelope was selected, when {@link CandidateBoundsKind#WORLD_FALLBACK}. */
	public Optional<String> candidateBoundsFallbackReason() {
		return Optional.ofNullable(candidateBoundsFallbackReason);
	}

	/**
	 * Returns whether the source is a valid, non-empty CRS84 polygon that covers every point in its index envelope.
	 *
	 * <p>This deliberately excludes transformed sources, holes, rotated polygons, collections, and the polygons that
	 * {@link #fillsItsEnvelope} rejects. For an eligible native CRS84 bound, containment of another source's index envelope proves that the two sources cannot be
	 * disjoint, on the conservative-envelope engineering assumption documented by
	 * {@link ConservativeCrs84EnvelopeProjector} when that other envelope is SIS-transformed.
	 */
	public boolean isEnvelopeCoveringRectangle() {
		GeometryWrapper sourceWrapper = sourceGeometryLiteral.asGeometryWrapper();
		Geometry sourceGeometry = sourceWrapper.getXYGeometry();
		return INDEX_CRS.equals(sourceWrapper.getSrsURI())
				&& fillsItsEnvelope(sourceGeometry, sourceGeometry::isValid);
	}

	/**
	 * Whether the geometry is a non-empty, valid polygon that covers every point of its envelope, whose area is
	 * {@link #hasNormalArea normal}. Only such a polygon proves that a geometry inside its envelope meets it: JTS
	 * may find that a self-overlapping ring fills its envelope while Jena finds a point inside that envelope
	 * disjoint from it.
	 *
	 * @param valid whether the geometry is valid, asked only of a polygon that passes the cheaper checks
	 */
	public static boolean fillsItsEnvelope(Geometry geometry, BooleanSupplier valid) {
		if (geometry.isEmpty() || !(geometry instanceof Polygon) || !hasNormalArea(geometry.getEnvelopeInternal())) {
			return false;
		}
		try {
			return valid.getAsBoolean()
					&& RelateNG.relate(geometry, geometry.getEnvelope(), RelatePredicate.equalsTopo());
		} catch (RuntimeException e) {
			// The proof is optional. Topology JTS cannot decide leaves the pair to exact evaluation.
			return false;
		}
	}

	public boolean isSpatialCandidate() {
		return !indexEnvelope.isNull();
	}

	/**
	 * Whether the source is an area collection: a generic geometry collection, not a multi-geometry, that reports
	 * the topological dimension of an area. Exact evaluation relates it by the union of its members, and separated
	 * envelopes do not prove the result for a relation that applies to areas only. A collection of points or lines
	 * with an empty polygon reports area dimension without being an area, and JTS 1.20.0 may find no boundary for a
	 * collection of polygons whose rings all start inside their union.
	 */
	public boolean isAreaCollection() {
		return sourceTopologicalDimension == Dimension.A && JenaFunctionEvaluator.isGenericCollection(
				sourceGeometryLiteral.asGeometryWrapper().getXYGeometry());
	}

	/**
	 * Returns the XY envelope of the source in its own CRS, or the {@link #comparableEnvelope unbounded envelope}
	 * when it has a non-finite bound.
	 *
	 * <p>Jena evaluates a left operand on its own coordinates, so this envelope bounds the geometry of every exact
	 * evaluation in which the source is the left operand.
	 */
	public Envelope sourceEnvelope() {
		return comparableEnvelope(sourceGeometryLiteral.asGeometryWrapper().getXYGeometry().getEnvelopeInternal());
	}

	/**
	 * Returns the envelope to compare for a geometry: a copy of its own when every bound is finite, and otherwise the
	 * unbounded envelope. A NaN or infinite coordinate leaves an envelope that shows nothing about where the geometry
	 * lies; the unbounded envelope meets every envelope, lies inside none and equals only another unbounded one, so
	 * candidate lookup never decides a pair from it.
	 */
	public static Envelope comparableEnvelope(Envelope envelope) {
		if (envelope.isNull() || Double.isFinite(envelope.getMinX()) && Double.isFinite(envelope.getMaxX())
				&& Double.isFinite(envelope.getMinY()) && Double.isFinite(envelope.getMaxY())) {
			return new Envelope(envelope);
		}
		return unboundedEnvelope();
	}

	/** The envelope that {@link #comparableEnvelope} gives a geometry with a non-finite bound. */
	public static Envelope unboundedEnvelope() {
		return new Envelope(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
	}

	/**
	 * The CRS84 index envelope of a source whose candidate bounds are {@link CandidateBoundsKind#WORLD_FALLBACK}. It
	 * meets every CRS84 envelope and says nothing of where the source lies: Jena may place the source outside it.
	 */
	public static Envelope worldFallbackEnvelope() {
		return ConservativeCrs84EnvelopeProjector.worldCrs84Envelope();
	}

	/** Whether the envelope is the {@link #unboundedEnvelope}. */
	public static boolean isUnbounded(Envelope envelope) {
		return unboundedEnvelope().equals(envelope);
	}

	/**
	 * Whether the envelope's area is a normal double: finite, and not so small that it underflows. JTS decides
	 * topology from products of coordinate differences, which the area of an envelope bounds for the geometries
	 * inside it. Once those products underflow, as they do for coordinates near 10⁻¹⁶², it can find a rectangle
	 * within a polygon that reaches outside it, or no point of a line in a rectangle that holds one, and they can
	 * overflow likewise. A rectangle or envelope is used as a proof only when its area is normal, and a geometry is
	 * split into such rectangles only when its own envelope's area is.
	 */
	public static boolean hasNormalArea(Envelope envelope) {
		double area = envelope.getWidth() * envelope.getHeight();
		return Double.isFinite(area) && area >= Double.MIN_NORMAL;
	}

	/**
	 * Returns this bound source's envelope in the CRS where Jena evaluates it against sources of the given CRS.
	 *
	 * <p>Empty when that envelope is not known without transforming each source: the bound is the left operand
	 * and the sources are in another CRS, so Jena transforms every source into the bound's CRS.
	 *
	 * @param sourceIsLeftOperand whether each source is the subject of the relation and this bound its object
	 */
	public Optional<BoundEvaluationEnvelope> evaluationEnvelope(String sourceCrsUri, boolean sourceIsLeftOperand) {
		GeometryWrapper bound = sourceGeometryLiteral.asGeometryWrapper();
		if (sourceCrsUri.equals(bound.getSrsURI())) {
			return Optional.of(new BoundEvaluationEnvelope(sourceGeometryLiteral, sourceCrsUri, bound));
		}
		if (!sourceIsLeftOperand) {
			return Optional.empty();
		}
		try {
			JenaCalculationPrecision.configure();
			return Optional.of(new BoundEvaluationEnvelope(sourceGeometryLiteral, sourceCrsUri,
					sourceGeometryLiteral.asRightOperandIn(sourceCrsUri)));
		} catch (Exception e) {
			// Jena fails the same way in every exact evaluation against a source of this CRS.
			LOGGER.debug("Relating no source of {} to a bound of {} that Jena cannot place there", sourceCrsUri,
					bound.getSrsURI(), e);
			return Optional.of(BoundEvaluationEnvelope.UNRELATED);
		}
	}
}
