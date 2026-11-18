package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.CandidateEntity;
import com.ontotext.trree.geosparql.CloseableIterator;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;

/**
 * Groups Lucene documents matched by a candidate query into one {@link CandidateEntity} per entity id.
 *
 * <p>Numeric entity-id sorting keeps all matching documents for an entity contiguous across {@code searchAfter}
 * pages. Every page runs the candidate query again, so each continuation page is larger than the one before it, up to
 * {@link #MAXIMUM_PAGE_SIZE}: a lookup with few candidates reads one small page, and a broad lookup repeats its query
 * a number of times that grows far more slowly than its candidate count. The source geometry resolver is shared
 * across the current group, so documents derived from the same source geometry literal reuse one validated source
 * snapshot. This iterator holds a reference to the searcher's reader and releases it on {@link #close()}.
 */
class LuceneCandidateEntityIterator implements CloseableIterator<CandidateEntity> {
	private static final int FIRST_PAGE_SIZE = 1000;
	private static final int PAGE_GROWTH = 4;
	private static final int MAXIMUM_PAGE_SIZE = 64_000;
	private static final Sort ENTITY_ID_SORT = new Sort(new SortField(LuceneGeoDocumentSchema.FIELD_ID,
			SortField.Type.LONG));
	private static final Sort CONTINUATION_SORT = continuationSort();

	private final IndexSearcher searcher;
	private final Query query;
	private final SourceGeometryLiteralResolver sourceResolver = new SourceGeometryLiteralResolver();
	private TopDocs topDocs;
	private int pageSize = FIRST_PAGE_SIZE;
	private int idx;
	private boolean exhausted;
	private CandidateEntity next;
	private Document bufferedDocument;
	private boolean closed;

	LuceneCandidateEntityIterator(IndexSearcher searcher, Query query) {
		this.searcher = searcher;
		this.query = query;
	}

	private static Sort continuationSort() {
		SortField entityId = new SortField(LuceneGeoDocumentSchema.FIELD_ID, SortField.Type.LONG);
		// The schema writes identical entity ids to the point and doc-values fields.
		entityId.setCanUsePoints();
		return new Sort(entityId);
	}

	@Override
	public boolean hasNext() {
		if (next != null) {
			return true;
		}
		next = loadNextCandidateEntity();
		return next != null;
	}

	@Override
	public CandidateEntity next() {
		if (!hasNext()) {
			throw new NoSuchElementException("No more Lucene candidate entities.");
		}
		CandidateEntity result = next;
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
		bufferedDocument = null;
		sourceResolver.clear();
		searcher.getIndexReader().decRef();
	}

	private CandidateEntity loadNextCandidateEntity() {
		try {
			Document firstDocument = nextDocument();
			if (firstDocument == null) {
				return null;
			}
			long entityId = LuceneGeoDocumentSchema.entityId(firstDocument);
			sourceResolver.clear();
			LinkedHashSet<SourceGeometryLiteral> sources = new LinkedHashSet<>();
			sources.add(LuceneGeoDocumentSchema.sourceGeometryLiteral(firstDocument, sourceResolver));

			while (true) {
				Document document = nextDocument();
				if (document == null) {
					return new CandidateEntity(entityId, sources);
				}
				long documentEntityId = LuceneGeoDocumentSchema.entityId(document);
				if (documentEntityId != entityId) {
					bufferedDocument = document;
					return new CandidateEntity(entityId, sources);
				}
				sources.add(LuceneGeoDocumentSchema.sourceGeometryLiteral(document, sourceResolver));
			}
		} catch (IOException e) {
			throw new PluginException("Unable to read Lucene candidate entity documents.", e);
		}
	}

	private void loadFirstPage() {
		try {
			topDocs = searcher.search(query, pageSize, ENTITY_ID_SORT);
			idx = 0;
		} catch (IOException e) {
			throw new PluginException("Unable to execute Lucene candidate entity query.", e);
		}
	}

	private boolean loadNextPage() {
		if (exhausted) {
			return false;
		}
		if (topDocs.scoreDocs.length < pageSize) {
			exhausted = true;
			return false;
		}
		try {
			FieldDoc last = (FieldDoc) topDocs.scoreDocs[topDocs.scoreDocs.length - 1];
			long lastEntityId = (Long) last.fields[0];
			// Include the last entity because its source documents may span pages.
			Query remaining = new BooleanQuery.Builder()
					.add(query, BooleanClause.Occur.FILTER)
					.add(LuceneGeoDocumentSchema.entityIdsAtLeastQuery(lastEntityId), BooleanClause.Occur.FILTER)
					.build();
			pageSize = Math.min(pageSize * PAGE_GROWTH, MAXIMUM_PAGE_SIZE);
			// Avoid point-comparator setup for lookups that fit on their first page.
			topDocs = searcher.searchAfter(last, remaining, pageSize, CONTINUATION_SORT);
			idx = 0;
			if (topDocs.scoreDocs.length == 0) {
				exhausted = true;
				return false;
			}
			return true;
		} catch (IOException e) {
			throw new PluginException("Unable to page Lucene candidate entity query.", e);
		}
	}

	private Document nextDocument() throws IOException {
		if (bufferedDocument != null) {
			Document document = bufferedDocument;
			bufferedDocument = null;
			return document;
		}
		if (topDocs == null) {
			loadFirstPage();
		}
		while (idx >= topDocs.scoreDocs.length) {
			if (!loadNextPage()) {
				return null;
			}
		}
		return searcher.doc(topDocs.scoreDocs[idx++].doc);
	}
}
