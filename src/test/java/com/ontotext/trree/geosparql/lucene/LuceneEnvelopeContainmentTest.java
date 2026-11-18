package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.GeoSparqlPropertyRelation;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.spatial4j.context.SpatialContext;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that exact numeric envelope bounds remove candidates that are known to be non-disjoint from an
 * envelope-covering rectangular bound before source geometry decoding and exact evaluation.
 */
public class LuceneEnvelopeContainmentTest {
	private static final SpatialContext SPATIAL_CONTEXT = SpatialContext.GEO;
	private static final SpatialStrategy SPATIAL_STRATEGY = new RecursivePrefixTreeStrategy(
			new QuadPrefixTree(SPATIAL_CONTEXT, 11), LuceneGeoDocumentSchema.FIELD_SPATIAL_PREFIX);
	private static final String EPSG_32634 = "http://www.opengis.net/def/crs/EPSG/0/32634";

	@Test
	public void exactEnvelopeFieldsCoverEveryNonEmptyGeometryTypeButAreNotStored() throws Exception {
		List<IndexGeometry> geometries = List.of(
				geometry("POINT(1 1)"),
				geometry("LINESTRING(2 2,3 3)"),
				geometry("POLYGON((4 4,4 5,5 5,5 4,4 4))"),
				geometry("GEOMETRYCOLLECTION(POINT(6 6),LINESTRING(7 7,8 8))"),
				geometry("GEOMETRYCOLLECTION EMPTY"));

		try (Directory directory = new ByteBuffersDirectory();
				IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
			for (int index = 0; index < geometries.size(); index++) {
				writeCandidate(writer, index + 1L, geometries.get(index));
			}
			writer.commit();

			try (DirectoryReader reader = DirectoryReader.open(directory)) {
				IndexSearcher searcher = new IndexSearcher(reader);
				assertEquals(Set.of(1L, 2L, 3L, 4L), collectEntityIds(searcher,
						LuceneGeoDocumentSchema.envelopeIntersectsQuery(new Envelope(-180, 180, -90, 90))));
				for (int documentId = 0; documentId < reader.maxDoc(); documentId++) {
					Document stored = reader.document(documentId);
					assertFalse(stored.getFields().stream().anyMatch(field ->
							field.name().equals(LuceneGeoDocumentSchema.FIELD_SOURCE_ENVELOPE)
									|| field.name().equals(LuceneGeoDocumentSchema.FIELD_INDEX_ENVELOPE)));
				}
			}
		}
	}

	/**
	 * Within keeps closed boundaries and treats signed zeros as equal, and beyond matches every other envelope with
	 * one: together they partition the envelopes, unbounded ones included.
	 */
	@Test
	public void containedEnvelopeQueriesIncludeClosedBoundariesAndEqualSignedZeros() throws Exception {
		List<Envelope> envelopes = List.of(
				new Envelope(0, 1, 0, 1), new Envelope(0, 0, 0, 0),
				new Envelope(-0.0, -0.0, 0, 0), new Envelope(0, 0, -0.0, -0.0),
				new Envelope(-0.0, -0.0, -0.0, -0.0),
				new Envelope(Math.nextDown(0.0), 0, 0, 0),
				new Envelope(0, Math.nextUp(1.0), 0, 1),
				new Envelope(0, 1, Math.nextDown(0.0), 1),
				new Envelope(0, 1, 0, Math.nextUp(1.0)),
				new Envelope(-1, 2, -1, 2),
				IndexGeometry.unboundedEnvelope());
		try (Directory directory = new ByteBuffersDirectory();
				IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
			for (Envelope envelope : envelopes) {
				Document document = new Document();
				document.add(new DoublePoint(LuceneGeoDocumentSchema.FIELD_SOURCE_ENVELOPE, envelope.getMinX(),
						envelope.getMaxX(), envelope.getMinY(), envelope.getMaxY()));
				writer.addDocument(document);
			}
			writer.addDocument(new Document());
			writer.commit();
			try (DirectoryReader reader = DirectoryReader.open(directory)) {
				IndexSearcher searcher = new IndexSearcher(reader);
				for (Envelope bound : List.of(new Envelope(0, 1, 0, 1),
						new Envelope(-0.0, -0.0, -0.0, -0.0), new Envelope(-1, 2, -1, 2))) {
					Set<Integer> expected = new HashSet<>();
					for (int i = 0; i < envelopes.size(); i++) {
						if (bound.covers(envelopes.get(i))) {
							expected.add(i);
						}
					}
					Set<Integer> beyond = new HashSet<>();
					for (int i = 0; i < envelopes.size(); i++) {
						if (!expected.contains(i)) {
							beyond.add(i);
						}
					}
					assertEquals(bound.toString(), expected,
							matches(searcher, LuceneGeoDocumentSchema.sourceEnvelopeWithinQuery(bound)));
					assertEquals(bound.toString(), beyond,
							matches(searcher, LuceneGeoDocumentSchema.sourceEnvelopeBeyondQuery(bound)));
				}
			}
		}
	}

	@Test
	public void ineligibleBoundShapesCannotUseContainmentAsANonMatchProof() {
		IndexGeometry rectangle = geometry("POLYGON((0 0,0 10,10 10,10 0,0 0))");
		IndexGeometry boundWithHole = geometry(
				"POLYGON((0 0,0 10,10 10,10 0,0 0),(4 4,6 4,6 6,4 6,4 4))");
		IndexGeometry rotated = geometry("POLYGON((0 5,5 10,10 5,5 0,0 5))");
		IndexGeometry pointInHole = geometry("POINT(5 5)");
		IndexGeometry collection = geometry(
				"GEOMETRYCOLLECTION(POLYGON((0 0,0 10,10 10,10 0,0 0)))");
		IndexGeometry projectedRectangle = geometry("<" + EPSG_32634 + "> "
				+ "POLYGON((799990 4589770,799990 4589790,800010 4589790,"
				+ "800010 4589770,799990 4589770))");
		IndexGeometry invalidPolygon = geometry("POLYGON((0 0,10 10,0 10,10 0,0 0))");
		IndexGeometry selfOverlapping = geometry(
				"POLYGON((0 0,10 0,10 10,0 10,0 0,10 0,10 10,0 10,0 0))");
		// JTS finds that this polygon fills its envelope once products of its coordinate differences underflow.
		double unit = Math.scalb(1.0, -540);
		IndexGeometry underflowing = geometry("POLYGON((" + 9 * unit + " " + 13 * unit + "," + unit + " "
				+ 10 * unit + ",0 " + 6 * unit + "," + 3 * unit + " " + 4 * unit + "," + 8 * unit + " " + 7 * unit
				+ "," + 11 * unit + " " + 5 * unit + "," + 9 * unit + " " + 13 * unit + "))");
		IndexGeometry empty = geometry("POLYGON EMPTY");

		assertTrue(rectangle.isEnvelopeCoveringRectangle());
		assertFalse(boundWithHole.isEnvelopeCoveringRectangle());
		assertFalse(rotated.isEnvelopeCoveringRectangle());
		assertFalse(collection.isEnvelopeCoveringRectangle());
		assertFalse(projectedRectangle.isEnvelopeCoveringRectangle());
		assertFalse(invalidPolygon.isEnvelopeCoveringRectangle());
		assertFalse(selfOverlapping.isEnvelopeCoveringRectangle());
		assertFalse(underflowing.isEnvelopeCoveringRectangle());
		assertFalse(empty.isEnvelopeCoveringRectangle());
		assertTrue(GeoSparqlPropertyRelation.SF_DISJOINT.evaluate(
				pointInHole.sourceGeometryLiteral(), boundWithHole.sourceGeometryLiteral()));
	}

	private static Set<Integer> matches(IndexSearcher searcher, Query query) throws IOException {
		Set<Integer> documents = new HashSet<>();
		for (ScoreDoc hit : searcher.search(query, searcher.getIndexReader().maxDoc()).scoreDocs) {
			documents.add(hit.doc);
		}
		return documents;
	}

	private static void writeCandidate(IndexWriter writer, long entityId, IndexGeometry geometry)
			throws Exception {
		Document document = LuceneGeoDocumentSchema.toDocument(
				entityId, geometry, SPATIAL_STRATEGY, SPATIAL_CONTEXT);
		writer.addDocument(document);
	}

	private static Set<Long> collectEntityIds(IndexSearcher searcher, Query query) throws Exception {
		Set<Long> entityIds = new HashSet<>();
		for (ScoreDoc scoreDoc : searcher.search(query, 100).scoreDocs) {
			entityIds.add(searcher.doc(scoreDoc.doc)
					.getField(LuceneGeoDocumentSchema.FIELD_ID).numericValue().longValue());
		}
		return entityIds;
	}

	private static IndexGeometry geometry(String wkt) {
		return TestIndexGeometries.fromWkt(wkt);
	}

}
