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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class LuceneGeoTransactionLifecycleTest {
	@Rule
	public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Test
	public void postCommitAbortRestoresPreviousGeometryAfterRestart() throws Exception {
		File dataDir = tmpFolder.newFolder("post-commit-abort");
		LuceneGeoIndexer indexer = createIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry", List.of(geometry("POINT(2 2)")));
		indexer.commit();
		assertPendingTransactionFailure(indexer);

		indexer.rollback();
		assertEquals("POINT(1 1)", onlySource(indexer).lexicalForm());

		LuceneGeoIndexer restarted = createIndexer(dataDir);
		assertEquals("POINT(1 1)", onlySource(restarted).lexicalForm());
	}

	@Test
	public void successfulCompletionKeepsNewGeometryAndReleasesPreviousCommit() throws Exception {
		File dataDir = tmpFolder.newFolder("successful-completion");
		LuceneGeoIndexer indexer = createIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		commit(indexer, "POINT(2 2)");
		indexer.complete();

		assertEquals("POINT(2 2)", onlySource(createIndexer(dataDir)).lexicalForm());
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir.toPath()))) {
			assertEquals(1, DirectoryReader.listCommits(directory).size());
		}
	}

	@Test
	public void postCommitAbortRestoresCommitlessIndexToLogicalEmptyState() throws Exception {
		File dataDir = tmpFolder.newFolder("commitless-abort");
		LuceneGeoIndexer indexer = createIndexer(dataDir);

		commit(indexer, "POINT(2 2)");
		assertPendingTransactionFailure(indexer);
		indexer.rollback();

		assertEmpty(createIndexer(dataDir));
	}

	@Test
	public void abortBeforeLuceneCommitUsesWriterRollback() throws Exception {
		File dataDir = tmpFolder.newFolder("pre-commit-abort");
		LuceneGeoIndexer indexer = createIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry", List.of(geometry("POINT(2 2)")));
		indexer.rollback();

		assertEquals("POINT(1 1)", onlySource(createIndexer(dataDir)).lexicalForm());
	}

	@Test
	public void startupWithPendingGraphDbTransactionFailsClosed() throws Exception {
		File dataDir = tmpFolder.newFolder("pending-startup");
		LuceneGeoIndexer indexer = createIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();
		commit(indexer, "POINT(2 2)");

		LuceneGeoIndexer restarted = createIndexer(dataDir);
		PluginException failure = assertThrows(PluginException.class,
				() -> restarted.getSourceGeometryLiteralsFor(1L));

		assertTrue(failure.getMessage().contains("pending GraphDB transaction"));
		assertTrue(failure.getMessage().contains("force-reindex"));
		indexer.rollback();
	}

	@Test
	public void pendingRecoveryCannotBeCompletedWithoutForceReindex() throws Exception {
		File dataDir = tmpFolder.newFolder("pending-recovery-no-op");
		LuceneGeoIndexer indexer = createIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();
		commit(indexer, "POINT(2 2)");

		LuceneGeoIndexer restarted = createIndexer(dataDir);
		restarted.begin();
		PluginException failure = assertThrows(PluginException.class, restarted::commit);
		assertTrue(failure.getMessage().contains("pending GraphDB transaction"));
		restarted.rollback();
		assertPendingTransactionFailure(restarted);
	}

	@Test
	public void luceneCommitFailureLeavesPreviousGeometryVisible() throws Exception {
		File dataDir = tmpFolder.newFolder("commit-failure");
		FailingCommitIndexer indexer = createFailingIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();
		long originalGeneration = latestCommit(dataDir).getGeneration();

		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry", List.of(geometry("POINT(2 2)")));
		indexer.failCommit = true;
		assertThrows(IOException.class, indexer::commit);
		indexer.rollback();

		assertEquals("POINT(1 1)", onlySource(createIndexer(dataDir)).lexicalForm());
		assertEquals(originalGeneration, latestCommit(dataDir).getGeneration());
	}

	@Test
	public void failedPostCommitRestorationBlocksReadsBeforeAndAfterRestart() throws Exception {
		File dataDir = tmpFolder.newFolder("failed-post-commit-restoration");
		FailingRestoreIndexer indexer = createFailingRestoreIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();

		commit(indexer, "POINT(2 2)");
		indexer.failRestore = true;
		assertThrows(IOException.class, indexer::rollback);

		assertPendingTransactionFailure(indexer);
		assertPendingTransactionFailure(createIndexer(dataDir));
	}

	@Test
	public void failedSnapshotAcquisitionReleasesWriterLock() throws Exception {
		File dataDir = tmpFolder.newFolder("failed-snapshot-acquisition");
		FailingSnapshotIndexer indexer = createFailingSnapshotIndexer(dataDir);
		commit(indexer, "POINT(1 1)");
		indexer.complete();
		indexer.failSnapshot = true;

		assertThrows(IOException.class, indexer::begin);
		assertFalse(indexer.isTransactionActive());

		LuceneGeoIndexer reopened = createIndexer(dataDir);
		reopened.begin();
		reopened.rollback();
	}

	private LuceneGeoIndexer createIndexer(File dataDir) throws Exception {
		LuceneGeoIndexer indexer = new LuceneGeoIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private FailingCommitIndexer createFailingIndexer(File dataDir) throws Exception {
		FailingCommitIndexer indexer = new FailingCommitIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private FailingRestoreIndexer createFailingRestoreIndexer(File dataDir) throws Exception {
		FailingRestoreIndexer indexer = new FailingRestoreIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private FailingSnapshotIndexer createFailingSnapshotIndexer(File dataDir) throws Exception {
		FailingSnapshotIndexer indexer = new FailingSnapshotIndexer(createParent(dataDir));
		indexer.initialize();
		return indexer;
	}

	private GeoSparqlPlugin createParent(File dataDir) {
		GeoSparqlPlugin parent = new GeoSparqlPlugin();
		GeoSparqlConfig config = new GeoSparqlConfig();
		config.setEnabled(true);
		parent.setConfig(config);
		parent.setLogger(LoggerFactory.getLogger(LuceneGeoTransactionLifecycleTest.class));
		parent.setDataDir(dataDir);
		return parent;
	}

	private void commit(LuceneGeoIndexer indexer, String wkt) throws Exception {
		indexer.begin();
		indexer.indexGeometryList(1L, id -> "geometry", List.of(geometry(wkt)));
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

	private void assertEmpty(LuceneGeoIndexer indexer) throws Exception {
		try (CloseableIterator<SourceGeometryLiteral> sources = indexer.getSourceGeometryLiteralsFor(0)) {
			assertFalse(sources.hasNext());
		}
	}

	private void assertPendingTransactionFailure(LuceneGeoIndexer indexer) {
		PluginException failure = assertThrows(PluginException.class,
				() -> indexer.getSourceGeometryLiteralsFor(1L));
		assertTrue(failure.getMessage().contains("pending GraphDB transaction"));
	}

	private IndexCommit latestCommit(File dataDir) throws Exception {
		try (FSDirectory directory = FSDirectory.open(GeoSparqlConfig.resolveIndexPath(dataDir.toPath()))) {
			List<IndexCommit> commits = DirectoryReader.listCommits(directory);
			return commits.get(commits.size() - 1);
		}
	}

	private static final class FailingCommitIndexer extends LuceneGeoIndexer {
		private boolean failCommit;

		private FailingCommitIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		void closeIndexWriter() throws IOException {
			if (failCommit) {
				throw new IOException("Simulated commit failure");
			}
			super.closeIndexWriter();
		}
	}

	private static final class FailingRestoreIndexer extends LuceneGeoIndexer {
		private boolean failRestore;

		private FailingRestoreIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		protected void restorePreTransactionCommit() throws IOException {
			if (failRestore) {
				throw new IOException("Simulated restoration failure");
			}
			super.restorePreTransactionCommit();
		}
	}

	private static final class FailingSnapshotIndexer extends LuceneGeoIndexer {
		private boolean failSnapshot;

		private FailingSnapshotIndexer(GeoSparqlPlugin parent) {
			super(parent);
		}

		@Override
		IndexCommit snapshotExistingCommit() throws IOException {
			if (failSnapshot) {
				throw new IOException("Simulated snapshot failure");
			}
			return super.snapshotExistingCommit();
		}
	}
}
