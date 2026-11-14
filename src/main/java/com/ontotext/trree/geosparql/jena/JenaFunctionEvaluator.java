package com.ontotext.trree.geosparql.jena;

import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.jena.geosparql.implementation.DimensionInfo;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.GeometryWrapperFactory;
import org.apache.jena.geosparql.implementation.UnitsOfMeasure;
import org.apache.jena.geosparql.implementation.intersection_patterns.EgenhoferIntersectionPattern;
import org.apache.jena.geosparql.implementation.intersection_patterns.RCC8IntersectionPattern;
import org.apache.jena.geosparql.implementation.intersection_patterns.SimpleFeaturesIntersectionPattern;
import org.apache.jena.geosparql.implementation.vocabulary.SRS_URI;
import org.apache.jena.geosparql.implementation.vocabulary.Unit_URI;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.locationtech.jts.algorithm.match.HausdorffSimilarityMeasure;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.relateng.RelateNG;
import org.locationtech.jts.operation.relateng.RelatePredicate;
import org.locationtech.jts.io.WKTWriter;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Direct Jena-backed evaluator for RDF4J GeoSPARQL filter functions.
 */
public final class JenaFunctionEvaluator {
	private static final Map<String, String> EXTENSION_UNIT_TO_JENA = new HashMap<>();
	private static final double LIGHT_YEAR_IN_METRES = 9.460528405E15D;
	private static final HausdorffSimilarityMeasure HAUSDORFF_SIMILARITY = new HausdorffSimilarityMeasure();

	static {
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_CENTIMETRE.stringValue(), Unit_URI.CENTIMETRE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_KILOMETRE.stringValue(), Unit_URI.KILOMETRE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_MILLIMETRE.stringValue(), Unit_URI.MILLIMETRE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_METRE.stringValue(), Unit_URI.METRE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_FOOT.stringValue(), Unit_URI.FOOT_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_US_SURVEY_FOOT.stringValue(), Unit_URI.US_SURVEY_FOOT_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_INCH.stringValue(), Unit_URI.INCH_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_MILE.stringValue(), Unit_URI.MILE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_NAUTICAL_MILE.stringValue(), Unit_URI.NAUTICAL_MILE_URL);
		EXTENSION_UNIT_TO_JENA.put(GeoSparqlUnits.URI_YARD.stringValue(), Unit_URI.YARD_URL);
	}

	private JenaFunctionEvaluator() {
	}

	public static Value evaluate(ValueFactory valueFactory, String functionUri, Value... args)
			throws ValueExprEvaluationException {
		JenaGeometryAdapter.initialize();
		try {
			if (isBinaryTopological(functionUri)) {
				requireArgs(functionUri, args, 2);
				return valueFactory.createLiteral(
						evaluateTopological(functionUri, sourceLiteral(args[0]), sourceLiteral(args[1])));
			}
			if (GeoConstants.GEOF_RELATE.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 3);
				return valueFactory.createLiteral(evaluateRelate(sourceLiteral(args[0]), sourceLiteral(args[1]),
						args[2].stringValue()));
			}
			if (GeoConstants.GEOF_DISTANCE.stringValue().equals(functionUri)
					|| GeoConstants.EXT_HAUSDORFF_DISTANCE.stringValue().equals(functionUri)) {
				return distance(valueFactory, functionUri, args);
			}
			if (GeoConstants.GEOF_BUFFER.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 2, 3);
				SourceGeometryLiteral source = sourceLiteral(args[0]);
				GeometryWrapper wrapper = source.asGeometryWrapper();
				double radius = doubleArg(args[1]);
				GeometryWrapper buffered = args.length == 3
						? buffer(wrapper, radius, unitUri(args[2]))
						: wrapper.buffer(radius, wrapper.getUnitsOfMeasure().getUnitURI());
				return geometryLiteral(valueFactory, buffered, source.datatype());
			}
			if (GeoConstants.GEOF_CONVEX_HULL.stringValue().equals(functionUri)) {
				return unaryGeometry(valueFactory, functionUri, args, GeometryWrapper::convexHull);
			}
			if (GeoConstants.GEOF_ENVELOPE.stringValue().equals(functionUri)) {
				return unaryGeometry(valueFactory, functionUri, args, GeometryWrapper::envelope);
			}
			if (GeoConstants.GEOF_BOUNDARY.stringValue().equals(functionUri)) {
				return unaryGeometry(valueFactory, functionUri, args, GeometryWrapper::boundary);
			}
			if (GeoConstants.GEOF_INTERSECTION.stringValue().equals(functionUri)
					|| GeoConstants.GEOF_UNION.stringValue().equals(functionUri)
					|| GeoConstants.GEOF_DIFFERENCE.stringValue().equals(functionUri)
					|| GeoConstants.GEOF_SYM_DIFFERENCE.stringValue().equals(functionUri)) {
				return binaryGeometry(valueFactory, functionUri, args);
			}
			if (GeoConstants.GEOF_GETSRID.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).getSRID(), XSD.ANYURI);
			}
			if (GeoConstants.GEO_DIMENSION.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).getTopologicalDimension());
			}
			if (GeoConstants.GEO_COORDINATE_DIMENSION.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).getCoordinateDimension());
			}
			if (GeoConstants.GEO_SPATIAL_DIMENSION.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).getSpatialDimension());
			}
			if (GeoConstants.GEO_IS_EMPTY.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).isEmpty());
			}
			if (GeoConstants.GEO_IS_SIMPLE.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).isSimple());
			}
			if (GeoConstants.EXT_IS_VALID.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(isValid(args[0]));
			}
			if (GeoConstants.EXT_AREA.stringValue().equals(functionUri)) {
				requireArgs(functionUri, args, 1);
				return valueFactory.createLiteral(geometry(args[0]).getXYGeometry().getArea());
			}
			if (GeoConstants.EXT_CLOSEST_POINT.stringValue().equals(functionUri)
					|| GeoConstants.EXT_SHORTEST_LINE.stringValue().equals(functionUri)) {
				return nearestGeometry(valueFactory, functionUri, args);
			}
			if (GeoConstants.EXT_CONTAINS_PROPERLY.stringValue().equals(functionUri)
					|| GeoConstants.EXT_COVERED_BY.stringValue().equals(functionUri)
					|| GeoConstants.EXT_COVERS.stringValue().equals(functionUri)) {
				return valueFactory.createLiteral(extensionBoolean(functionUri, args));
			}
			if (GeoConstants.EXT_SIMPLIFY.stringValue().equals(functionUri)
					|| GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY.stringValue().equals(functionUri)) {
				return simplify(valueFactory, functionUri, args);
			}
		} catch (ValueExprEvaluationException e) {
			throw e;
		} catch (JenaGeoSparqlException e) {
			throw new ValueExprEvaluationException(e);
		} catch (Exception e) {
			throw new ValueExprEvaluationException(e);
		}
		throw new ValueExprEvaluationException("Unsupported GeoSPARQL function: " + functionUri);
	}

	private static SourceGeometryLiteral sourceLiteral(Value value) {
		return JenaGeometryAdapter.toSourceGeometryLiteral(value, true);
	}

	private static GeometryWrapper geometry(Value value) {
		return sourceLiteral(value).asGeometryWrapper();
	}

	private static boolean isBinaryTopological(String functionUri) {
		return functionUri.startsWith(GeoConstants.NS_GEOF + "sf")
				|| functionUri.startsWith(GeoConstants.NS_GEOF + "eh")
				|| functionUri.startsWith(GeoConstants.NS_GEOF + "rcc8");
	}

	public static boolean evaluateTopological(String functionUri, SourceGeometryLiteral leftSource,
											  SourceGeometryLiteral rightSource)
			throws Exception {
		JenaCalculationPrecision.configure();
		GeometryWrapper left = leftSource.asGeometryWrapper();
		GeometryWrapper right = rightSource.asGeometryWrapper();
		boolean hasGenericCollection = isGenericCollection(left) || isGenericCollection(right);
		if (!permittedTopology(functionUri, left.getDimensionInfo(), right.getDimensionInfo())) {
			return false;
		}
		if (hasGenericCollection) {
			return evaluateCollectionTopological(functionUri, left, right);
		}
		if (GeoConstants.GEOF_SF_EQUALS.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_EQUALS.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_RCC8_EQ.stringValue().equals(functionUri)) {
			return left.relate(right, GeoConstants.GEOF_RCC8_EQ.stringValue().equals(functionUri)
					? RCC8IntersectionPattern.EQUALS
					: SimpleFeaturesIntersectionPattern.EQUALS);
		}
		if (GeoConstants.GEOF_SF_DISJOINT.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_DISJOINT.stringValue().equals(functionUri)) {
			return left.disjoint(right);
		}
		if (GeoConstants.GEOF_RCC8_DC.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.DISCONNECTED);
		}
		if (GeoConstants.GEOF_SF_INTERSECTS.stringValue().equals(functionUri)) {
			return left.intersects(right);
		}
		if (GeoConstants.GEOF_SF_TOUCHES.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_MEET.stringValue().equals(functionUri)) {
			return left.touches(right);
		}
		if (GeoConstants.GEOF_RCC8_EC.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.EXTERNALLY_CONNECTED);
		}
		if (GeoConstants.GEOF_SF_WITHIN.stringValue().equals(functionUri)) {
			return left.within(right);
		}
		if (GeoConstants.GEOF_SF_CONTAINS.stringValue().equals(functionUri)) {
			return left.contains(right);
		}
		if (GeoConstants.GEOF_SF_OVERLAPS.stringValue().equals(functionUri)) {
			return left.overlaps(right);
		}
		if (GeoConstants.GEOF_EH_OVERLAP.stringValue().equals(functionUri)) {
			return left.relate(right, EgenhoferIntersectionPattern.OVERLAP);
		}
		if (GeoConstants.GEOF_RCC8_PO.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.PARTIALLY_OVERLAPPING);
		}
		if (GeoConstants.GEOF_SF_CROSSES.stringValue().equals(functionUri)) {
			return left.crosses(right);
		}
		if (GeoConstants.GEOF_EH_COVERS.stringValue().equals(functionUri)) {
			return left.relate(right, EgenhoferIntersectionPattern.COVERS);
		}
		if (GeoConstants.GEOF_EH_COVERED_BY.stringValue().equals(functionUri)) {
			return left.relate(right, EgenhoferIntersectionPattern.COVERED_BY);
		}
		if (GeoConstants.GEOF_EH_INSIDE.stringValue().equals(functionUri)) {
			return left.relate(right, EgenhoferIntersectionPattern.INSIDE);
		}
		if (GeoConstants.GEOF_EH_CONTAINS.stringValue().equals(functionUri)) {
			return left.relate(right, EgenhoferIntersectionPattern.CONTAINS);
		}
		if (GeoConstants.GEOF_RCC8_TPPI.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.TANGENTIAL_PROPER_PART_INVERSE);
		}
		if (GeoConstants.GEOF_RCC8_TPP.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.TANGENTIAL_PROPER_PART);
		}
		if (GeoConstants.GEOF_RCC8_NTPP.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.NON_TANGENTIAL_PROPER_PART);
		}
		if (GeoConstants.GEOF_RCC8_NTPPI.stringValue().equals(functionUri)) {
			return left.relate(right, RCC8IntersectionPattern.NON_TANGENTIAL_PROPER_PART_INVERSE);
		}
		throw new ValueExprEvaluationException("Unsupported topological function: " + functionUri);
	}

	private static boolean evaluateRelate(SourceGeometryLiteral leftSource, SourceGeometryLiteral rightSource,
			String intersectionPattern) throws Exception {
		GeometryWrapper left = leftSource.asGeometryWrapper();
		GeometryWrapper right = rightSource.asGeometryWrapper();
		if (isGenericCollection(left) || isGenericCollection(right)) {
			GeometryWrapper transformedRight = left.checkTransformSRS(right);
			return RelateNG.relate(left.getXYGeometry(), transformedRight.getXYGeometry(), intersectionPattern);
		}
		return left.relate(right, intersectionPattern);
	}

	private static boolean evaluateCollectionTopological(String functionUri, GeometryWrapper left,
			GeometryWrapper right) throws Exception {
		GeometryWrapper transformedRight = left.checkTransformSRS(right);
		Geometry leftGeometry = left.getXYGeometry();
		Geometry rightGeometry = transformedRight.getXYGeometry();
		if (GeoConstants.GEOF_SF_EQUALS.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_EQUALS.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, SimpleFeaturesIntersectionPattern.EQUALS);
		}
		if (GeoConstants.GEOF_RCC8_EQ.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RCC8IntersectionPattern.EQUALS);
		}
		if (GeoConstants.GEOF_SF_DISJOINT.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_DISJOINT.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.disjoint());
		}
		if (GeoConstants.GEOF_RCC8_DC.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RCC8IntersectionPattern.DISCONNECTED);
		}
		if (GeoConstants.GEOF_SF_INTERSECTS.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.intersects());
		}
		if (GeoConstants.GEOF_SF_TOUCHES.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_MEET.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.touches());
		}
		if (GeoConstants.GEOF_RCC8_EC.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RCC8IntersectionPattern.EXTERNALLY_CONNECTED);
		}
		if (GeoConstants.GEOF_SF_WITHIN.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.within());
		}
		if (GeoConstants.GEOF_SF_CONTAINS.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.contains());
		}
		if (GeoConstants.GEOF_SF_OVERLAPS.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.overlaps());
		}
		if (GeoConstants.GEOF_SF_CROSSES.stringValue().equals(functionUri)) {
			return RelateNG.relate(leftGeometry, rightGeometry, RelatePredicate.crosses());
		}
		String pattern = collectionIntersectionPattern(functionUri);
		if (pattern != null) {
			return RelateNG.relate(leftGeometry, rightGeometry, pattern);
		}
		throw new ValueExprEvaluationException("Unsupported topological function: " + functionUri);
	}

	private static String collectionIntersectionPattern(String functionUri) {
		if (GeoConstants.GEOF_EH_OVERLAP.stringValue().equals(functionUri)) {
			return EgenhoferIntersectionPattern.OVERLAP;
		}
		if (GeoConstants.GEOF_EH_COVERS.stringValue().equals(functionUri)) {
			return EgenhoferIntersectionPattern.COVERS;
		}
		if (GeoConstants.GEOF_EH_COVERED_BY.stringValue().equals(functionUri)) {
			return EgenhoferIntersectionPattern.COVERED_BY;
		}
		if (GeoConstants.GEOF_EH_INSIDE.stringValue().equals(functionUri)) {
			return EgenhoferIntersectionPattern.INSIDE;
		}
		if (GeoConstants.GEOF_EH_CONTAINS.stringValue().equals(functionUri)) {
			return EgenhoferIntersectionPattern.CONTAINS;
		}
		if (GeoConstants.GEOF_RCC8_PO.stringValue().equals(functionUri)) {
			return RCC8IntersectionPattern.PARTIALLY_OVERLAPPING;
		}
		if (GeoConstants.GEOF_RCC8_TPPI.stringValue().equals(functionUri)) {
			return RCC8IntersectionPattern.TANGENTIAL_PROPER_PART_INVERSE;
		}
		if (GeoConstants.GEOF_RCC8_TPP.stringValue().equals(functionUri)) {
			return RCC8IntersectionPattern.TANGENTIAL_PROPER_PART;
		}
		if (GeoConstants.GEOF_RCC8_NTPP.stringValue().equals(functionUri)) {
			return RCC8IntersectionPattern.NON_TANGENTIAL_PROPER_PART;
		}
		if (GeoConstants.GEOF_RCC8_NTPPI.stringValue().equals(functionUri)) {
			return RCC8IntersectionPattern.NON_TANGENTIAL_PROPER_PART_INVERSE;
		}
		return null;
	}

	private static boolean isGenericCollection(GeometryWrapper wrapper) {
		return wrapper.getXYGeometry().getClass().equals(GeometryCollection.class);
	}

	private static boolean permittedTopology(String functionUri, DimensionInfo left, DimensionInfo right) {
		if (GeoConstants.GEOF_SF_TOUCHES.stringValue().equals(functionUri)
				|| GeoConstants.GEOF_EH_MEET.stringValue().equals(functionUri)) {
			return !(left.isPoint() && right.isPoint());
		}
		if (GeoConstants.GEOF_SF_OVERLAPS.stringValue().equals(functionUri)) {
			return (left.isArea() && right.isArea())
					|| (left.isPoint() && right.isPoint())
					|| (left.isLine() && right.isLine());
		}
		if (GeoConstants.GEOF_SF_CROSSES.stringValue().equals(functionUri)) {
			return (left.isPoint() && right.isLine())
					|| (left.isPoint() && right.isArea())
					|| (left.isLine() && right.isArea())
					|| (left.isLine() && right.isLine());
		}
		if (GeoConstants.GEOF_EH_COVERS.stringValue().equals(functionUri)) {
			return (left.isArea() && right.isArea())
					|| (left.isArea() && right.isLine())
					|| (left.isLine() && right.isLine());
		}
		if (GeoConstants.GEOF_EH_COVERED_BY.stringValue().equals(functionUri)) {
			return (left.isArea() && right.isArea())
					|| (left.isLine() && right.isArea())
					|| (left.isLine() && right.isLine());
		}
		if (functionUri.startsWith(GeoConstants.NS_GEOF + "rcc8")) {
			return left.isArea() && right.isArea();
		}
		return true;
	}

	private static Literal distance(ValueFactory valueFactory, String functionUri, Value... args)
			throws Exception {
		requireArgs(functionUri, args, 2, 3);
		GeometryWrapper left = geometry(args[0]);
		GeometryWrapper right = geometry(args[1]);
		if (GeoConstants.EXT_HAUSDORFF_DISTANCE.stringValue().equals(functionUri)) {
			GeometryWrapper transformedRight = left.checkTransformSRS(right);
			return valueFactory.createLiteral(HAUSDORFF_SIMILARITY.measure(left.getXYGeometry(),
					transformedRight.getXYGeometry()));
		}
		if (args.length == 2) {
			return valueFactory.createLiteral(left.getXYGeometry().distance(left.checkTransformSRS(right).getXYGeometry()));
		}
		if (GeoSparqlUnits.URI_LIGHT_YEAR.equals(args[2])) {
			return valueFactory.createLiteral(left.distance(right, Unit_URI.METRE_URL) / LIGHT_YEAR_IN_METRES);
		}
		if (isAngularDistanceUnit(args[2])) {
			double radians = left.distance(right, Unit_URI.METRE_URL) / UnitsOfMeasure.EARTH_MEAN_RADIUS;
			return valueFactory.createLiteral(GeoSparqlUnits.URI_DEGREE.equals(args[2])
					? Math.toDegrees(radians)
					: radians);
		}
		if (isRawCoordinateDistanceUnit(args[2])) {
			return valueFactory.createLiteral(left.getXYGeometry().distance(left.checkTransformSRS(right).getXYGeometry()));
		}
		return valueFactory.createLiteral(left.distance(right, unitUri(args[2])));
	}

	private static GeometryWrapper buffer(GeometryWrapper wrapper, double radius, String unitUri) throws Exception {
		return wrapper.buffer(radius, radius == 0.0 ? wrapper.getUnitsOfMeasure().getUnitURI() : unitUri);
	}

	private static boolean isAngularDistanceUnit(Value unit) {
		return GeoSparqlUnits.URI_DEGREE.equals(unit) || GeoSparqlUnits.URI_RADIAN.equals(unit);
	}

	private static boolean isRawCoordinateDistanceUnit(Value unit) {
		return GeoSparqlUnits.URI_GRID_SPACING.equals(unit) || GeoSparqlUnits.URI_UNITY.equals(unit);
	}

	private interface UnaryGeometryOperation {
		GeometryWrapper apply(GeometryWrapper geometry) throws Exception;
	}

	private static Literal unaryGeometry(ValueFactory valueFactory, String functionUri, Value[] args,
										 UnaryGeometryOperation operation) throws Exception {
		requireArgs(functionUri, args, 1);
		SourceGeometryLiteral source = sourceLiteral(args[0]);
		return geometryLiteral(valueFactory, operation.apply(source.asGeometryWrapper()), source.datatype());
	}

	private static Literal binaryGeometry(ValueFactory valueFactory, String functionUri, Value... args)
			throws Exception {
		requireArgs(functionUri, args, 2);
		SourceGeometryLiteral source = sourceLiteral(args[0]);
		GeometryWrapper left = source.asGeometryWrapper();
		GeometryWrapper right = geometry(args[1]);
		GeometryWrapper result;
		if (GeoConstants.GEOF_INTERSECTION.stringValue().equals(functionUri)) {
			result = left.intersection(right);
		} else if (GeoConstants.GEOF_UNION.stringValue().equals(functionUri)) {
			result = left.union(right);
		} else if (GeoConstants.GEOF_DIFFERENCE.stringValue().equals(functionUri)) {
			result = left.difference(right);
		} else {
			result = left.symDifference(right);
		}
		return geometryLiteral(valueFactory, result, source.datatype());
	}

	private static Literal nearestGeometry(ValueFactory valueFactory, String functionUri, Value... args)
			throws Exception {
		requireArgs(functionUri, args, 2);
		SourceGeometryLiteral source = sourceLiteral(args[0]);
		GeometryWrapper left = source.asGeometryWrapper();
		GeometryWrapper right = left.checkTransformSRS(geometry(args[1]));
		Coordinate[] points = DistanceOp.nearestPoints(left.getXYGeometry(), right.getXYGeometry());
		GeometryFactory factory = left.getXYGeometry().getFactory();
		Geometry result;
		if (GeoConstants.EXT_CLOSEST_POINT.stringValue().equals(functionUri)) {
			result = factory.createPoint(points[0]);
		} else {
			result = factory.createLineString(new Coordinate[]{points[0], points[1]});
		}
		return geometryLiteral(valueFactory,
				GeometryWrapperFactory.createGeometry(result, left.getSrsURI(), left.getGeometryDatatypeURI()),
				source.datatype());
	}

	private static boolean extensionBoolean(String functionUri, Value... args) throws Exception {
		requireArgs(functionUri, args, 2);
		GeometryWrapper left = geometry(args[0]);
		GeometryWrapper right = left.checkTransformSRS(geometry(args[1]));
		if (GeoConstants.EXT_CONTAINS_PROPERLY.stringValue().equals(functionUri)) {
			return PreparedGeometryFactory.prepare(left.getXYGeometry()).containsProperly(right.getXYGeometry());
		}
		if (GeoConstants.EXT_COVERED_BY.stringValue().equals(functionUri)) {
			return left.getXYGeometry().coveredBy(right.getXYGeometry());
		}
		return left.getXYGeometry().covers(right.getXYGeometry());
	}

	private static Literal simplify(ValueFactory valueFactory, String functionUri, Value... args) throws Exception {
		requireArgs(functionUri, args, 2);
		SourceGeometryLiteral source = sourceLiteral(args[0]);
		GeometryWrapper wrapper = source.asGeometryWrapper();
		double tolerance = doubleArg(args[1]);
		Geometry simplified = GeoConstants.EXT_SIMPLIFY_PRESERVE_TOPOLOGY.stringValue().equals(functionUri)
				? TopologyPreservingSimplifier.simplify(wrapper.getXYGeometry(), tolerance)
				: DouglasPeuckerSimplifier.simplify(wrapper.getXYGeometry(), tolerance);
		return geometryLiteral(valueFactory,
				GeometryWrapperFactory.createGeometry(simplified, wrapper.getSrsURI(), wrapper.getGeometryDatatypeURI()),
				source.datatype());
	}

	private static Literal geometryLiteral(ValueFactory valueFactory, GeometryWrapper wrapper, IRI datatype) {
		if (!GeoConstants.GEO_GML_LITERAL.equals(datatype)) {
			String wkt = new WKTWriter().write(wrapper.getParsingGeometry());
			if (!SRS_URI.DEFAULT_WKT_CRS84.equals(wrapper.getSrsURI())) {
				wkt = "<" + wrapper.getSrsURI() + "> " + wkt;
			}
			return valueFactory.createLiteral(wkt, datatype);
		}
		org.apache.jena.rdf.model.Literal literal = wrapper.asLiteral(wrapper.getGeometryDatatypeURI());
		return valueFactory.createLiteral(literal.getLexicalForm(), datatype);
	}

	private static boolean isValid(Value value) throws ValueExprEvaluationException {
		try {
			return geometry(value).isValid();
		} catch (JenaGeoSparqlException e) {
			if (e.isUnsupportedCrs()) {
				throw new ValueExprEvaluationException(e);
			}
			return false;
		}
	}

	private static double doubleArg(Value value) throws ValueExprEvaluationException {
		if (!(value instanceof Literal)) {
			throw new ValueExprEvaluationException("Expected numeric literal, found: " + value);
		}
		try {
			return ((Literal) value).doubleValue();
		} catch (NumberFormatException e) {
			throw new ValueExprEvaluationException(e);
		}
	}

	private static String unitUri(Value value) throws ValueExprEvaluationException {
		if (!(value instanceof IRI)) {
			throw new ValueExprEvaluationException("Expected unit IRI, found: " + value);
		}
		String uri = value.stringValue();
		return EXTENSION_UNIT_TO_JENA.getOrDefault(uri, uri);
	}

	private static void requireArgs(String functionUri, Value[] args, int... allowed) throws ValueExprEvaluationException {
		for (int count : allowed) {
			if (args.length == count) {
				return;
			}
		}
		throw new ValueExprEvaluationException(functionUri + " function received unexpected argument count: " + args.length);
	}
}
