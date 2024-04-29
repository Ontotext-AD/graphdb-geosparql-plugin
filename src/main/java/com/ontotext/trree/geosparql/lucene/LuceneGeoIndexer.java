package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.EntityGeometryIterator;
import com.ontotext.trree.geosparql.GeoSparqlConfig;
import com.ontotext.trree.geosparql.GeoSparqlIndexer;
import com.ontotext.trree.geosparql.GeoSparqlPlugin;
import com.ontotext.trree.sdk.PluginException;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.composite.CompositeSpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.prefix.tree.QuadPrefixTree;
import org.apache.lucene.spatial.prefix.tree.SpatialPrefixTree;
import org.apache.lucene.spatial.prefix.tree.S2PrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.apache.lucene.spatial.serialized.SerializedDVStrategy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.spatial4j.context.jts.JtsSpatialContext;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Lucene implementation of the GeoSPARQL indexer.
 */
public class LuceneGeoIndexer implements GeoSparqlIndexer {

	private final static FieldType GEO_DATA_FIELD_TYPE = new FieldType();

	static {
		GEO_DATA_FIELD_TYPE.setStored(true);
		GEO_DATA_FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
		GEO_DATA_FIELD_TYPE.freeze();
	}

	private GeoSparqlPlugin parent;

	private JtsSpatialContext ctx;

    private SpatialStrategy strategy;
    private Path indexDir;

    private Directory directory;

    private IndexWriterConfig iwConfig;
    private IndexWriter indexWriter;
    private Logger logger;

	public LuceneGeoIndexer(GeoSparqlPlugin parent) {
		this.parent = parent;
	}


	@Override
	public void initialize() throws Exception {
		this.logger = parent.getLogger();

		this.ctx = JtsSpatialContext.GEO;

        this.indexDir = GeoSparqlConfig.resolveIndexPath(parent.getDataDir().toPath());

		this.directory = FSDirectory.open(indexDir);

		initSettings();
	}

	private Document newGeoDocument(long id, Geometry geometry) {
		final Document doc = new Document();
		doc.add(new LongPoint("id", id));
		doc.add(new StoredField("id", id));

        JtsGeometry shape = new JtsGeometry(geometry, ctx, true, true);
		// Adds an index to JtsGeometry class internally to compute spatial relations faster.
        shape.index();

        for (Field f : strategy.createIndexableFields(shape)) {
            doc.add(f);
        }

        doc.add(geometryToField(geometry));

		return doc;
	}

	@Override
	public void initSettings() {
		SpatialPrefixTree grid;
		GeoSparqlConfig.PrefixTree prefixTree = parent.getConfig().getCurrentPrefixTree();
		int precision = parent.getConfig().getCurrentPrecision();
		if (prefixTree == GeoSparqlConfig.PrefixTree.QUAD) {
			grid = new QuadPrefixTree(ctx, precision);
		} else if (prefixTree == GeoSparqlConfig.PrefixTree.GEOHASH) {
			grid = new GeohashPrefixTree(ctx, precision);
		} else if (prefixTree == GeoSparqlConfig.PrefixTree.S2) {
			grid = new S2PrefixTree(ctx, precision);
		} else {
			throw new PluginException("Unexpected prefix tree type: " + prefixTree);
		}

		RecursivePrefixTreeStrategy rptStrategy = new RecursivePrefixTreeStrategy(grid, "geoData1");
		SerializedDVStrategy sdvStrategy = new SerializedDVStrategy(ctx, "geoData2");
		this.strategy = new CompositeSpatialStrategy("geoData", rptStrategy, sdvStrategy);
	}

	@Override
	public void begin() throws Exception {
		iwConfig = new IndexWriterConfig();
		// Turn off compound file format.
		// Building the compound file format takes time during indexing (7-33%)
		iwConfig.setUseCompoundFile(false);
		// Set maxBufferedDocs large enough to prevent the writer from flushing based on document count.
		iwConfig.setMaxBufferedDocs(parent.getConfig().getMaxBufferedDocs());
		//More RAM before flushing means Lucene writes larger segments to begin with which means less merging later.
		iwConfig.setRAMBufferSizeMB(parent.getConfig().getRamBufferSizeMb());
		indexWriter = new IndexWriter(directory, iwConfig);
	}

	@Override
	public void commit() throws Exception {
		indexWriter.close(); // also commits
	}

	@Override
	public void rollback() throws Exception {
		indexWriter.rollback();
	}

	@Override
	public void freshIndex() throws Exception {
		indexWriter.deleteAll();
	}

	@Override
	public void indexGeometryList(long subject, Function<Long, String> subjectMapper, List<Geometry> geometries) {
		//logger.info("Indexing literal for {}; {}", parent.getEntities().get(subject), geometries.size());
		try {
			indexWriter.deleteDocuments(LongPoint.newExactQuery("id", subject));
			if (!geometries.isEmpty()) {
				for (Geometry geometry : geometries) {
					indexWriter.addDocument(newGeoDocument(subject, geometry));
				}
			}
		} catch (Exception e) {
			handleCreateDocumentUnhandledException(subject, subjectMapper, e);
		}
	}

	@Override
	public void indexGeometry(long subject, Function<Long, String> subjectMapper, Geometry geometry) {
		try {
			indexWriter.addDocument(newGeoDocument(subject, geometry));
		} catch (Exception e) {
			handleCreateDocumentUnhandledException(subject, subjectMapper, e);
		}
	}

	private EntityGeometryIterator getDisjointObjects(Geometry geometry) {
		JtsGeometry shape = new JtsGeometry(geometry, ctx, true, true);
		// Adds an index to JtsGeometry class internally to compute spatial relations faster.
		shape.index();
		final SpatialArgs args = new SpatialArgs(SpatialOperation.Intersects, shape);

		Query query = new BooleanQuery.Builder()
				.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
				.add(strategy.makeQuery(args), BooleanClause.Occur.MUST_NOT).build();

		return getIteratorForQuery(query);
	}

	@Override
	public EntityGeometryIterator getMatchingObjects(Geometry geometry, SpatialOperation spatialOperation) {
		if (spatialOperation == SpatialOperation.IsDisjointTo) {
			return getDisjointObjects(geometry);
		} else {
			JtsGeometry shape = new JtsGeometry(geometry, ctx, true, true);
			// Adds an index to JtsGeometry class internally to compute spatial relations faster.
			shape.index();
			final SpatialArgs args = new SpatialArgs(spatialOperation, shape);

			Query query = strategy.makeQuery(args);

			return getIteratorForQuery(query);
		}
	}

	@Override
	public EntityGeometryIterator getGeometriesFor(long subject) {
		final Query query;
		if (subject > 0) {
			query = LongPoint.newExactQuery("id", subject);
		} else {
			query = new MatchAllDocsQuery();
		}

		return getIteratorForQuery(query);
	}

	private EntityGeometryIterator getIteratorForQuery(Query query) {
		IndexReader indexReader = null;
		try {
			indexReader = DirectoryReader.open(directory);
			IndexSearcher indexSearcher = new IndexSearcher(indexReader);

			return new LuceneEntityGeometryIterator(indexSearcher, query);
		} catch (Exception e) {
			if (indexReader != null) {
				try {
					indexReader.close();
				} catch (IOException x) {
					// ignore
				}
			}
			throw new PluginException("Unable to execute Lucene query.", e);
		}

	}

    private static Field geometryToField(Geometry geometry) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(bos);
			oos.writeObject(geometry);
			oos.close();
			return new Field("geoData", bos.toByteArray(), GEO_DATA_FIELD_TYPE);
		} catch (Exception e) {
			throw new PluginException("Unable to create field from geometry.", e);
		}
	}

	private void handleCreateDocumentUnhandledException(long subject, Function<Long, String> subjectMapper, Exception e) {
		String subjectIri = subjectMapper.apply(subject);

		if (parent.getConfig().isIgnoreErrors()) {
			logger.warn("Could not create GeoDocument for subject " + subjectIri, e);
		} else {
			throw new PluginException("Could not create GeoDocument for subject " + subjectIri +
					"\nIf you want to ignore this message and still build the index configure ignoreErrors = true (refer to documentation) and rebuild the index", e);
		}
	}

	static Geometry fieldValueToGeometry(byte[] fieldValue) {
		try {
			ByteArrayInputStream bis = new ByteArrayInputStream(fieldValue);
			ObjectInputStream ois = new ObjectInputStream(bis);
			return (Geometry) ois.readObject();
		} catch (Exception e) {
			throw new PluginException("Unable to create geometry from field value.", e);
		}
	}
}
