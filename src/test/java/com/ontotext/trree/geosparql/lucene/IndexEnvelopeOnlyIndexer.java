package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PointValues;

import java.io.IOException;

/**
 * Reads its index as one whose sources carry no envelope in their own CRS, so every lookup uses CRS84 index
 * envelopes.
 */
final class IndexEnvelopeOnlyIndexer extends LuceneGeoIndexer {
    IndexEnvelopeOnlyIndexer(GeoSparqlPlugin parent) {
        super(parent);
    }

    @Override
    IndexReader openReader() throws IOException {
        return new WithoutSourceEnvelopes((DirectoryReader) super.openReader());
    }

    private static final class WithoutSourceEnvelopes extends FilterDirectoryReader {
        private WithoutSourceEnvelopes(DirectoryReader reader) throws IOException {
            super(reader, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader leaf) {
                    return new FilterLeafReader(leaf) {
                        @Override
                        public PointValues getPointValues(String field) throws IOException {
                            return LuceneGeoDocumentSchema.FIELD_SOURCE_ENVELOPE.equals(field)
                                    ? null : super.getPointValues(field);
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
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader reader) throws IOException {
            return new WithoutSourceEnvelopes(reader);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }
}
