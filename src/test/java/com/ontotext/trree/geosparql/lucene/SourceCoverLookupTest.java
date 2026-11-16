package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Lookup of sources that carry cover boxes. The fixtures sit around 15.5 E 60.5 N. The ring has an outer radius of
 * 0.4 degrees and a hole of radius 0.2 degrees, so its envelope holds four empty corners and an empty middle. Each
 * source is written in a CRS with the coordinates Jena gives the CRS84 geometry there.
 */
public class SourceCoverLookupTest {
    private static final String CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84";
    private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
    private static final String MERCATOR = "http://www.opengis.net/def/crs/EPSG/0/3857";
    private static final String UTM_33N = "http://www.opengis.net/def/crs/EPSG/0/32633";
    private static final List<String> CRSS = List.of(CRS84, EPSG_4326, MERCATOR, UTM_33N);
    private static final String RING = SourceCoverBoxesTest.ring(15.5, 60.5, 0.4, 0.2, 500);
    private static final String IN_THE_HOLE = "POINT(15.45 60.45)";
    private static final String ON_THE_RING = "POINT(15.8 60.5)";

    @Rule
    public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    /**
     * A bound in the hole of the ring meets the ring's envelope and none of its boxes. The ring is then no
     * candidate and is a definite disjoint match, whether the bound is transformed into the ring's CRS or is
     * written in it.
     */
    @Test
    public void boundInAnEmptyPartOfTheEnvelopeIsApartInEveryCrs() throws Exception {
        for (String crs : CRSS) {
            LuceneGeoIndexer indexer = createIndexer("apart-" + CRSS.indexOf(crs));
            index(indexer, List.of(geometry(in(crs, RING))));
            for (IndexGeometry bound : List.of(geometry(IN_THE_HOLE), geometry(in(crs, IN_THE_HOLE)))) {
                boolean candidateIsSubject = bound.sourceGeometryLiteral().lexicalForm().equals(IN_THE_HOLE);
                String label = crs + " " + candidateIsSubject;

                assertEquals(label, Set.of(), ids(indexer.getEnvelopeIntersections(bound, candidateIsSubject)));
                assertEquals(label, Set.of(1L), definite(indexer.getEnvelopeDisjointCandidates(bound, candidateIsSubject)));
                assertEquals(label, Set.of(),
                        ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject)));
            }
        }
    }

    @Test
    public void boundOnTheGeometryRemainsACandidateInEveryCrs() throws Exception {
        for (String crs : CRSS) {
            LuceneGeoIndexer indexer = createIndexer("meeting-" + CRSS.indexOf(crs));
            IndexGeometry ring = geometry(in(crs, RING));
            index(indexer, List.of(ring));
            IndexGeometry bound = geometry(ON_THE_RING);

            assertTrue(crs, holds(GeoSparqlPropertyRelation.SF_INTERSECTS, ring.sourceGeometryLiteral(),
                    bound.sourceGeometryLiteral()));
            assertEquals(crs, Set.of(1L), ids(indexer.getEnvelopeIntersections(bound, true)));
            assertEquals(crs, Set.of(), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
            assertEquals(crs, Set.of(1L), ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
        }
    }

    /**
     * A source that is apart from a bound by its boxes is returned as disjoint without exact evaluation. Exact
     * evaluation agrees for a generic collection as well, which is related by its own evaluation path.
     */
    @Test
    public void largeCollectionApartFromABoundIsDisjointUnderExactEvaluationToo() throws Exception {
        IndexGeometry collection = geometry("GEOMETRYCOLLECTION(" + RING + "," + climbingLine(400) + ")");
        LuceneGeoIndexer indexer = createIndexer("collection");
        index(indexer, List.of(collection));
        // Outside the ring, beside the line and inside the envelope of both.
        IndexGeometry bound = geometry("POINT(15.85 60.15)");

        assertEquals(Set.of(1L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
        assertEquals(Set.of(), ids(indexer.getEnvelopeIntersections(bound, true)));
        for (GeoSparqlPropertyRelation disjoint : List.of(GeoSparqlPropertyRelation.SF_DISJOINT,
                GeoSparqlPropertyRelation.EH_DISJOINT)) {
            assertTrue(disjoint.toString(), holds(disjoint, collection.sourceGeometryLiteral(),
                    bound.sourceGeometryLiteral()));
        }
    }

    /**
     * Cover boxes decide only for a valid bound. Two overlapping squares in the hole of the ring form an invalid
     * multipolygon, apart from every box of the ring; the ring stays a candidate for exact evaluation in both
     * directions instead of becoming a definite disjoint match. A valid square in the same place is a definite
     * disjoint match.
     */
    @Test
    public void coverBoxesDecideNothingForAnInvalidBound() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("invalid-bound");
        index(indexer, List.of(geometry(RING)));
        IndexGeometry overlappingParts = geometry("MULTIPOLYGON(((15.45 60.45,15.52 60.45,15.52 60.52,15.45 60.52,"
                + "15.45 60.45)),((15.48 60.48,15.55 60.48,15.55 60.55,15.48 60.55,15.48 60.48)))");
        IndexGeometry square = geometry("POLYGON((15.45 60.45,15.55 60.45,15.55 60.55,15.45 60.55,15.45 60.45))");

        for (boolean candidateIsSubject : new boolean[]{true, false}) {
            assertEquals(Set.of(1L), definite(indexer.getEnvelopeDisjointCandidates(square, candidateIsSubject)));
            assertEquals(Set.of(), ids(indexer.getEnvelopeDisjointUncertainCandidates(square, candidateIsSubject)));
            assertEquals(Set.of(), definite(indexer.getEnvelopeDisjointCandidates(overlappingParts, candidateIsSubject)));
            assertEquals(Set.of(1L),
                    ids(indexer.getEnvelopeDisjointUncertainCandidates(overlappingParts, candidateIsSubject)));
            assertEquals(Set.of(1L), ids(indexer.getEnvelopeIntersections(overlappingParts, candidateIsSubject)));
        }
    }

    /** A triangle has too few points for boxes, so a bound in the empty half of its envelope stays a candidate. */
    @Test
    public void sourceWithoutBoxesIsLookedUpByItsEnvelope() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("without-boxes");
        index(indexer, List.of(geometry("POLYGON((15 60,16 60,15 61,15 60))")));
        IndexGeometry bound = geometry("POINT(15.9 60.9)");

        assertEquals(Set.of(1L), ids(indexer.getEnvelopeIntersections(bound, true)));
        assertEquals(Set.of(1L), ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
    }

    /**
     * Boxes describe a source on its own coordinates. Where the bound is the subject in another CRS, Jena evaluates
     * the source transformed into that CRS, and the lookup by CRS84 index envelopes keeps the source.
     */
    @Test
    public void boundThatIsNotPlacedInTheSourceCrsKeepsTheSourceAsACandidate() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("not-placed");
        index(indexer, List.of(geometry(RING)));

        assertEquals(Set.of(1L), ids(indexer.getEnvelopeIntersections(geometry(in(MERCATOR, IN_THE_HOLE)), false)));
    }

    /**
     * Every source that Jena relates to a bound is a candidate, every definite disjoint match is disjoint, and every
     * disjoint source is found in one of the disjoint phases, for large sources and bounds in each pair of CRSs and
     * for both binding directions.
     */
    @Test
    public void lookupKeepsEveryLargeSourceJenaRelatesInEitherDirection() throws Exception {
        Random random = new Random(40);
        List<IndexGeometry> sources = new ArrayList<>();
        for (String crs : CRSS) {
            sources.add(geometry(in(crs, RING)));
            sources.add(geometry(in(crs, SourceCoverBoxesTest.ring(15.3, 60.7, 0.25, 0.1, 300))));
            sources.add(geometry(in(crs, climbingLine(400))));
        }
        LuceneGeoIndexer indexer = createIndexer("differential");
        index(indexer, sources);

        for (int i = 0; i < 60; i++) {
            IndexGeometry bound = geometry(in(CRSS.get(i % CRSS.size()), randomShape(random)));
            for (boolean candidateIsSubject : new boolean[]{true, false}) {
                Set<Long> intersections = ids(indexer.getEnvelopeIntersections(bound, candidateIsSubject));
                Set<Long> definite = definite(indexer.getEnvelopeDisjointCandidates(bound, candidateIsSubject));
                Set<Long> evaluated = ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject));
                evaluated.addAll(ids(indexer.getDisjointTransformCleanupCandidates(bound, candidateIsSubject)));
                for (int id = 1; id <= sources.size(); id++) {
                    SourceGeometryLiteral source = sources.get(id - 1).sourceGeometryLiteral();
                    SourceGeometryLiteral subject = candidateIsSubject ? source : bound.sourceGeometryLiteral();
                    SourceGeometryLiteral object = candidateIsSubject ? bound.sourceGeometryLiteral() : source;
                    String pair = "source " + id + " / " + bound.sourceGeometryLiteral().lexicalForm()
                            + " / candidate is subject " + candidateIsSubject;
                    if (holds(GeoSparqlPropertyRelation.SF_INTERSECTS, subject, object)) {
                        assertTrue("intersecting pair omitted: " + pair, intersections.contains((long) id));
                    }
                    boolean disjoint = holds(GeoSparqlPropertyRelation.SF_DISJOINT, subject, object);
                    if (definite.contains((long) id)) {
                        assertTrue("definite disjoint match is not disjoint: " + pair, disjoint);
                    } else if (disjoint) {
                        assertTrue("disjoint pair omitted: " + pair, evaluated.contains((long) id));
                    }
                }
            }
        }
    }

    private static boolean holds(GeoSparqlPropertyRelation relation, SourceGeometryLiteral subject,
            SourceGeometryLiteral object) {
        return relation.evaluate(List.of(subject), List.of(object));
    }

    /** A point, a line or a rectangle within the envelopes of the sources, as CRS84 WKT. */
    private static String randomShape(Random random) {
        double x = 15.05 + random.nextDouble() * 0.9;
        double y = 60.05 + random.nextDouble() * 0.9;
        double width = random.nextDouble() * 0.1;
        double height = random.nextDouble() * 0.1;
        return switch (random.nextInt(3)) {
            case 0 -> String.format(Locale.ROOT, "POINT(%.6f %.6f)", x, y);
            case 1 -> String.format(Locale.ROOT, "LINESTRING(%.6f %.6f,%.6f %.6f)", x, y, x + width, y + height);
            default -> String.format(Locale.ROOT, "POLYGON((%1$.6f %2$.6f,%3$.6f %2$.6f,%3$.6f %4$.6f,%1$.6f %4$.6f,"
                    + "%1$.6f %2$.6f))", x, y, x + width + 0.001, y + height + 0.001);
        };
    }

    /** A line of the given number of points that climbs in steps from 15.1 E 60.1 N to 15.9 E 60.9 N. */
    private static String climbingLine(int points) {
        StringBuilder line = new StringBuilder("LINESTRING(15.1 60.1");
        double step = 0.8 / (points / 2);
        for (int i = 1; i < points; i++) {
            line.append(String.format(Locale.ROOT, ",%.6f %.6f", 15.1 + (i + 1) / 2 * step, 60.1 + i / 2 * step));
        }
        return line.append(')').toString();
    }

    /** Writes CRS84 WKT in another CRS, with the coordinates Jena gives the geometry there. */
    private static String in(String crsUri, String crs84Wkt) throws Exception {
        if (CRS84.equals(crsUri)) {
            return crs84Wkt;
        }
        return SourceGeometryLiteral.fromWkt(crs84Wkt).asGeometryWrapper().transform(crsUri).asLiteral()
                .getLexicalForm();
    }

    private LuceneGeoIndexer createIndexer(String name) throws Exception {
        GeoSparqlPlugin parent = new GeoSparqlPlugin();
        GeoSparqlConfig config = new GeoSparqlConfig();
        config.setPrefixTree(GeoSparqlConfig.PrefixTree.QUAD);
        config.setPrecision(11);
        config.updateCurrentSettings();
        parent.setConfig(config);
        parent.setLogger(LoggerFactory.getLogger(SourceCoverLookupTest.class));
        parent.setDataDir(Files.createDirectories(tmpFolder.getRoot().toPath().resolve(name)).toFile());
        LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent);
        indexer.initialize();
        return indexer;
    }

    private static void index(LuceneGeoIndexer indexer, List<IndexGeometry> geometries) throws Exception {
        indexer.begin();
        for (int i = 0; i < geometries.size(); i++) {
            indexer.indexGeometryList(i + 1L, id -> "geometry-" + id, List.of(geometries.get(i)));
        }
        indexer.commit();
        indexer.complete();
    }

    private static Set<Long> ids(CloseableIterator<CandidateEntity> iterator) throws Exception {
        try (iterator) {
            Set<Long> ids = new TreeSet<>();
            while (iterator.hasNext()) {
                ids.add(iterator.next().entityId());
            }
            return ids;
        }
    }

    private static Set<Long> definite(CloseableIterator<EnvelopeDisjointCandidate> iterator) throws Exception {
        try (iterator) {
            Set<Long> ids = new TreeSet<>();
            while (iterator.hasNext()) {
                ids.add(iterator.next().entityId());
            }
            return ids;
        }
    }

    private static IndexGeometry geometry(String wkt) {
        return IndexGeometry.fromSourceGeometryLiteral(SourceGeometryLiteral.fromWkt(wkt));
    }
}
