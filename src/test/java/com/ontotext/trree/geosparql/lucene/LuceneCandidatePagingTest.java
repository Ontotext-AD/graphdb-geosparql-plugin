package com.ontotext.trree.geosparql.lucene;

import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.geosparql.TestIndexGeometries;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.FilterNumericDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LuceneCandidatePagingTest {
    @Rule
    public TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

    @Test
    public void candidatePagesDoNotRereadTheLowestEntityId() throws Exception {
        GeoSparqlPlugin parent = new GeoSparqlPlugin();
        GeoSparqlConfig config = new GeoSparqlConfig();
        config.setPrefixTree(GeoSparqlConfig.PrefixTree.QUAD);
        config.setPrecision(11);
        config.updateCurrentSettings();
        parent.setConfig(config);
        parent.setLogger(LoggerFactory.getLogger(LuceneCandidatePagingTest.class));
        parent.setDataDir(Files.createDirectories(tmpFolder.getRoot().toPath().resolve("entity-pages")).toFile());
        IdReadCountingIndexer indexer = new IdReadCountingIndexer(parent);
        indexer.initialize();

        int entityCount = 10000;
        List<Long> insertionOrder = new ArrayList<>(entityCount);
        for (long id = 1; id <= entityCount; id++) insertionOrder.add(id);
        Collections.shuffle(insertionOrder, new Random(1847));
        var geometry = TestIndexGeometries.fromWkt("POINT(0 0)");
        indexer.begin();
        for (long id : insertionOrder) {
            indexer.indexGeometryList(id, ignored -> "geometry", List.of(geometry));
        }
        indexer.commit();
        indexer.complete();

        indexer.idReads.set(0);
        indexer.lowestIdReads.set(0);
        long expectedId = 1;
        try (var candidates = indexer.getEnvelopeIntersections(geometry)) {
            while (candidates.hasNext()) {
                var candidate = candidates.next();
                assertEquals(expectedId++, candidate.entityId());
                assertEquals(1, candidate.matchingSourceGeometryLiterals().size());
            }
        }
        assertEquals(entityCount + 1L, expectedId);
        assertTrue("Candidate paging reread the lowest entity id " + indexer.lowestIdReads.get()
                        + " times (" + indexer.idReads.get() + " total id reads)",
                indexer.lowestIdReads.get() <= 3);
    }

    private static final class IdReadCountingIndexer extends LuceneGeoIndexer {
        private final AtomicLong idReads = new AtomicLong();
        private final AtomicLong lowestIdReads = new AtomicLong();

        private IdReadCountingIndexer(GeoSparqlPlugin parent) {
            super(parent);
        }

        @Override
        IndexReader openReader() throws IOException {
            return new IdReadCountingReader((DirectoryReader) super.openReader(), idReads, lowestIdReads);
        }
    }

    private static final class IdReadCountingReader extends FilterDirectoryReader {
        private final AtomicLong idReads;
        private final AtomicLong lowestIdReads;

        private IdReadCountingReader(DirectoryReader reader, AtomicLong idReads, AtomicLong lowestIdReads)
                throws IOException {
            super(reader, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader leaf) {
                    return new FilterLeafReader(leaf) {
                        @Override
                        public NumericDocValues getNumericDocValues(String field) throws IOException {
                            NumericDocValues values = super.getNumericDocValues(field);
                            if (!LuceneGeoDocumentSchema.FIELD_ID.equals(field) || values == null) return values;
                            return new FilterNumericDocValues(values) {
                                @Override
                                public long longValue() throws IOException {
                                    idReads.incrementAndGet();
                                    long value = super.longValue();
                                    if (value == 1) lowestIdReads.incrementAndGet();
                                    return value;
                                }
                            };
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
            this.idReads = idReads;
            this.lowestIdReads = lowestIdReads;
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader reader) throws IOException {
            return new IdReadCountingReader(reader, idReads, lowestIdReads);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return in.getReaderCacheHelper();
        }
    }
}
