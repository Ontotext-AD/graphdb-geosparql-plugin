package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.EnvelopeDisjointCandidate;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Streams lightweight source-document metadata for envelope-disjoint Lucene matches in one pass.
 *
 * <p>Only the entity id and source topological dimension are loaded. Source WKB and literal metadata remain unread.
 * Results follow Lucene document order and are not sorted. The iterator owns the searcher's reader and closes it on
 * exhaustion, failure, or explicit close.
 */
final class LuceneEnvelopeDisjointCandidateIterator implements CloseableIterator<EnvelopeDisjointCandidate> {
	private final IndexSearcher searcher;
	private final Query query;
	private List<LeafReaderContext> leaves;
	private Weight weight;
	private int leafIndex;
	private LeafReaderContext currentLeaf;
	private DocIdSetIterator currentMatches;
	private NumericDocValues currentEntityIds;
	private NumericDocValues currentSourceTopologicalDimensions;
	private Bits currentLiveDocs;
	private EnvelopeDisjointCandidate next;
	private boolean initialized;
	private boolean closed;

	LuceneEnvelopeDisjointCandidateIterator(IndexSearcher searcher, Query query) {
		this.searcher = searcher;
		this.query = query;
	}

	@Override
	public boolean hasNext() {
		if (closed) {
			return false;
		}
		if (next != null) {
			return true;
		}
		try {
			next = loadNextCandidate();
			if (next == null) {
				close();
				return false;
			}
			return true;
		} catch (IOException e) {
			PluginException failure =
					new PluginException("Unable to stream envelope-disjoint candidate metadata.", e);
			closeAfterFailure(failure);
			throw failure;
		} catch (RuntimeException e) {
			closeAfterFailure(e);
			throw e;
		}
	}

	@Override
	public EnvelopeDisjointCandidate next() {
		if (!hasNext()) {
			throw new NoSuchElementException("No more envelope-disjoint candidates.");
		}
		EnvelopeDisjointCandidate result = next;
		next = null;
		return result;
	}

	@Override
	public void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		next = null;
		currentLeaf = null;
		currentMatches = null;
		currentEntityIds = null;
		currentSourceTopologicalDimensions = null;
		currentLiveDocs = null;
		searcher.getIndexReader().close();
	}

	private EnvelopeDisjointCandidate loadNextCandidate() throws IOException {
		initialize();
		while (true) {
			if (currentMatches == null && !openNextMatchingLeaf()) {
				return null;
			}

			int documentId = currentMatches.nextDoc();
			if (documentId == DocIdSetIterator.NO_MORE_DOCS) {
				clearCurrentLeaf();
				continue;
			}
			if (currentLiveDocs != null && !currentLiveDocs.get(documentId)) {
				continue;
			}

			long entityId = requiredNumericValue(currentEntityIds, documentId,
					LuceneGeoDocumentSchema.FIELD_ID);
			int sourceTopologicalDimension = Math.toIntExact(requiredNumericValue(
					currentSourceTopologicalDimensions, documentId,
					LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION));
			return new EnvelopeDisjointCandidate(entityId, sourceTopologicalDimension);
		}
	}

	private void initialize() throws IOException {
		if (initialized) {
			return;
		}
		Query rewrittenQuery = searcher.rewrite(query);
		weight = searcher.createWeight(rewrittenQuery, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
		leaves = searcher.getIndexReader().leaves();
		initialized = true;
	}

	private boolean openNextMatchingLeaf() throws IOException {
		while (leafIndex < leaves.size()) {
			currentLeaf = leaves.get(leafIndex++);
			Scorer scorer = weight.scorer(currentLeaf);
			if (scorer == null) {
				clearCurrentLeaf();
				continue;
			}
			// A Lucene scorer may expose only an approximation through iterator(), and does not apply deletions.
			// Wrap its two-phase matcher here; loadNextCandidate applies the leaf's live-document filter separately.
			TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
			currentMatches = twoPhase == null
					? scorer.iterator()
					: TwoPhaseIterator.asDocIdSetIterator(twoPhase);
			currentEntityIds = DocValues.getNumeric(
					currentLeaf.reader(), LuceneGeoDocumentSchema.FIELD_ID);
			currentSourceTopologicalDimensions = DocValues.getNumeric(
					currentLeaf.reader(), LuceneGeoDocumentSchema.FIELD_SOURCE_TOPOLOGICAL_DIMENSION);
			currentLiveDocs = currentLeaf.reader().getLiveDocs();
			return true;
		}
		return false;
	}

	private long requiredNumericValue(NumericDocValues values, int documentId, String field) throws IOException {
		if (!values.advanceExact(documentId)) {
			int globalDocumentId = currentLeaf.docBase + documentId;
			throw new PluginException("GeoSPARQL Lucene document " + globalDocumentId
					+ " is missing required numeric doc values field '" + field + "'. "
					+ LuceneGeoDocumentSchema.SCHEMA_MISMATCH_MESSAGE);
		}
		return values.longValue();
	}

	private void clearCurrentLeaf() {
		currentLeaf = null;
		currentMatches = null;
		currentEntityIds = null;
		currentSourceTopologicalDimensions = null;
		currentLiveDocs = null;
	}

	private void closeAfterFailure(RuntimeException failure) {
		try {
			close();
		} catch (IOException closeFailure) {
			failure.addSuppressed(closeFailure);
		}
	}
}
