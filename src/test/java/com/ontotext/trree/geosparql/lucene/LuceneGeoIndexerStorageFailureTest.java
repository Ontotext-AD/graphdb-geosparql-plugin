package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.apache.lucene.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Lucene mutation and persistence failures abort index writes even when {@code ignoreErrors=true}.
 *
 * <p>Provenance: <a href="https://github.com/Kurrawong/graphdb-geosparql-plugin/issues/5">#5</a>.
 * {@code ignoreErrors} remains limited to source-data conversion failures such as malformed literals and
 * unsupported CRS values. Skip coverage for those source-data failures remains in
 * {@link com.ontotext.trree.geosparql.TestPluginIgnoreErrors}.
 */
public class LuceneGeoIndexerStorageFailureTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void ignoreErrorsDoesNotSkipEntityDeletionFailure() throws Exception {
		assertMutationFailureRestoresPreviousGeometry("delete-failure", indexer -> indexer.failDelete = true,
				new IOException("Simulated Lucene deleteDocuments failure"));
	}

	@Test
	public void ignoreErrorsDoesNotSkipReplacementAdditionFailure() throws Exception {
		assertMutationFailureRestoresPreviousGeometry("add-failure", indexer -> indexer.failAdd = true,
				new IOException("Simulated Lucene addDocuments failure"));
	}

	@Test
	public void ignoreErrorsDoesNotSkipStreamingReindexAdditionFailure() throws Exception {
		File dataDir = tmpFolder.newFolder("append-failure");
		FailingMutationIndexer indexer = createFailingIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		indexer.begin();
		indexer.freshIndex();
		IOException appendFailure = new IOException("Simulated Lucene addDocument failure");
		indexer.failAppend = true;
		indexer.injectedFailure = appendFailure;

		PluginException failure = assertThrows(PluginException.class,
				() -> indexer.appendGeometry(1L, id -> "http://example.com/geometry", geometry("POINT(2 2)")));
		assertStorageFailure(failure, appendFailure);

		indexer.rollback();
		assertEquals("POINT(1 1)", onlySource(createIndexer(dataDir)).lexicalForm());
	}

	@Test
	public void ignoreErrorsDoesNotSkipLuceneCommitFailure() throws Exception {
		File dataDir = tmpFolder.newFolder("commit-failure");
		FailingMutationIndexer indexer = createFailingIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "http://example.com/geometry", List.of(geometry("POINT(2 2)")));
		IOException commitFailure = new IOException("Simulated Lucene commit failure");
		indexer.failCommit = true;
		indexer.injectedFailure = commitFailure;

		IOException thrown = assertThrows(IOException.class, indexer::commit);
		assertSame(commitFailure, thrown);

		indexer.rollback();
		assertEquals("POINT(1 1)", onlySource(createIndexer(dataDir)).lexicalForm());
	}

	private void assertMutationFailureRestoresPreviousGeometry(String folder,
			Consumer<FailingMutationIndexer> fail, IOException injectedFailure)
			throws Exception {
		File dataDir = tmpFolder.newFolder(folder);
		FailingMutationIndexer indexer = createFailingIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		indexer.begin();
		fail.accept(indexer);
		indexer.injectedFailure = injectedFailure;

		PluginException failure = assertThrows(PluginException.class,
				() -> indexer.indexGeometryList(1L, id -> "http://example.com/geometry",
						List.of(geometry("POINT(2 2)"))));
		assertStorageFailure(failure, injectedFailure);

		indexer.rollback();
		assertEquals("POINT(1 1)", onlySource(createIndexer(dataDir)).lexicalForm());
	}

	private void assertStorageFailure(PluginException failure, IOException expectedCause) {
		assertTrue(failure.getMessage().contains("http://example.com/geometry"));
		assertFalse(failure.getMessage().contains("ignoreErrors"));
		assertSame(expectedCause, failure.getCause());
	}

	private FailingMutationIndexer createFailingIndexer(File dataDir) throws Exception {
		FailingMutationIndexer indexer = new FailingMutationIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private LuceneGeoIndexer createIndexer(File dataDir) throws Exception {
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private GeoSparqlPlugin createParent(File dataDir) {
		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setEnabled(true);
		config.setIgnoreErrors(true);
		parent.setConfig(config);
		parent.setLogger(LoggerFactory.getLogger(LuceneGeoIndexerStorageFailureTest.class));
		parent.setDataDir(dataDir);
		return parent;
	}

	private void commit(LuceneGeoIndexer indexer, String wkt) throws Exception {
		indexer.begin();
		indexer.indexGeometryList(1L, id -> "http://example.com/geometry", List.of(geometry(wkt)));
		indexer.commit();
	}

	private IndexGeometry geometry(String wkt) {
		return TestIndexGeometries.fromWkt(wkt);
	}

	private SourceGeometryLiteral onlySource(LuceneGeoIndexer indexer) throws Exception {
		try (CloseableIterator<SourceGeometryLiteral> sources = indexer.getSourceGeometryLiteralsFor(1L)) {
			assertTrue(sources.hasNext());
			SourceGeometryLiteral source = sources.next();
			assertFalse(sources.hasNext());
			return source;
		}
	}

	private static final class FailingMutationIndexer extends LuceneGeoIndexer {
		private boolean failDelete;
		private boolean failAdd;
		private boolean failAppend;
		private boolean failCommit;
		private IOException injectedFailure;

		private FailingMutationIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		void deleteEntityDocuments(long subject) throws IOException {
			if (failDelete) {
				throw injectedFailure;
			}
			super.deleteEntityDocuments(subject);
		}

		@Override
		void addEntityDocuments(List<Document> documents) throws IOException {
			if (failAdd) {
				throw injectedFailure;
			}
			super.addEntityDocuments(documents);
		}

		@Override
		void addEntityDocument(Document document) throws IOException {
			if (failAppend) {
				throw injectedFailure;
			}
			super.addEntityDocument(document);
		}

		@Override
		void closeIndexWriter() throws IOException {
			if (failCommit) {
				throw injectedFailure;
			}
			super.closeIndexWriter();
		}
	}
}
