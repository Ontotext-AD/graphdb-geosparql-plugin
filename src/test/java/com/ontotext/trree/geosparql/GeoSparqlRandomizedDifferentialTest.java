package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.apache.lucene.search.BooleanQuery;
import org.eclipse.rdf4j.model.Value;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.Assert.assertTrue;

/**
 * Compares indexed property relations with their registered filter functions over seeded random geometries that
 * stress candidate lookup: six CRSs, two- and three-dimensional, geographic and projected; points, lines, polygons,
 * multi-geometries and collections; heights and measures that are sometimes huge or infinite; collections with
 * members of different dimensions or with empty members; self-intersecting polygons; and, in projected CRSs,
 * points and polygons with an infinite coordinate. All lie within a few kilometres of each other near Brisbane, so many pairs meet, touch or
 * nearly do. Half of the geometries are indexed when the plugin is enabled and the rest by later updates. One seed
 * compares with a Lucene clause limit low enough that the bound cannot be placed in every CRS.
 *
 * <p>Besides random bounds, every seed compares a polygon covering the world, a polygon whose ring winds twice around
 * a square, a polygon about a millimetre across, and a feature that the repository does not hold.
 */
public class GeoSparqlRandomizedDifferentialTest extends AbstractGeoSparqlPluginTest {
	private static final String NAMESPACE = "http://example.com/randomized-differential/";
	private static final String PREFIXES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX ex: <" + NAMESPACE + ">\n";
	private static final List<String> RELATIONS = List.of("sfIntersects", "sfDisjoint", "ehDisjoint", "rcc8dc",
			"sfWithin", "sfContains", "sfTouches", "sfEquals");
	private static final int FEATURES = 40;
	private static final int BOUNDS = 8;
	private static final double LONGITUDE = 153.02;
	private static final double LATITUDE = -27.47;
	private static final double SPREAD = 0.02;
	private static final double MERCATOR_RADIUS = 6378137;
	private static final Map<String, String> FIXED_BOUNDS = Map.of(
			"world", "POLYGON((-180 -90,180 -90,180 90,-180 90,-180 -90))",
			"doubleWound", "POLYGON((153.01 -27.48,153.03 -27.48,153.03 -27.46,153.01 -27.46,153.01 -27.48,"
					+ "153.03 -27.48,153.03 -27.46,153.01 -27.46,153.01 -27.48))",
			"tiny", "POLYGON((153.02 -27.47,153.02000001 -27.47,153.02000001 -27.46999999,153.02 -27.46999999,"
					+ "153.02 -27.47))");
	private static final String ABSENT = "absent";

	private record Crs(String uri, boolean latitudeFirst, boolean height, boolean projected) {
	}

	private static final List<Crs> CRSS = List.of(
			new Crs("http://www.opengis.net/def/crs/OGC/1.3/CRS84", false, false, false),
			new Crs("http://www.opengis.net/def/crs/EPSG/0/4326", true, false, false),
			new Crs("http://www.opengis.net/def/crs/EPSG/0/7844", true, false, false),
			new Crs("http://www.opengis.net/def/crs/EPSG/0/4979", true, true, false),
			new Crs("http://www.opengis.net/def/crs/EPSG/0/3857", false, false, true),
			new Crs("http://www.opengis.net/def/crs/EPSG/0/7856", false, false, true));

	@Test
	public void seed1() throws Exception {
		compare(1, 0);
	}

	@Test
	public void seed2() throws Exception {
		compare(2, 0);
	}

	@Test
	public void seed3WithFewLuceneClauses() throws Exception {
		compare(3, 4);
	}

	private void compare(long seed, int clauseLimit) throws Exception {
		Random random = new Random(seed);
		Map<String, String> features = new LinkedHashMap<>();
		List<String> rejected = new ArrayList<>();
		for (int i = 0; i < FEATURES; i++) {
			if (i == FEATURES / 2) {
				enablePlugin();
			}
			String name = "f" + i;
			String wkt = randomGeometry(random);
			boolean valid = isValidSource(wkt);
			// The plugin indexes nothing before it is enabled, and an invalid geometry would then fail enabling it.
			if (!valid && i < FEATURES / 2) {
				rejected.add(name + " " + wkt);
				continue;
			}
			// An enabled plugin rejects an insert exactly when Jena rejects its geometry; any other failure is a fault.
			try {
				executeSparqlUpdateQuery(PREFIXES + "INSERT DATA {\n"
						+ "  ex:" + name + " a geo:Feature ; geo:hasDefaultGeometry ex:" + name + "Geom .\n"
						+ "  ex:" + name + "Geom a geo:Geometry ; geo:asWKT \"" + wkt + "\"^^geo:wktLiteral .\n}");
			} catch (Exception e) {
				if (valid) {
					throw new AssertionError("insert of a valid geometry failed: " + wkt, e);
				}
				if (!rejectedByJena(e)) {
					throw new AssertionError("insert of an invalid geometry failed for another reason: " + wkt, e);
				}
				rejected.add(name + " " + wkt);
				continue;
			}
			assertTrue("insert of an invalid geometry succeeded: " + wkt, valid);
			features.put(name, wkt);
		}
		assertTrue("too few geometries accepted: " + rejected, features.size() >= FEATURES * 3 / 4);
		for (Map.Entry<String, String> bound : new TreeMap<>(FIXED_BOUNDS).entrySet()) {
			executeSparqlUpdateQuery(PREFIXES + "INSERT DATA {\n"
					+ "  ex:" + bound.getKey() + " a geo:Feature ; geo:hasDefaultGeometry ex:" + bound.getKey() + "Geom .\n"
					+ "  ex:" + bound.getKey() + "Geom a geo:Geometry ; geo:asWKT \"" + bound.getValue()
					+ "\"^^geo:wktLiteral .\n}");
			features.put(bound.getKey(), bound.getValue());
		}
		List<String> bounds = new ArrayList<>(features.keySet().stream().limit(BOUNDS).toList());
		bounds.addAll(new TreeSet<>(FIXED_BOUNDS.keySet()));
		bounds.add(ABSENT);
		Set<Value> candidates = features.keySet().stream().map(name -> (Value) VF.createIRI(NAMESPACE, name))
				.collect(Collectors.toCollection(LinkedHashSet::new));
		String values = features.keySet().stream().map(name -> "ex:" + name).collect(Collectors.joining(" "));

		int previousClauseLimit = BooleanQuery.getMaxClauseCount();
		if (clauseLimit > 0) {
			BooleanQuery.setMaxClauseCount(clauseLimit);
		}
		List<String> mismatches = new ArrayList<>();
		try {
			for (String bound : bounds) {
				for (String relation : RELATIONS) {
					for (boolean boundSubject : new boolean[] { false, true }) {
						Set<Value> property = query("SELECT DISTINCT ?candidate WHERE {\n" + (boundSubject
								? "  ex:" + bound + " geo:" + relation + " ?candidate .\n"
								: "  ?candidate geo:" + relation + " ex:" + bound + " .\n") + "}");
						property.retainAll(candidates);
						Set<Value> function = query("SELECT DISTINCT ?candidate WHERE {\n"
								+ "  VALUES ?candidate { " + values + " }\n"
								+ "  ?candidate geo:hasDefaultGeometry/geo:asWKT ?candidateWkt .\n"
								+ "  ex:" + bound + " geo:hasDefaultGeometry/geo:asWKT ?boundWkt .\n"
								+ "  FILTER(geof:" + relation + "(" + (boundSubject ? "?boundWkt, ?candidateWkt"
								: "?candidateWkt, ?boundWkt") + "))\n}");
						if (!property.equals(function)) {
							mismatches.add(describe(features, relation, bound, boundSubject, property, function));
						}
					}
				}
			}
		} finally {
			BooleanQuery.setMaxClauseCount(previousClauseLimit);
		}
		assertTrue("seed " + seed + ":\n" + String.join("\n", mismatches), mismatches.isEmpty());
	}

	private static String describe(Map<String, String> features, String relation, String bound,
			boolean boundSubject, Set<Value> property, Set<Value> function) {
		Set<Value> extra = new LinkedHashSet<>(property);
		extra.removeAll(function);
		Set<Value> missing = new LinkedHashSet<>(function);
		missing.removeAll(property);
		return relation + (boundSubject ? " with the bound as subject " : " with the bound as object ") + bound
				+ " " + features.get(bound) + "\n  property relation only: " + wkts(features, extra)
				+ "\n  filter function only: " + wkts(features, missing);
	}

	private static String wkts(Map<String, String> features, Set<Value> candidates) {
		return candidates.stream().map(value -> {
			String name = value.stringValue().substring(NAMESPACE.length());
			return name + " " + features.get(name);
		}).collect(Collectors.joining("; "));
	}

	/**
	 * Whether Jena accepts the geometry as a source geometry literal, without the index's own processing: an index
	 * that rejects such a geometry fails the test rather than excludes the geometry from it.
	 */
	private static boolean isValidSource(String wkt) {
		try {
			JenaGeometryAdapter.toSourceGeometryLiteral(VF.createLiteral(wkt, GeoConstants.GEO_WKT_LITERAL),
					GeoConstants.GEO_WKT_LITERAL).asGeometryWrapper();
			return true;
		} catch (JenaGeoSparqlException e) {
			return false;
		}
	}

	private static boolean rejectedByJena(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof JenaGeoSparqlException) {
				return true;
			}
		}
		return false;
	}

	private Set<Value> query(String query) throws Exception {
		return new LinkedHashSet<>(executeSparqlQueryWithResult(PREFIXES + query, "candidate"));
	}

	private static String randomGeometry(Random random) {
		Crs crs = CRSS.get(random.nextInt(CRSS.size()));
		Ordinates ordinates = Ordinates.random(random, crs);
		double[] centre = { LONGITUDE + (random.nextDouble() * 2 - 1) * SPREAD,
				LATITUDE + (random.nextDouble() * 2 - 1) * SPREAD };
		double size = 0.0005 + random.nextDouble() * 0.01;
		String srs = "<" + crs.uri() + "> ";
		return srs + switch (random.nextInt(10)) {
			case 0 -> "POINT" + ordinates.tag() + "(" + position(crs, ordinates, random, centre[0], centre[1]) + ")";
			case 1 -> "LINESTRING" + ordinates.tag() + "(" + positions(crs, ordinates, random,
					centre[0], centre[1], centre[0] + size, centre[1] + size * (random.nextDouble() - 0.5),
					centre[0] + size * random.nextDouble(), centre[1] + size) + ")";
			case 2 -> "POLYGON" + ordinates.tag() + "(" + rectangle(crs, ordinates, random, centre, size) + ")";
			case 3 -> "POLYGON" + ordinates.tag() + "((" + positions(crs, ordinates, random,
					centre[0], centre[1], centre[0] + size, centre[1], centre[0], centre[1] + size,
					centre[0], centre[1]) + "))";
			case 4 -> "POLYGON" + ordinates.tag() + "((" + positions(crs, ordinates, random,
					centre[0], centre[1], centre[0] + size, centre[1] + size, centre[0] + size, centre[1],
					centre[0], centre[1] + size, centre[0], centre[1]) + "))";
			case 5 -> "MULTIPOINT" + ordinates.tag() + "((" + position(crs, ordinates, random, centre[0], centre[1])
					+ "),(" + position(crs, ordinates, random, centre[0] + size, centre[1] - size) + "))";
			case 6 -> "MULTIPOLYGON" + ordinates.tag() + "(" + rectangle(crs, ordinates, random, centre, size) + ","
					+ rectangle(crs, ordinates, random, new double[] { centre[0] + 2 * size, centre[1] }, size)
					+ ")";
			case 7 -> collection(crs, ordinates, random, centre, size);
			case 8 -> crs.projected()
					? "POLYGON((" + infiniteRectangle(crs, centre, size) + "))"
					: "POLYGON" + ordinates.tag() + "(" + rectangle(crs, ordinates, random, centre, size) + ")";
			default -> "GEOMETRYCOLLECTION" + ordinates.tag() + "(POINT" + ordinates.tag() + "("
					+ position(crs, ordinates, random, centre[0], centre[1]) + "),POLYGON" + ordinates.tag() + "("
					+ rectangle(crs, ordinates, random, new double[] { centre[0] + size, centre[1] }, size) + "))";
		};
	}

	/** A collection that stresses one rule: empty members, members of different dimensions or an infinite X. */
	private static String collection(Crs crs, Ordinates ordinates, Random random, double[] centre, double size) {
		String point = "POINT" + ordinates.tag() + "(" + position(crs, ordinates, random, centre[0], centre[1]) + ")";
		return switch (random.nextInt(4)) {
			case 0 -> "GEOMETRYCOLLECTION" + ordinates.tag() + "(" + point + ",POLYGON EMPTY)";
			case 1 -> "GEOMETRYCOLLECTION" + ordinates.tag() + "(LINESTRING" + ordinates.tag() + "("
					+ positions(crs, ordinates, random, centre[0], centre[1], centre[0] + size, centre[1] + size)
					+ "),POLYGON EMPTY)";
			case 2 -> "GEOMETRYCOLLECTION Z(POINT(" + position(crs, Ordinates.XY, random, centre[0], centre[1])
					+ "),POINT Z(" + position(crs, Ordinates.Z, random, centre[0] + size, centre[1]) + "))";
			default -> crs.projected()
					? "GEOMETRYCOLLECTION(POINT(1e309 " + ordinate(xy(crs, centre[0], centre[1])[1]) + "),POINT("
							+ position(crs, Ordinates.XY, random, centre[0], centre[1]) + "))"
					: "GEOMETRYCOLLECTION" + ordinates.tag() + "(" + point + ",POINT" + ordinates.tag() + "("
							+ position(crs, ordinates, random, centre[0] + size, centre[1] + size) + "))";
		};
	}

	/** A rectangle in a projected CRS whose east side lies at infinity. */
	private static String infiniteRectangle(Crs crs, double[] corner, double size) {
		double[] low = xy(crs, corner[0], corner[1]);
		double[] high = xy(crs, corner[0] + size, corner[1] + size);
		return ordinate(low[0]) + " " + ordinate(low[1]) + ",1e309 " + ordinate(low[1]) + ",1e309 " + ordinate(high[1])
				+ "," + ordinate(low[0]) + " " + ordinate(high[1]) + "," + ordinate(low[0]) + " " + ordinate(low[1]);
	}

	private static String rectangle(Crs crs, Ordinates ordinates, Random random, double[] corner, double size) {
		double x = corner[0];
		double y = corner[1];
		return "(" + positions(crs, ordinates, random, x, y, x + size, y, x + size, y + size, x, y + size, x, y) + ")";
	}

	private static String positions(Crs crs, Ordinates ordinates, Random random, double... lonLat) {
		List<String> positions = new ArrayList<>();
		for (int i = 0; i < lonLat.length; i += 2) {
			positions.add(position(crs, ordinates, random, lonLat[i], lonLat[i + 1]));
		}
		return String.join(",", positions);
	}

	private static String position(Crs crs, Ordinates ordinates, Random random, double longitude, double latitude) {
		double[] xy = xy(crs, longitude, latitude);
		StringBuilder position = new StringBuilder(ordinate(xy[0])).append(' ').append(ordinate(xy[1]));
		if (ordinates.z()) {
			position.append(' ').append(ordinate(ordinates.height(random)));
		}
		if (ordinates.m()) {
			position.append(' ').append(ordinate(ordinates.measure(random)));
		}
		return position.toString();
	}

	/** The position in the CRS's axis order: longitude and latitude, or a projection of them. */
	private static double[] xy(Crs crs, double longitude, double latitude) {
		if (crs.uri().endsWith("/3857")) {
			return new double[] { MERCATOR_RADIUS * StrictMath.toRadians(longitude),
					MERCATOR_RADIUS * StrictMath.log(StrictMath.tan(StrictMath.PI / 4 + StrictMath.toRadians(latitude) / 2)) };
		}
		if (crs.uri().endsWith("/7856")) {
			return new double[] { 502000 + (longitude - LONGITUDE) * 98800, 6961000 + (latitude - LATITUDE) * 110900 };
		}
		return crs.latitudeFirst() ? new double[] { latitude, longitude } : new double[] { longitude, latitude };
	}

	private static String ordinate(double value) {
		if (Double.isInfinite(value)) {
			return value > 0 ? "1e309" : "-1e309";
		}
		return String.format(Locale.ROOT, "%.9f", value);
	}

	/** Which ordinates beyond X and Y a geometry carries, and how unusual their values may be. */
	private record Ordinates(boolean z, boolean m, double unusual) {
		static final Ordinates XY = new Ordinates(false, false, 0);
		static final Ordinates Z = new Ordinates(true, false, 0.3);

		static Ordinates random(Random random, Crs crs) {
			double unusual = random.nextInt(3) == 0 ? 0.3 : 0;
			if (crs.height()) {
				return new Ordinates(true, random.nextInt(4) == 0, unusual);
			}
			return switch (random.nextInt(6)) {
				case 0 -> new Ordinates(true, false, unusual);
				case 1 -> new Ordinates(false, true, unusual);
				case 2 -> new Ordinates(true, true, unusual);
				default -> XY;
			};
		}

		String tag() {
			return z && m ? " ZM" : z ? " Z" : m ? " M" : "";
		}

		/** A height in metres: usually a few hundred, sometimes a million, sometimes infinite. */
		double height(Random random) {
			double draw = random.nextDouble();
			return draw < unusual / 2 ? Double.POSITIVE_INFINITY : draw < unusual ? 1e6 : random.nextDouble() * 500;
		}

		double measure(Random random) {
			return random.nextDouble() < unusual / 2 ? Double.POSITIVE_INFINITY : random.nextDouble() * 100;
		}
	}
}
