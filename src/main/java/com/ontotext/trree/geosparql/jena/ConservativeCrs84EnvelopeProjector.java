package com.ontotext.trree.geosparql.jena;

import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.apache.sis.geometry.Envelopes;
import org.apache.sis.geometry.GeneralEnvelope;
import org.apache.sis.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Rectangle;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.cs.CoordinateSystem;
import org.opengis.referencing.operation.CoordinateOperation;
import org.opengis.referencing.operation.TransformException;
import org.opengis.util.FactoryException;

/**
 * Derives a CRS84 index envelope from a source geometry literal.
 *
 * <p>Native CRS84 sources use their source envelope. Empty sources yield a null envelope. Other non-empty sources
 * transform the source-CRS bounding box with Apache SIS {@code Envelopes.transform(CoordinateOperation, Envelope)}.
 * Sources with a three-dimensional CRS combine a direct source-to-CRS84 transformation that retains their vertical
 * range with a height-independent transformation of their two-dimensional horizontal envelope. The direct component
 * covers mixed-CRS exact evaluation, while the horizontal component preserves the height-independent topology of
 * same-CRS JTS evaluation. Other sources use an operation on their two-dimensional horizontal CRS. The result is used
 * only for Lucene candidate lookup. Exact evaluation uses the source geometry literal and its native CRS.
 *
 * <p>The plugin uses the SIS
 * <a href="https://sis.apache.org/apidocs/org.apache.sis.referencing/org/apache/sis/geometry/Envelopes.html">
 * {@code CoordinateOperation} envelope transformation</a>, which does substantially more than transform the four
 * corners. SIS samples intermediate points, uses transform derivatives (Jacobians) where available, estimates
 * nonlinear edge extrema by cubic interpolation, and transforms the source positions corresponding to the predicted
 * extrema. This overload also uses {@code CoordinateOperation} metadata to handle poles and longitude wraparound.
 * SIS recommends this overload for envelope transformation and states that most SIS transforms support the
 * derivatives used by the algorithm. The {@code MathTransform} overload is not used because it lacks the additional
 * pole and wraparound handling.
 *
 * <p>SIS nevertheless describes the result as an approximation that "should not be smaller in most cases". The
 * <a href="https://sis.apache.org/book/en/developer-guide.html">
 * transformed-envelope extremum calculation</a> uses a cubic approximation, not interval arithmetic or another
 * formal enclosure proof. The plugin relies on Apache SIS envelope transformation as a conservative engineering
 * assumption: SIS intends and tests the operation to preserve containment, but does not provide a universal
 * mathematical enclosure guarantee. Finite, representable SIS envelopes are therefore treated as conservative
 * candidate bounds without claiming a mathematically proven property of every operation.
 *
 * <p>This assumption is accepted because conservative containment is the intended SIS behaviour; SIS tests include
 * containment-oriented checks such as inverse-transformed envelopes containing their originals; and SIS regression
 * coverage treats projection-domain and wraparound behaviour as envelope-transformation correctness concerns. No
 * finite, in-domain under-bounding case is identified by the plugin's SIS 1.6 test corpus or its review of the SIS API
 * and implementation, but absence of an identified counterexample does not prove safety. The plugin independently
 * exercises the assumption with adversarial and randomised transformed-envelope coverage tests,
 * exact-versus-index-backed differential tests, and prefix-tree and precision tests.
 *
 * <p>Jena separately rounds coordinates after transforming the right exact-evaluation operand into the left
 * operand's CRS. The plugin pins that cleanup precision in {@link JenaCalculationPrecision}. Transformed CRS84
 * candidate bounds are widened by the maximum cleanup displacement in CRS84 units so a right operand transformed
	 * into CRS84 remains covered. This widening is distinct from the SIS envelope approximation. Non-disjoint candidate
	 * lookup treats independently derived CRS84 envelopes as conservative in both binding directions. When disjoint exact
	 * evaluation would round in another CRS, relation traversal retains the pair for exact evaluation instead of using
	 * the CRS84 envelopes as a definite classification. When the exact target is CRS84 and the right operand has a
	 * three-dimensional CRS, the direct component selects the same source-to-CRS84 operation as exact evaluation. The
	 * horizontal component ensures that height does not separate same-CRS sources whose JTS XY geometries intersect.
 *
 * <p>Antimeridian wraparound may broaden the candidate envelope, including to full longitude while keeping local
 * latitude, when that is what SIS reports through {@code getMinimum}/{@code getMaximum}. The world CRS84 envelope is
 * used only when the result still cannot be stored as one Lucene geographic rectangle: inverted lower/upper ordering,
 * non-finite ordinates, bounds outside the geographic world, missing two-dimensional horizontal CRS, or transform
 * failure. Cleanup-displacement and unit-in-the-last-place widening do not close a geographically large miss.
 *
 * <p>If a future SIS implementation, EPSG dataset, datum grid, or selected coordinate operation violates the
 * assumption, candidate pruning could produce a false negative. The transformed-envelope coverage and differential
 * tests, together with CRS-environment compatibility and fingerprint checks, are therefore correctness controls, not
 * merely accuracy or performance tests. World fallback remains the representation path for transform failures and
 * results that cannot safely be stored as one Lucene geographic rectangle; it is not the policy for every non-CRS84
 * geometry.
 */
final class ConservativeCrs84EnvelopeProjector {
	static final String FALLBACK_MISSING_HORIZONTAL_CRS = "missing-horizontal-crs";
	static final String FALLBACK_TRANSFORM_FAILURE = "transform-failure";
	static final String FALLBACK_UNREPRESENTABLE_RECTANGLE = "unrepresentable-rectangle";

	Envelope project(SourceGeometryLiteral source) {
		return projectBounds(source).envelope();
	}

	ProjectedCandidateBounds projectBounds(SourceGeometryLiteral source) {
		JenaCalculationPrecision.configure();
		GeometryWrapper sourceWrapper = source.asGeometryWrapper();
		if (sourceWrapper.isEmpty()) {
			return ProjectedCandidateBounds.empty();
		}
		if (IndexGeometry.INDEX_CRS.equals(sourceWrapper.getSrsURI())) {
			return ProjectedCandidateBounds.nativeCrs84(
					new Envelope(sourceWrapper.getXYGeometry().getEnvelopeInternal()));
		}
		try {
			return transformSourceBounds(sourceWrapper);
		} catch (FactoryException | TransformException | RuntimeException ignored) {
			return ProjectedCandidateBounds.worldFallback(FALLBACK_TRANSFORM_FAILURE);
		}
	}

	private static ProjectedCandidateBounds transformSourceBounds(GeometryWrapper sourceWrapper)
			throws FactoryException, TransformException {
		CoordinateReferenceSystem sourceCrs = sourceWrapper.getCRS();
		CoordinateReferenceSystem horizontal;
		try {
			horizontal = horizontalCrs(sourceCrs);
		} catch (TransformException e) {
			return ProjectedCandidateBounds.worldFallback(FALLBACK_MISSING_HORIZONTAL_CRS);
		}
		CoordinateSystem sourceCoordinateSystem = sourceCrs.getCoordinateSystem();
		if (sourceCoordinateSystem != null && sourceCoordinateSystem.getDimension() == 3) {
			return transformThreeDimensionalSourceBounds(sourceWrapper, sourceCrs, horizontal);
		}
		return transformHorizontalSourceBounds(sourceWrapper, horizontal);
	}

	private static ProjectedCandidateBounds transformThreeDimensionalSourceBounds(
			GeometryWrapper sourceWrapper, CoordinateReferenceSystem sourceCrs,
			CoordinateReferenceSystem horizontal)
			throws FactoryException, TransformException {
		CoordinateReferenceSystem targetCrs = SRSRegistry.getCRS(IndexGeometry.INDEX_CRS);
		GeneralEnvelope sourceEnvelope = createThreeDimensionalSourceEnvelope(sourceWrapper, sourceCrs);
		CoordinateOperation operation = CRS.findOperation(sourceCrs, targetCrs, null);
		org.opengis.geometry.Envelope threeDimensionalBounds =
				Envelopes.transform(operation, sourceEnvelope);

		GeneralEnvelope horizontalSourceEnvelope = createSourceEnvelope(sourceWrapper, horizontal);
		CoordinateOperation horizontalOperation = CRS.findOperation(horizontal, targetCrs, null);
		org.opengis.geometry.Envelope horizontalBounds =
				Envelopes.transform(horizontalOperation, horizontalSourceEnvelope);

		GeneralEnvelope combinedBounds = new GeneralEnvelope(targetCrs);
		for (int dimension = 0; dimension < 2; dimension++) {
			combinedBounds.setRange(dimension,
					Math.min(threeDimensionalBounds.getMinimum(dimension),
							horizontalBounds.getMinimum(dimension)),
					Math.max(threeDimensionalBounds.getMaximum(dimension),
							horizontalBounds.getMaximum(dimension)));
		}
		return toLuceneGeoBounds(combinedBounds);
	}

	private static ProjectedCandidateBounds transformHorizontalSourceBounds(
			GeometryWrapper sourceWrapper, CoordinateReferenceSystem sourceCrs)
			throws FactoryException, TransformException {
		CoordinateReferenceSystem targetCrs = SRSRegistry.getCRS(IndexGeometry.INDEX_CRS);
		GeneralEnvelope sourceEnvelope = createSourceEnvelope(sourceWrapper, sourceCrs);
		CoordinateOperation operation = CRS.findOperation(sourceCrs, targetCrs, null);
		org.opengis.geometry.Envelope transformed = Envelopes.transform(operation, sourceEnvelope);
		return toLuceneGeoBounds(transformed);
	}

	private static CoordinateReferenceSystem horizontalCrs(CoordinateReferenceSystem sourceCrs)
			throws TransformException {
		CoordinateReferenceSystem horizontal = CRS.getHorizontalComponent(sourceCrs);
		if (horizontal == null) {
			throw new TransformException("Source CRS has no two-dimensional horizontal component.");
		}
		CoordinateSystem coordinateSystem = horizontal.getCoordinateSystem();
		if (coordinateSystem == null || coordinateSystem.getDimension() != 2) {
			throw new TransformException("Source CRS horizontal component is not two-dimensional.");
		}
		return horizontal;
	}

	private static GeneralEnvelope createSourceEnvelope(GeometryWrapper sourceWrapper,
			CoordinateReferenceSystem sourceCrs) {
		Envelope sourceBounds = sourceWrapper.getParsingGeometry().getEnvelopeInternal();
		GeneralEnvelope sourceEnvelope = new GeneralEnvelope(sourceCrs);
		sourceEnvelope.setRange(0, sourceBounds.getMinX(), sourceBounds.getMaxX());
		sourceEnvelope.setRange(1, sourceBounds.getMinY(), sourceBounds.getMaxY());
		return sourceEnvelope;
	}

	private static GeneralEnvelope createThreeDimensionalSourceEnvelope(GeometryWrapper sourceWrapper,
			CoordinateReferenceSystem sourceCrs) throws TransformException {
		GeneralEnvelope sourceEnvelope = createSourceEnvelope(sourceWrapper, sourceCrs);
		double minZ = Double.POSITIVE_INFINITY;
		double maxZ = Double.NEGATIVE_INFINITY;
		for (Coordinate coordinate : sourceWrapper.getParsingGeometry().getCoordinates()) {
			double z = coordinate.getZ();
			if (!Double.isFinite(z)) {
				throw new TransformException("Three-dimensional source CRS requires finite vertical ordinates.");
			}
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		sourceEnvelope.setRange(2, minZ, maxZ);
		return sourceEnvelope;
	}

	/**
	 * Adapts a transformed envelope to one Lucene geographic rectangle, or returns the world CRS84 envelope.
	 *
	 * <p>SIS wraparound is read through {@code getMinimum}/{@code getMaximum}. A geographic envelope that already
	 * spans full longitude with a local latitude range is stored as that rectangle. The world CRS84 envelope is used
	 * only when the result is still unusable as one ordered Lucene geographic rectangle.
	 *
	 * <p>This path trusts a finite, ordered, in-range SIS rectangle after widening for Jena's pinned CRS84 decimal
	 * cleanup and floating-point arithmetic. It does
	 * not prove that rectangle covers the complete transformed geometry. A representable rectangle, including one that
	 * spans full longitude, remains {@link CandidateBoundsKind#TRANSFORMED}. World fallback is a representation path
	 * for an unusable rectangle, not a substitute for an unidentified unsafe {@code CoordinateOperation} class.
	 */
	static Envelope toLuceneGeoEnvelope(org.opengis.geometry.Envelope transformed) {
		return toLuceneGeoBounds(transformed).envelope();
	}

	static ProjectedCandidateBounds toLuceneGeoBounds(org.opengis.geometry.Envelope transformed) {
		if (transformed == null || transformed.getDimension() < 2) {
			return ProjectedCandidateBounds.worldFallback(FALLBACK_UNREPRESENTABLE_RECTANGLE);
		}
		double minX = transformed.getMinimum(0);
		double maxX = transformed.getMaximum(0);
		double minY = transformed.getMinimum(1);
		double maxY = transformed.getMaximum(1);
		Rectangle world = SpatialContext.GEO.getWorldBounds();
		if (!Double.isFinite(minX) || !Double.isFinite(maxX)
				|| !Double.isFinite(minY) || !Double.isFinite(maxY)
				|| minX > maxX || minY > maxY
				|| minX < world.getMinX() || maxX > world.getMaxX()
				|| minY < world.getMinY() || maxY > world.getMaxY()) {
			return ProjectedCandidateBounds.worldFallback(FALLBACK_UNREPRESENTABLE_RECTANGLE);
		}
		double cleanupDisplacement = JenaCalculationPrecision.maximumCleanupDisplacement();
		minX = Math.max(world.getMinX(), Math.nextDown(minX - cleanupDisplacement));
		maxX = Math.min(world.getMaxX(), Math.nextUp(maxX + cleanupDisplacement));
		minY = Math.max(world.getMinY(), Math.nextDown(minY - cleanupDisplacement));
		maxY = Math.min(world.getMaxY(), Math.nextUp(maxY + cleanupDisplacement));
		if (minX > maxX || minY > maxY) {
			return ProjectedCandidateBounds.worldFallback(FALLBACK_UNREPRESENTABLE_RECTANGLE);
		}
		return ProjectedCandidateBounds.transformed(new Envelope(minX, maxX, minY, maxY));
	}

	static Envelope worldCrs84Envelope() {
		Rectangle world = SpatialContext.GEO.getWorldBounds();
		return new Envelope(world.getMinX(), world.getMaxX(), world.getMinY(), world.getMaxY());
	}
}
