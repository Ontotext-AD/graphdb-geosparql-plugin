package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.util.BytesRef;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.LongStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Lookup in the CRS where Jena evaluates a pair. The fixtures sit around 15.5 E 60.5 N. The CRS84 bound spans
 * 15 to 16 E and 60 to 61 N, and "beside" sources lie 0.01 degrees east of it: apart from the bound, and within
 * the prefix cells of its CRS84 index envelope at QUAD precision 11.
 */
public class EvaluationCrsLookupTest {
    private static final String CRS84 = "http://www.opengis.net/def/crs/OGC/1.3/CRS84";
    private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";
    private static final String MERCATOR = "http://www.opengis.net/def/crs/EPSG/0/3857";
    private static final String UTM_33N = "http://www.opengis.net/def/crs/EPSG/0/32633";
    private static final String POLAR_STEREOGRAPHIC = "http://www.opengis.net/def/crs/EPSG/0/3413";
    private static final List<String> SOURCE_CRSS = List.of(CRS84, EPSG_4326, MERCATOR, UTM_33N);
    /** CRSs in which the CRS84 bound stays a rectangle with sides along the axes. */
    private static final List<String> ALIGNED_CRSS = List.of(CRS84, EPSG_4326, MERCATOR);
    private static final String BOUND = "POLYGON((15 60,16 60,16 61,15 61,15 60))";

    @Rule
    public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    /** Sources 1 to 3 lie inside the bound and sources 4 to 6 beside it, one of each per source CRS. */
    @Test
    public void subjectCandidatesAreSelectedByTheirOwnEnvelopesInEverySourceCrs() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("subject");
        index(indexer, insideAndBeside());
        IndexGeometry bound = geometry(BOUND);

        assertEquals(Set.of(1L, 2L, 3L), ids(indexer.getEnvelopeIntersections(bound, true)));
        assertEquals(Set.of(4L, 5L, 6L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
        // The bound fills its envelope in each of these CRSs, so a source inside it is not disjoint.
        assertEquals(Set.of(), ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
        assertEquals(Set.of(), ids(indexer.getDisjointTransformCleanupCandidates(bound, true)));
    }

    /**
     * The bound is placed in every source CRS, however many the reader holds. Sources 1 to 4 lie inside the bound
     * and beside it in EPSG:4326 and EPSG:3857, whose URIs sort after those of the eighteen UTM CRSs that the other
     * sources use. The sources beside the bound share prefix cells with it and are still not candidates.
     */
    @Test
    public void subjectCandidatesAreSelectedByTheirOwnEnvelopesInManySourceCrss() throws Exception {
        Set<Long> inside = new TreeSet<>();
        LuceneGeoIndexer indexer = createIndexer("many-crss");
        index(indexer, sourcesInManyCrss(inside));
        IndexGeometry bound = geometry(BOUND);

        assertEquals(inside, ids(indexer.getEnvelopeIntersections(bound, true)));
        assertEquals(Set.of(), ids(indexer.getDisjointTransformCleanupCandidates(bound, true)));
    }

    /**
     * A lookup has one clause for each CRS it places the bound in. With twelve clauses the bound is placed in its
     * own CRS and ten of the twenty source CRSs. Every source of the other ten, eight UTM CRSs, EPSG:3857 and
     * EPSG:4326, is left to exact evaluation, so the sources beside the bound in the last two are candidates too.
     * Every disjoint source is still found, and every definite disjoint match holds.
     */
    @Test
    public void lookupPastTheClauseLimitLosesNoSource() throws Exception {
        Set<Long> inside = new TreeSet<>();
        List<IndexGeometry> sources = sourcesInManyCrss(inside);
        LuceneGeoIndexer indexer = createIndexer("clause-limit");
        index(indexer, sources);
        IndexGeometry bound = geometry(BOUND);
        Set<Long> expected = new TreeSet<>(inside);
        expected.addAll(Set.of(2L, 4L));
        int clauseLimit = BooleanQuery.getMaxClauseCount();
        BooleanQuery.setMaxClauseCount(12);
        try {
            assertEquals(expected, ids(indexer.getEnvelopeIntersections(bound, true)));
            Set<Long> disjoint = definite(indexer.getEnvelopeDisjointCandidates(bound, true));
            disjoint.addAll(ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
            disjoint.addAll(ids(indexer.getDisjointTransformCleanupCandidates(bound, true)));
            for (int id = 1; id <= sources.size(); id++) {
                if (holds(GeoSparqlPropertyRelation.SF_DISJOINT, sources.get(id - 1).sourceGeometryLiteral(),
                        bound.sourceGeometryLiteral())) {
                    assertTrue("disjoint source " + id + " omitted", disjoint.contains((long) id));
                }
            }
            for (long id : definite(indexer.getEnvelopeDisjointCandidates(bound, true))) {
                assertTrue("source " + id + " is no disjoint match", holds(GeoSparqlPropertyRelation.SF_DISJOINT,
                        sources.get((int) id - 1).sourceGeometryLiteral(), bound.sourceGeometryLiteral()));
            }
        } finally {
            BooleanQuery.setMaxClauseCount(clauseLimit);
        }
    }

    /**
     * Past the clause limit the bound may not be placed in CRS84. A CRS84 source is then evaluated exactly, not proved
     * disjoint by its index envelope: Jena transforms the bound into CRS84, and cannot transform this bound, whose
     * height is infinite, so no relation holds.
     */
    @Test
    public void crs84SourceBeyondTheClauseLimitIsEvaluatedExactly() throws Exception {
        List<IndexGeometry> sources = new ArrayList<>(List.of(geometry("POINT(153.031 -27.47)")));
        for (int zone = 50; zone <= 53; zone++) {
            sources.add(geometry("<http://www.opengis.net/def/crs/EPSG/0/327" + zone + "> POINT(500000 6960000)"));
        }
        LuceneGeoIndexer indexer = createIndexer("crs84-beyond-clause-limit");
        index(indexer, sources);
        IndexGeometry bound = geometry("<" + EPSG_4326 + "> POINT Z(-27.47 153.03 1e309)");
        int clauseLimit = BooleanQuery.getMaxClauseCount();
        BooleanQuery.setMaxClauseCount(4);
        try {
            assertFalse(definite(indexer.getEnvelopeDisjointCandidates(bound, true)).contains(1L));
            assertTrue(ids(indexer.getDisjointTransformCleanupCandidates(bound, true)).contains(1L));
        } finally {
            BooleanQuery.setMaxClauseCount(clauseLimit);
        }
        assertFalse(holds(GeoSparqlPropertyRelation.SF_DISJOINT, sources.get(0).sourceGeometryLiteral(),
                bound.sourceGeometryLiteral()));
    }

    /**
     * A CRS whose documents hold only empty geometries, or are deleted, keeps its term in the index but takes no
     * placement. With three clauses the bound is placed in its own CRS and one other. That is EPSG:3413 here, so its
     * source on the bound's chord is a candidate and its source far from the bound is not. EPSG:32633 sorts before it.
     */
    @Test
    public void crssWithoutLiveSpatialSourcesTakeNoPlacement() throws Exception {
        IndexGeometry bound = thinPolarBound();
        IndexGeometry farFromBound = geometry("<" + POLAR_STEREOGRAPHIC + "> POINT(-1000000 1000000)");
        LuceneGeoIndexer withEmpty = createIndexer("crs-of-empty-geometries");
        index(withEmpty, List.of(pointOnThinPolarBoundChord(), geometry("<" + UTM_33N + "> POINT EMPTY"),
                farFromBound));
        LuceneGeoIndexer withDeleted = createIndexer("crs-of-deleted-geometries");
        index(withDeleted, List.of(pointOnThinPolarBoundChord(), geometry("<" + UTM_33N + "> POINT(500000 6700000)"),
                farFromBound));
        delete(withDeleted, 2L);
        int clauseLimit = BooleanQuery.getMaxClauseCount();
        BooleanQuery.setMaxClauseCount(3);
        try {
            assertEquals(Set.of(1L), ids(withEmpty.getEnvelopeIntersections(bound, true)));
            assertEquals(Set.of(1L), ids(withDeleted.getEnvelopeIntersections(bound, true)));
        } finally {
            BooleanQuery.setMaxClauseCount(clauseLimit);
        }
    }

    /**
     * With three clauses the bound is placed in its own CRS and in EPSG:32633, which sorts before EPSG:3413 and holds
     * a live source. Every source of EPSG:3413, past the clause limit, is a candidate, so the source on the bound's
     * chord stays one although the CRS84 lookup would miss it.
     */
    @Test
    public void sourcesOfCrssPastTheClauseLimitAreLeftToExactEvaluation() throws Exception {
        IndexGeometry bound = thinPolarBound();
        LuceneGeoIndexer indexer = createIndexer("crs-past-the-clause-limit");
        index(indexer, List.of(pointOnThinPolarBoundChord(), geometry("<" + UTM_33N + "> POINT(500000 6700000)"),
                geometry("<" + POLAR_STEREOGRAPHIC + "> POINT(-1000000 1000000)")));
        int clauseLimit = BooleanQuery.getMaxClauseCount();
        BooleanQuery.setMaxClauseCount(3);
        try {
            assertEquals(Set.of(1L, 3L), ids(indexer.getEnvelopeIntersections(bound, true)));
            assertEquals(Set.of(1L, 3L), ids(indexer.getEnvelopeEqualityCandidates(bound, true)));
        } finally {
            BooleanQuery.setMaxClauseCount(clauseLimit);
        }
    }

    /**
     * A bound that is the subject is placed only in its own CRS, so none of its objects loses a placement to the
     * clause limit. With two clauses, which leave none for another CRS, the objects of EPSG:32633 are still selected
     * by CRS84 index envelopes: the one inside the bound is a candidate and the one far from it is not.
     */
    @Test
    public void objectsOfABoundSubjectAreSelectedByIndexEnvelopesAtAnyClauseLimit() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("bound-subject-at-the-clause-limit");
        index(indexer, List.of(geometry(literal(UTM_33N, "POINT(15.5 60.5)")),
                geometry(literal(UTM_33N, "POINT(15.5 10)"))));
        int clauseLimit = BooleanQuery.getMaxClauseCount();
        BooleanQuery.setMaxClauseCount(2);
        try {
            assertEquals(Set.of(1L), ids(indexer.getEnvelopeIntersections(geometry(BOUND), false)));
        } finally {
            BooleanQuery.setMaxClauseCount(clauseLimit);
        }
    }

    /**
     * A bound that is the subject is placed only in its own CRS, so the lookup only checks whether the reader holds
     * live spatial sources in that CRS and outside it. A CRS whose sources are all deleted or all empty holds none,
     * on either side, and the live sources on the other side stay candidates.
     */
    @Test
    public void objectsOfABoundSubjectAreFoundWhereOtherCrssHoldOnlyDeletedOrEmptySources() throws Exception {
        IndexGeometry bound = geometry(BOUND);
        IndexGeometry ownInside = geometry("POINT(15.5 60.5)");
        IndexGeometry otherInside = geometry(literal(UTM_33N, "POINT(15.5 60.5)"));
        LuceneGeoIndexer liveInBoth = createIndexer("bound-subject-live-in-both");
        index(liveInBoth, List.of(ownInside, otherInside));
        LuceneGeoIndexer liveOutside = createIndexer("bound-subject-live-outside");
        index(liveOutside, List.of(geometry("POINT EMPTY"), otherInside, ownInside));
        delete(liveOutside, 3L);
        LuceneGeoIndexer liveInOwn = createIndexer("bound-subject-live-in-own");
        index(liveInOwn, List.of(ownInside, geometry("<" + UTM_33N + "> POINT EMPTY"),
                geometry(literal(EPSG_4326, "POINT(15.5 60.5)"))));
        delete(liveInOwn, 3L);

        assertEquals(Set.of(1L, 2L), ids(liveInBoth.getEnvelopeIntersections(bound, false)));
        assertEquals(Set.of(2L), ids(liveOutside.getEnvelopeIntersections(bound, false)));
        assertEquals(Set.of(1L), ids(liveInOwn.getEnvelopeIntersections(bound, false)));
        assertSpatialSources(liveInBoth, true, true);
        assertSpatialSources(liveOutside, false, true);
        assertSpatialSources(liveInOwn, true, false);
    }

    private static void assertSpatialSources(LuceneGeoIndexer indexer, boolean inCrs84, boolean outsideCrs84)
            throws Exception {
        try (IndexReader reader = indexer.openReader()) {
            assertEquals(inCrs84, LuceneGeoDocumentSchema.hasSpatialSourcesIn(reader, CRS84));
            assertEquals(outsideCrs84, LuceneGeoDocumentSchema.hasSpatialSourcesOutside(reader, CRS84));
        }
    }

    /**
     * A bound that is the subject has one placement, so preparing its lookup reads little of the source CRS field
     * however the sources are spread: among a hundred CRSs it stops at the first other one with a live source, and
     * among a thousand sources of its own CRS it finds none outside that CRS without passing over them.
     */
    @Test
    public void preparingTheLookupOfABoundSubjectReadsFewCrssAndSources() throws Exception {
        List<IndexGeometry> oneInEachCrs = new ArrayList<>();
        for (int zone = 1; zone <= 50; zone++) {
            oneInEachCrs.add(geometry("<http://www.opengis.net/def/crs/EPSG/0/" + (32600 + zone)
                    + "> POINT(500000 1000000)"));
            oneInEachCrs.add(geometry("<http://www.opengis.net/def/crs/EPSG/0/" + (32700 + zone)
                    + "> POINT(500000 9000000)"));
        }
        LuceneGeoIndexer manyCrss = createIndexer("bound-subject-many-crss");
        index(manyCrss, oneInEachCrs);
        LuceneGeoIndexer oneCrs = createIndexer("bound-subject-one-crs");
        index(oneCrs, Collections.nCopies(1000, geometry("POINT(10 10)")));

        assertFewCrsFieldReads(manyCrss);
        assertFewCrsFieldReads(oneCrs);
    }

    private static void assertFewCrsFieldReads(LuceneGeoIndexer indexer) throws Exception {
        int[] reads = new int[2];
        try (DirectoryReader reader = new CrsFieldCountingReader((DirectoryReader) indexer.openReader(), reads)) {
            EvaluationCrsLookup.of(reader, geometry(BOUND), false);
        }
        assertTrue("CRS terms stepped through: " + reads[0], reads[0] <= 2);
        assertTrue("CRS postings stepped through: " + reads[1], reads[1] <= 4);
    }

    /** A reader that counts the terms of the source CRS field, and the documents of their postings, it steps through. */
    private static final class CrsFieldCountingReader extends FilterDirectoryReader {
        private final int[] reads;

        private CrsFieldCountingReader(DirectoryReader reader, int[] reads) throws IOException {
            super(reader, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader leaf) {
                    return new FilterLeafReader(leaf) {
                        @Override
                        public Terms terms(String field) throws IOException {
                            Terms terms = super.terms(field);
                            return terms == null || !LuceneGeoDocumentSchema.FIELD_SOURCE_CRS.equals(field)
                                    ? terms : new CountingTerms(terms, reads);
                        }

                        @Override
                        public CacheHelper getCoreCacheHelper() {
                            return in.getCoreCacheHelper();
                        }

                        @Override
                        public CacheHelper getReaderCacheHelper() {
                            return in.getReaderCacheHelper();
                        }
                    };
                }
            });
            this.reads = reads;
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader reader) throws IOException {
            return new CrsFieldCountingReader(reader, reads);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }

    private static final class CountingTerms extends FilterLeafReader.FilterTerms {
        private final int[] reads;

        private CountingTerms(Terms terms, int[] reads) {
            super(terms);
            this.reads = reads;
        }

        @Override
        public TermsEnum iterator() throws IOException {
            return new FilterLeafReader.FilterTermsEnum(in.iterator()) {
                @Override
                public BytesRef next() throws IOException {
                    reads[0]++;
                    return in.next();
                }

                @Override
                public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
                    return new FilterLeafReader.FilterPostingsEnum(in.postings(null, flags)) {
                        @Override
                        public int nextDoc() throws IOException {
                            reads[1]++;
                            return in.nextDoc();
                        }

                        @Override
                        public int advance(int target) throws IOException {
                            reads[1]++;
                            return in.advance(target);
                        }
                    };
                }
            };
        }
    }

    /**
     * A CRS84 polygon a thousandth of a degree tall from longitude -45 to 45 at latitude 70. Jena joins its
     * transformed vertices in EPSG:3413 with straight lines that run far from its CRS84 envelope and prefix cells.
     */
    private static IndexGeometry thinPolarBound() {
        return geometry("POLYGON((-45 70,45 70,45 70.001,-45 70.001,-45 70))");
    }

    /** An EPSG:3413 point that meets {@link #thinPolarBound} only on the straight line Jena draws there. */
    private static IndexGeometry pointOnThinPolarBoundChord() {
        Envelope west = geometry("POINT(-45 70.0005)").evaluationEnvelope(POLAR_STEREOGRAPHIC, true).orElseThrow()
                .envelope();
        Envelope east = geometry("POINT(45 70.0005)").evaluationEnvelope(POLAR_STEREOGRAPHIC, true).orElseThrow()
                .envelope();
        IndexGeometry onChord = geometry("<" + POLAR_STEREOGRAPHIC + "> POINT("
                + (west.getMinX() + east.getMinX()) / 2 + " " + (west.getMinY() + east.getMinY()) / 2 + ")");
        assertTrue(holds(GeoSparqlPropertyRelation.SF_INTERSECTS, onChord.sourceGeometryLiteral(),
                thinPolarBound().sourceGeometryLiteral()));
        return onChord;
    }

    /**
     * An infinite coordinate leaves a source's own envelope with a non-finite bound, which shows nothing about where
     * the source lies. The source stays a candidate wherever the bound may meet it and is never proved disjoint, and a
     * bound with such an envelope proves no source of its CRS disjoint.
     */
    @Test
    public void nonFiniteEnvelopesDecideNothing() throws Exception {
        String utm34 = "<http://www.opengis.net/def/crs/EPSG/0/32634> ";
        LuceneGeoIndexer indexer = createIndexer("non-finite-envelopes");
        index(indexer, List.of(geometry(utm34 + "GEOMETRYCOLLECTION(POINT(1e309 5),POINT(500000 6700000))"),
                geometry(utm34 + "POINT(400000 6600000)")));
        IndexGeometry point = geometry(utm34 + "POINT(500000 6700000)");
        IndexGeometry unbounded = geometry(utm34 + "GEOMETRYCOLLECTION(POINT(1e309 5),POINT(450000 6650000))");

        for (boolean candidateIsSubject : new boolean[]{true, false}) {
            assertEquals(Set.of(1L), ids(indexer.getEnvelopeIntersections(point, candidateIsSubject)));
            assertEquals(Set.of(1L), ids(indexer.getEnvelopeEqualityCandidates(point, candidateIsSubject)));
            assertEquals(Set.of(2L), definite(indexer.getEnvelopeDisjointCandidates(point, candidateIsSubject)));
            assertEquals(Set.of(1L, 2L), ids(indexer.getEnvelopeIntersections(unbounded, candidateIsSubject)));
            assertEquals(Set.of(1L, 2L), ids(indexer.getEnvelopeEqualityCandidates(unbounded, candidateIsSubject)));
            assertEquals(Set.of(), definite(indexer.getEnvelopeDisjointCandidates(unbounded, candidateIsSubject)));
        }
    }

    /**
     * Sources in twenty CRSs besides CRS84. Sources 1 to 4 lie inside the bound and beside it in EPSG:4326 and
     * EPSG:3857; the others lie inside it, one in each of eighteen UTM CRSs. The ids of the sources inside the
     * bound are added to the given set.
     */
    private static List<IndexGeometry> sourcesInManyCrss(Set<Long> inside) {
        List<IndexGeometry> sources = new ArrayList<>();
        for (String crs : List.of(EPSG_4326, MERCATOR)) {
            sources.add(geometry(literal(crs, "POINT(15.5 60.5)")));
            inside.add((long) sources.size());
            sources.add(geometry(literal(crs, "POINT(16.01 60.5)")));
        }
        for (int zone = 29; zone <= 37; zone++) {
            for (int hemisphere : new int[]{32600, 32700}) {
                String utm = "http://www.opengis.net/def/crs/EPSG/0/" + (hemisphere + zone);
                sources.add(geometry(literal(utm, "POINT(15.5 60.5)")));
                inside.add((long) sources.size());
            }
        }
        return sources;
    }

    /**
     * In UTM the CRS84 bound is a quadrilateral that does not fill its envelope. Sources 1 to 3 lie inside
     * rectangles that Jena finds within it and cannot be disjoint. Source 4 is far outside its envelope. Source 5
     * lies about a kilometre east of its slanted eastern edge, inside its envelope, and needs exact evaluation.
     * Sources 6 and later lie at the middle of the bound; with them there are enough candidates to certify it.
     */
    @Test
    public void subjectsInsideAnObliqueBoundAreRemovedFromItsDisjointCandidates() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("oblique");
        List<IndexGeometry> sources = new ArrayList<>(List.of(geometry(literal(UTM_33N, "POINT(15.4 60.4)")),
                geometry(literal(UTM_33N, "POINT(15.5 60.5)")), geometry(literal(UTM_33N, "POINT(15.6 60.6)")),
                geometry(literal(UTM_33N, "POINT(17 60.5)")), geometry(literal(UTM_33N, "POINT(16.02 60.95)"))));
        sources.addAll(Collections.nCopies(40, geometry(literal(UTM_33N, "POINT(15.5 60.5)"))));
        index(indexer, sources);
        IndexGeometry bound = geometry(BOUND);
        Set<Long> meetingTheEnvelope = new TreeSet<>(Set.of(1L, 2L, 3L, 5L));
        LongStream.rangeClosed(6, sources.size()).forEach(meetingTheEnvelope::add);

        assertEquals(meetingTheEnvelope, ids(indexer.getEnvelopeIntersections(bound, true)));
        assertEquals(Set.of(5L), ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
        assertEquals(Set.of(4L), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
    }

    /**
     * Jena evaluates a CRS84 subject in CRS84, against each object transformed there. A transformed object lies
     * inside its CRS84 index envelope, so objects of every CRS beside the bound are excluded by numeric ranges.
     */
    @Test
    public void objectsOfACrs84SubjectAreSelectedByTheirIndexEnvelopes() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("crs84-subject");
        index(indexer, insideAndBeside());

        assertEquals(Set.of(1L, 2L, 3L), ids(indexer.getEnvelopeIntersections(geometry(BOUND), false)));
    }

    /**
     * Jena transforms each object into the CRS of a projected subject. Only objects in that CRS are then selected
     * by their own envelopes; an object of another CRS beside the bound stays a candidate through its prefix cells.
     */
    @Test
    public void objectsOfAnotherCrsKeepPrefixCellLookupForAProjectedSubject() throws Exception {
        LuceneGeoIndexer indexer = createIndexer("projected-subject");
        index(indexer, insideAndBeside());

        Set<Long> candidates = ids(indexer.getEnvelopeIntersections(geometry(literal(MERCATOR, BOUND)), false));
        assertTrue(candidates.containsAll(Set.of(1L, 2L, 3L, 4L, 5L)));
        assertFalse("A Mercator object beside the Mercator bound is selected by its own envelope",
                candidates.contains(6L));
    }

    /**
     * Jena cannot place this three-dimensional EPSG:4979 bound near Brisbane in UTM 32N, so every exact evaluation
     * of a UTM 32N subject against it fails and no relation holds. Such a subject is no candidate of any phase.
     */
    @Test
    public void subjectsInACrsTheBoundCannotBePlacedInAreNoCandidates() throws Exception {
        IndexGeometry utmSubject = geometry("<http://www.opengis.net/def/crs/EPSG/0/32632> POINT(-100000000 0)");
        IndexGeometry bound = geometry("<http://www.opengis.net/def/crs/EPSG/0/4979> POINT Z(-27.47 153.03 55)");
        try {
            GeoSparqlPropertyRelation.SF_INTERSECTS.evaluate(utmSubject.sourceGeometryLiteral(),
                    bound.sourceGeometryLiteral());
            throw new AssertionError("The fixture needs a pair that Jena cannot evaluate");
        } catch (JenaGeoSparqlException expected) {
            // Jena cannot place the bound in the subject's CRS.
        }
        LuceneGeoIndexer indexer = createIndexer("unrelated");
        index(indexer, List.of(utmSubject));

        assertEquals(Set.of(), ids(indexer.getEnvelopeIntersections(bound, true)));
        assertEquals(Set.of(), ids(indexer.getEnvelopeEqualityCandidates(bound, true)));
        assertEquals(Set.of(), definite(indexer.getEnvelopeDisjointCandidates(bound, true)));
        assertEquals(Set.of(), ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, true)));
        assertEquals(Set.of(), ids(indexer.getDisjointTransformCleanupCandidates(bound, true)));
    }

    /** Equal geometries share an envelope, so equality candidates are the sources whose own envelope is the bound's. */
    @Test
    public void equalityCandidatesShareTheBoundsEnvelopeInTheirOwnCrs() throws Exception {
        String square = "POLYGON((1700000 8400000,1710000 8400000,1710000 8410000,1700000 8410000,1700000 8400000))";
        String sameEnvelope = "POLYGON((1700000 8400000,1710000 8400000,1710000 8410000,1705000 8405000,"
                + "1700000 8410000,1700000 8400000))";
        String larger = "POLYGON((1700000 8400000,1710001 8400000,1710001 8410000,1700000 8410000,1700000 8400000))";
        LuceneGeoIndexer indexer = createIndexer("equality");
        index(indexer, List.of(geometry("<" + MERCATOR + "> " + square), geometry("<" + MERCATOR + "> " + sameEnvelope),
                geometry("<" + MERCATOR + "> " + larger)));

        assertEquals(Set.of(1L, 2L),
                ids(indexer.getEnvelopeEqualityCandidates(geometry("<" + MERCATOR + "> " + square), true)));
        assertEquals(Set.of(1L, 2L),
                ids(indexer.getEnvelopeEqualityCandidates(geometry("<" + MERCATOR + "> " + square), false)));
    }

    /**
     * Jena rounds a transformed bound to its cleanup precision. A source that holds the rounded coordinates in its
     * own CRS equals a bound written with more decimal places, and is an equality candidate. A source one cleanup
     * step away is not equal and has another envelope.
     */
    @Test
    public void equalityCandidatesIncludeASourceEqualToTheBoundAfterCoordinateCleanup() throws Exception {
        IndexGeometry bound = geometry("LINESTRING(15.1234564 60.1234564,15.9 60.9)");
        IndexGeometry rounded = geometry("<" + EPSG_4326 + "> LINESTRING(60.123456 15.123456,60.9 15.9)");
        IndexGeometry oneStepAway = geometry("<" + EPSG_4326 + "> LINESTRING(60.123457 15.123456,60.9 15.9)");
        LuceneGeoIndexer indexer = createIndexer("equality-cleanup");
        index(indexer, List.of(rounded, oneStepAway));

        assertTrue(holds(GeoSparqlPropertyRelation.SF_EQUALS, rounded.sourceGeometryLiteral(),
                bound.sourceGeometryLiteral()));
        assertFalse(holds(GeoSparqlPropertyRelation.SF_EQUALS, oneStepAway.sourceGeometryLiteral(),
                bound.sourceGeometryLiteral()));
        assertEquals(Set.of(1L), ids(indexer.getEnvelopeEqualityCandidates(bound, true)));
    }

    /**
     * Every source that Jena relates to a bound is a candidate, every definite disjoint match is disjoint, and every
     * disjoint source is found in one of the disjoint phases, for bounds and sources in each pair of CRSs and for
     * both binding directions.
     */
    @Test
    public void lookupKeepsEverySourceJenaRelatesInEitherDirection() throws Exception {
        Random random = new Random(40);
        List<IndexGeometry> sources = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            sources.add(geometry(literal(SOURCE_CRSS.get(i % SOURCE_CRSS.size()), randomShape(random, 0.08))));
        }
        LuceneGeoIndexer indexer = createIndexer("differential");
        index(indexer, sources);

        for (int i = 0; i < 24; i++) {
            IndexGeometry bound = geometry(literal(SOURCE_CRSS.get(i % SOURCE_CRSS.size()), randomShape(random, 0.3)));
            for (boolean candidateIsSubject : new boolean[]{true, false}) {
                Set<Long> intersections = ids(indexer.getEnvelopeIntersections(bound, candidateIsSubject));
                Set<Long> equalities = ids(indexer.getEnvelopeEqualityCandidates(bound, candidateIsSubject));
                Set<Long> definite = definite(indexer.getEnvelopeDisjointCandidates(bound, candidateIsSubject));
                Set<Long> evaluated = ids(indexer.getEnvelopeDisjointUncertainCandidates(bound, candidateIsSubject));
                Set<Long> cleanup = ids(indexer.getDisjointTransformCleanupCandidates(bound, candidateIsSubject));
                Set<Long> evaluatedTwice = new TreeSet<>(evaluated);
                evaluatedTwice.retainAll(cleanup);
                assertEquals("sources evaluated in two disjoint phases", Set.of(), evaluatedTwice);
                evaluated.addAll(cleanup);
                for (int id = 1; id <= sources.size(); id++) {
                    SourceGeometryLiteral source = sources.get(id - 1).sourceGeometryLiteral();
                    SourceGeometryLiteral subject = candidateIsSubject ? source : bound.sourceGeometryLiteral();
                    SourceGeometryLiteral object = candidateIsSubject ? bound.sourceGeometryLiteral() : source;
                    String pair = subject.lexicalForm() + " / " + object.lexicalForm();
                    if (holds(GeoSparqlPropertyRelation.SF_INTERSECTS, subject, object)) {
                        assertTrue("intersecting pair omitted: " + pair, intersections.contains((long) id));
                    }
                    if (holds(GeoSparqlPropertyRelation.SF_EQUALS, subject, object)) {
                        assertTrue("equal pair omitted: " + pair, equalities.contains((long) id));
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

    /** A point, a line or a rectangle near 15.5 E 60.5 N, as CRS84 WKT. */
    private static String randomShape(Random random, double size) {
        double x = 15.2 + random.nextDouble() * 0.6;
        double y = 60.2 + random.nextDouble() * 0.6;
        double width = random.nextDouble() * size;
        double height = random.nextDouble() * size;
        return switch (random.nextInt(3)) {
            case 0 -> String.format(Locale.ROOT, "POINT(%.6f %.6f)", x, y);
            case 1 -> String.format(Locale.ROOT, "LINESTRING(%.6f %.6f,%.6f %.6f)", x, y, x + width, y + height);
            default -> String.format(Locale.ROOT, "POLYGON((%1$.6f %2$.6f,%3$.6f %2$.6f,%3$.6f %4$.6f,%1$.6f %4$.6f,"
                    + "%1$.6f %2$.6f))", x, y, x + width + 0.001, y + height + 0.001);
        };
    }

    private static List<IndexGeometry> insideAndBeside() {
        List<IndexGeometry> sources = new ArrayList<>();
        for (String wkt : List.of("POINT(15.5 60.5)", "POINT(16.01 60.5)")) {
            for (String crs : ALIGNED_CRSS) {
                sources.add(geometry(literal(crs, wkt)));
            }
        }
        return sources;
    }

    /** Writes CRS84 WKT in another CRS, with the coordinates Jena gives the geometry there. */
    private static String literal(String crsUri, String crs84Wkt) {
        if (CRS84.equals(crsUri)) {
            return crs84Wkt;
        }
        IndexGeometry crs84 = geometry(crs84Wkt);
        Envelope placed = crs84.evaluationEnvelope(crsUri, true).orElseThrow().envelope();
        boolean latitudeFirst = EPSG_4326.equals(crsUri);
        String low = coordinate(placed.getMinX(), placed.getMinY(), latitudeFirst);
        String high = coordinate(placed.getMaxX(), placed.getMaxY(), latitudeFirst);
        String body;
        if (crs84Wkt.startsWith("POINT")) {
            body = "POINT(" + low + ")";
        } else if (crs84Wkt.startsWith("LINESTRING")) {
            body = "LINESTRING(" + low + "," + high + ")";
        } else {
            String lowHigh = coordinate(placed.getMinX(), placed.getMaxY(), latitudeFirst);
            String highLow = coordinate(placed.getMaxX(), placed.getMinY(), latitudeFirst);
            body = "POLYGON((" + low + "," + highLow + "," + high + "," + lowHigh + "," + low + "))";
        }
        return "<" + crsUri + "> " + body;
    }

    private static String coordinate(double x, double y, boolean latitudeFirst) {
        return latitudeFirst ? y + " " + x : x + " " + y;
    }

    private LuceneGeoIndexer createIndexer(String name) throws Exception {
        GeoSparqlPlugin parent = new GeoSparqlPlugin();
        GeoSparqlConfig config = new GeoSparqlConfig();
        config.setPrefixTree(GeoSparqlConfig.PrefixTree.QUAD);
        config.setPrecision(11);
        config.updateCurrentSettings();
        parent.setConfig(config);
        parent.setLogger(LoggerFactory.getLogger(EvaluationCrsLookupTest.class));
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

    private static void delete(LuceneGeoIndexer indexer, long id) throws Exception {
        indexer.begin();
        indexer.indexGeometryList(id, entity -> "geometry-" + entity, List.of());
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
