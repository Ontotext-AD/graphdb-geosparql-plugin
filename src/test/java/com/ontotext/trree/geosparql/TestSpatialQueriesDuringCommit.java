package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies spatial queries from another connection while a GraphDB transaction has published its Lucene commit and
 * GraphDB has not yet completed the RDF transaction.
 */
public class TestSpatialQueriesDuringCommit extends AbstractGeoSparqlPluginTest {
	private static final String PREFIXES = "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/>\n";
	private static final int BATCH_SIZE = 3;
	private static final long TIMEOUT_SECONDS = 30;

	@Test
	public void queryFromAnotherConnectionSucceedsWhileLuceneCommitAwaitsGraphDbCompletion() throws Exception {
		enablePlugin();
		update(connection, "INSERT DATA { ex:zone a geo:Geometry ; "
				+ "geo:asWKT \"POLYGON((120 0, 130 0, 130 10, 120 10, 120 0))\"^^geo:wktLiteral }");
		update(connection, batchInsert(1));
		GeoSparqlPlugin plugin = activePlugin();
		PausingCommitIndexer indexer = new PausingCommitIndexer(plugin);
		indexer.initialize();
		plugin.indexer = indexer;

		ExecutorService writer = Executors.newSingleThreadExecutor();
		try {
			Future<?> batchCommit = writer.submit(() -> {
				try (RepositoryConnection writeConnection = repository.getConnection()) {
					update(writeConnection, batchInsert(2));
				}
				return null;
			});
			assertTrue("The Lucene commit was not published.",
					indexer.commitPublished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			try (RepositoryConnection readConnection = repository.getConnection()) {
				assertEquals(List.of(), select(readConnection,
						"SELECT ?batch (COUNT(?g) AS ?seen) WHERE { ?g geo:sfWithin ex:zone ; ex:batch ?batch } "
								+ "GROUP BY ?batch HAVING (COUNT(?g) != " + BATCH_SIZE + ")", "batch"));
				assertEquals(List.of(String.valueOf(BATCH_SIZE)), select(readConnection,
						"SELECT (COUNT(?g) AS ?n) WHERE { ?g geo:sfWithin ex:zone ; ex:batch ?batch }", "n"));
				assertEquals(withinZone(1), select(readConnection,
						"SELECT ?g WHERE { ?g geo:sfWithin ex:zone } ORDER BY ?g", "g"));
			}
			indexer.releaseCommit.countDown();
			batchCommit.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			indexer.releaseCommit.countDown();
			writer.shutdownNow();
		}

		try (RepositoryConnection readConnection = repository.getConnection()) {
			assertEquals(List.of(String.valueOf(2 * BATCH_SIZE)), select(readConnection,
					"SELECT (COUNT(?g) AS ?n) WHERE { ?g geo:sfWithin ex:zone ; ex:batch ?batch }", "n"));
			assertEquals(withinZone(2), select(readConnection,
					"SELECT ?g WHERE { ?g geo:sfWithin ex:zone } ORDER BY ?g", "g"));
		}
	}

	/** Returns the zone and the points of batches 1 to {@code batches}, in IRI order. */
	private static List<String> withinZone(int batches) {
		List<String> iris = new ArrayList<>();
		for (int batch = 1; batch <= batches; batch++) {
			for (int i = 0; i < BATCH_SIZE; i++) {
				iris.add("http://example.com/b" + batch + "p" + i);
			}
		}
		iris.add("http://example.com/zone");
		return iris;
	}

	/** Inserts points inside the zone, each with its batch number, under subject IRIs unique to the batch. */
	private static String batchInsert(int batch) {
		String[] points = {"POINT(123.4 5.6)", "POINT(127.1 2.9)", "POINT(125.0 8.0)"};
		StringBuilder update = new StringBuilder("INSERT DATA { ");
		for (int i = 0; i < BATCH_SIZE; i++) {
			update.append("ex:b").append(batch).append('p').append(i)
					.append(" ex:batch ").append(batch)
					.append(" ; geo:asWKT \"").append(points[i]).append("\"^^geo:wktLiteral . ");
		}
		return update.append('}').toString();
	}

	private static void update(RepositoryConnection updateConnection, String update) {
		updateConnection.begin();
		updateConnection.prepareUpdate(QueryLanguage.SPARQL, PREFIXES + update).execute();
		updateConnection.commit();
	}

	private static List<String> select(RepositoryConnection queryConnection, String query, String binding) {
		List<String> values = new ArrayList<>();
		try (TupleQueryResult result = queryConnection.prepareTupleQuery(QueryLanguage.SPARQL, PREFIXES + query)
				.evaluate()) {
			while (result.hasNext()) {
				BindingSet bindings = result.next();
				values.add(bindings.getValue(binding).stringValue());
			}
		}
		return values;
	}

	/** Holds the GraphDB commit after the Lucene commit is published until the test releases it. */
	private static final class PausingCommitIndexer extends LuceneGeoIndexer {
		private final CountDownLatch commitPublished = new CountDownLatch(1);
		private final CountDownLatch releaseCommit = new CountDownLatch(1);

		private PausingCommitIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		public void commit() throws Exception {
			super.commit();
			commitPublished.countDown();
			if (!releaseCommit.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("The paused commit was not released.");
			}
		}
	}
}
