package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.CandidateBoundsKind;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.IndexGeometryFixtures;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.junit.Rule;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class LuceneEnvelopeEqualityTest {
	private static final String EPSG_4326 = "http://www.opengis.net/def/crs/EPSG/0/4326";

	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void nativeEqualityPrunesDifferentEnvelopesAndExactEvaluationRejectsFalsePositives() throws Exception {
		List<IndexGeometry> sources = List.of(geometry("LINESTRING(0 0,2 2)"),
				geometry("LINESTRING(2 2,0 0)"), geometry("POINT(1 1)"),
				geometry("POLYGON((-1 -1,-1 3,3 3,3 -1,-1 -1))"), geometry("LINESTRING(0 2,2 0)"));
		LuceneGeoIndexer indexer = index(sources);
		List<CandidateEntity> candidates = collect(indexer.getEnvelopeEqualityCandidates(sources.get(0), false));

		assertEquals(List.of(1L, 2L, 5L), ids(candidates));
		assertEquals(List.of(1L, 2L), candidates.stream()
				.filter(candidate -> GeoSparqlPropertyRelation.SF_EQUALS.evaluate(
						candidate.matchingSourceGeometryLiterals(), sources.get(0).sourceGeometryLiteral()))
				.map(CandidateEntity::entityId).toList());
	}

	@Test
	public void nativeEqualityRetainsBothSignedZerosOnDegenerateEnvelopes() throws Exception {
		List<IndexGeometry> sources = List.of(geometry("POINT(0.0 0.0)"), geometry("POINT(-0.0 0.0)"),
				geometry("POINT(0.0 -0.0)"), geometry("POINT(-0.0 -0.0)"), geometry("POINT(0.0 0.001)"));
		LuceneGeoIndexer indexer = index(sources);
		for (IndexGeometry bound : sources.subList(0, 4)) {
			assertEquals(List.of(1L, 2L, 3L, 4L), ids(collect(indexer.getEnvelopeEqualityCandidates(bound, false))));
		}
	}

	@Test
	public void nativeBoundsRetainTransformedAndWorldFallbackCandidates() throws Exception {
		IndexGeometry bound = geometry("LINESTRING(0 0,2 2)");
		SourceGeometryLiteral fallback = SourceGeometryLiteral.fromWkt(
				"<" + EPSG_4326 + "> LINESTRING(0 0,2 2)");
		LuceneGeoIndexer indexer = index(List.of(bound,
				geometry("<" + EPSG_4326 + "> LINESTRING(0 0,2 2)"),
				IndexGeometryFixtures.withIndexEnvelope(fallback, new Envelope(-180, 180, -90, 90),
						CandidateBoundsKind.WORLD_FALLBACK, "Transform cannot be represented by one rectangle"),
				IndexGeometryFixtures.withIndexEnvelope(bound.sourceGeometryLiteral(),
						new Envelope(-180, 180, -90, 90), CandidateBoundsKind.WORLD_FALLBACK,
						"Transform cannot be represented by one rectangle"),
				geometry("LINESTRING(-1 -1,3 3)")));

		assertEquals(List.of(1L, 2L, 3L, 4L), ids(collect(indexer.getEnvelopeEqualityCandidates(bound, false))));
	}

	/**
	 * A subject in another CRS than CRS84 is evaluated against each object transformed into its CRS, so objects of
	 * another CRS are equality candidates wherever the prefix cells of the index envelopes meet. The CRS84 line lies
	 * beside the bound's index envelope, in one prefix cell of the default grid with the bound's end point.
	 */
	@Test
	public void transformedSubjectsRetainObjectsOfAnotherCrsInTheirPrefixCells() throws Exception {
		IndexGeometry bound = geometry("<" + EPSG_4326 + "> LINESTRING(0 0,2 2)");
		LuceneGeoIndexer indexer = index(List.of(bound,
				geometry("LINESTRING(2.001 2.001,2.002 2.002)"),
				geometry("<" + EPSG_4326 + "> LINESTRING(2.001 2.001,2.002 2.002)")));

		assertEquals(List.of(1L, 2L), ids(collect(indexer.getEnvelopeEqualityCandidates(bound, false))));
	}

	@Test
	public void transformedBoundsRetainConservativeIntersectionCandidates() throws Exception {
		IndexGeometry bound = geometry("<" + EPSG_4326 + "> LINESTRING(0 0,2 2)");
		LuceneGeoIndexer indexer = index(List.of(geometry("LINESTRING(0 0,2 2)"), bound,
				geometry("LINESTRING(-1 -1,3 3)"), geometry("POINT(20 20)")));

		assertEquals(List.of(1L, 2L, 3L), ids(collect(indexer.getEnvelopeEqualityCandidates(bound, false))));
	}

	private LuceneGeoIndexer index(List<IndexGeometry> sources) throws Exception {
		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.updateCurrentSettings();
		parent.setConfig(config);
		parent.setLogger(LoggerFactory.getLogger(LuceneEnvelopeEqualityTest.class));
		parent.setDataDir(tmpFolder.newFolder());
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(parent);
		indexer.initialize();
		indexer.begin();
		for (int i = 0; i < sources.size(); i++) {
			indexer.indexGeometryList(i + 1L, id -> "geometry-" + id, List.of(sources.get(i)));
		}
		indexer.commit();
		indexer.complete();
		return indexer;
	}

	private static List<CandidateEntity> collect(CloseableIterator<CandidateEntity> iterator) throws Exception {
		try (iterator) {
			List<CandidateEntity> candidates = new ArrayList<>();
			while (iterator.hasNext()) {
				candidates.add(iterator.next());
			}
			return candidates;
		}
	}

	private static List<Long> ids(List<CandidateEntity> candidates) {
		return candidates.stream().map(CandidateEntity::entityId).toList();
	}

	private static IndexGeometry geometry(String wkt) {
		return TestIndexGeometries.fromWkt(wkt);
	}
}
