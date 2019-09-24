package com.ontotext.trree.geosparql;

import com.google.common.annotations.VisibleForTesting;
import com.ontotext.trree.geosparql.gml.GmlConverter;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.sdk.*;
import com.useekm.indexing.GeoConstants;
import com.useekm.types.GeoConvert;
import com.useekm.types.exception.InvalidGeometryException;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.locationtech.jts.geom.Geometry;
import gnu.trove.TLongObjectHashMap;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import javax.xml.bind.JAXBException;
import java.io.IOException;

/**
 * GeoSPARQL index/query plugin.
 */
public class GeoSparqlPlugin extends PluginBase implements PatternInterpreter, UpdateInterpreter,
        ParallelTransactionListener, StatementListener {
    static final ValueFactory VALUE_FACTORY = SimpleValueFactory.getInstance();

    private static final String NS = "http://www.ontotext.com/plugins/geosparql#";

    static final IRI CONTEXT_IRI = VALUE_FACTORY.createIRI(NS.substring(0, NS.length() - 1));

    static final IRI FORCE_REINDEX_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "forceReindex");

    static final IRI ENABLED_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "enabled");

    static final IRI PREFIXTREE_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "prefixTree");

    static final IRI PRECISION_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "precision");

    static final IRI CURRENT_PREFIXTREE_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "currentPrefixTree");

    static final IRI CURRENT_PRECISION_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "currentPrecision");

    static final IRI IGNORE_ERRORS_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "ignoreErrors");

    static final IRI MAX_BUFFERED_DOCS_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "maxBufferedDocs");

    static final IRI RAM_BUFFER_SIZE_MB_PREDICATE_IRI = VALUE_FACTORY.createIRI(NS, "ramBufferSizeMB");

    private GeoSparqlConfig config;

    // Custom SPARQL config predicate ids
    private long forceReindexPredicateId;
    long contextId;
    long enabledPredicateId;
    long prefixTreePredicateId;
    long precisionPredicateId;
    long currentPrefixTreePredicateId;
    long currentPrecisionPredicateId;
    long ignoreErrorsPredicateId;
    long maxBufferedDocsPredicateId;
    long ramBufferSizePredicateId;

	long asWKT;
	long asGML;
	long hasDefaultGeometry;

	GeoSparqlIndexer indexer;
	GeoSparqlConfig.PrefixTree tmpPrefixTree;
    int tmpPrecision;

    private TLongObjectHashMap<GeoSparqlFunction> predicateIds2Function =
			new TLongObjectHashMap<>(GeoSparqlFunction.values().length);

	private GmlConverter gmlConverter;

	private GeoSparqlUpdateListener updateListener;

	@Override
	public String getName() {
		return "GeoSPARQL";
	}

	@Override
	public void initialize(InitReason reason, PluginConnection pluginConnection) {
        config = GeoSparqlUtils.readConfig(getDataDir().toPath());

        initControlPredicates(pluginConnection.getEntities());

        initPluginFeatures(pluginConnection.getEntities());

        if (getConfig().isEnabled()) {
            try {
                indexer.begin();
                indexer.commit();
            } catch (Exception e) {
                // If created index is older version on indexer
                // begin is thrown IndexFormatTooOldException and we delete
                // directory and recreate index
                if (e instanceof IndexFormatTooOldException) {
                    try {
                        FileUtils.deleteDirectory(indexer.getIndexDir().toFile());
                        getLogger().warn("Upgrade detected! Should rebuild existing index!");
                        indexAllData(false, pluginConnection);
                    } catch (IOException ex) {
                        getLogger().error("Could not delete existing lucene directory!");
                    }
                }
            }
        }

        updateListener = new GeoSparqlUpdateListener(this, asWKT, asGML, hasDefaultGeometry);
    }

    @Override
	public double estimate(long subject, long predicate, long object, long context, PluginConnection pluginConnection,
                           RequestContext requestContext) {
		if ((subject != 0 || object != 0) && predicateIds2Function.contains(predicate)) {
            // GeoSPARQL query
            return 0.1;
        } else if (subject == contextId || predicate == enabledPredicateId || predicate == prefixTreePredicateId
                        || predicate == precisionPredicateId || predicate == currentPrefixTreePredicateId
                        || predicate == currentPrecisionPredicateId || predicate == maxBufferedDocsPredicateId
                        || predicate == ramBufferSizePredicateId) {
            // status query
            return 0.1;
        } else {
			return Double.POSITIVE_INFINITY;
		}
	}

	@Override
	public StatementIterator interpret(long subject, long predicate, long object, long context,
                                       PluginConnection pluginConnection, RequestContext requestContext) {
        if (subject == contextId) {
            return new GeoSparqlConfigIterator(this, 0, pluginConnection.getEntities());
        } else if (predicate == enabledPredicateId || predicate == prefixTreePredicateId
                || predicate == precisionPredicateId || predicate == currentPrefixTreePredicateId
                || predicate == currentPrecisionPredicateId || predicate == maxBufferedDocsPredicateId
                || predicate == ramBufferSizePredicateId) {
            return new GeoSparqlConfigIterator(this, predicate, pluginConnection.getEntities());
        }

        if (!config.isEnabled()) {
            return null;
        }

        if (predicateIds2Function.contains(predicate)) {
            if (subject == 0 && object == 0) {
                return StatementIterator.EMPTY;
            }

            return new GeoSparqlRelationIterator(this, predicateIds2Function.get(predicate), subject, predicate,
                    object, pluginConnection.getEntities());
        }

		return null;
	}

    @Override
    public long[] getPredicatesToListenFor() {
        return new long[]{ forceReindexPredicateId, enabledPredicateId, prefixTreePredicateId,
                precisionPredicateId, ignoreErrorsPredicateId, maxBufferedDocsPredicateId, ramBufferSizePredicateId };
    }

    @Override
    public boolean interpretUpdate(long subject, long predicate, long object, long context,
                                   boolean isAddition, boolean isExplicit, PluginConnection pluginConnection) {
        if (predicate == forceReindexPredicateId && config.isEnabled()) {
            indexAllData(true, pluginConnection);
        } else if (predicate == enabledPredicateId) {
            boolean wasPluginEnabled = config.isEnabled();
            String pluginEnabledStringLiteral = pluginConnection.getEntities().get(object).stringValue();
            config.setEnabled(XMLDatatypeUtil.parseBoolean(pluginEnabledStringLiteral));
            if (config.isEnabled() != wasPluginEnabled) {
                GeoSparqlUtils.saveConfig(config, getDataDir().toPath());
                if (config.isEnabled()) {
                    initializeGeoIndexer();
                    indexAllData(false, pluginConnection);
                } else {
                    try {
                        indexer.rollback();
                    } catch (Exception e) {
                        throw new PluginException("Unable to rollback plugin data", e);
                    }
                }
            }
        } else if (predicate == prefixTreePredicateId) {
            String prefixTreeString = pluginConnection.getEntities().get(object).stringValue();
            try {
                tmpPrefixTree = GeoSparqlConfig.PrefixTree.valueOf(prefixTreeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new PluginException(
                        String.format("Unknown prefix tree: %s. The supported types are 'quad' and 'geohash'.",
                                prefixTreeString));
            }
        } else if (predicate == precisionPredicateId) {
            String precisionString = pluginConnection.getEntities().get(object).stringValue();
            try {
                tmpPrecision = Integer.parseInt(precisionString);
            } catch (NumberFormatException e) {
                throw new PluginException("Precision must be an integer number.");
            }
        } else if (predicate == ignoreErrorsPredicateId) {
            String ignoreErrorsString = pluginConnection.getEntities().get(object).stringValue();
            boolean ignoreErrors = Boolean.parseBoolean(ignoreErrorsString);
            config.setIgnoreErrors(ignoreErrors);
            GeoSparqlUtils.saveConfig(config, getDataDir().toPath());
        } else if (predicate == maxBufferedDocsPredicateId) {
            String maxBufferedDocsString = pluginConnection.getEntities().get(object).stringValue();
            try {
                int maxBufferedDocs = Integer.parseInt(maxBufferedDocsString);
                config.setMaxBufferedDocs(maxBufferedDocs);
                GeoSparqlUtils.saveConfig(config, getDataDir().toPath());
            } catch (NumberFormatException e) {
                throw new PluginException("Maximum buffered documents must be an integer number.");
            }
        } else if (predicate == ramBufferSizePredicateId) {
            String ramBufferSizeString = pluginConnection.getEntities().get(object).stringValue();
            try {
                double ramBufferSize = Double.parseDouble(ramBufferSizeString);
                config.setRamBufferSizeMb(ramBufferSize);
                GeoSparqlUtils.saveConfig(config, getDataDir().toPath());
            } catch (NumberFormatException e) {
                throw new PluginException("Ram buffer size must be a double number.");
            }
        }

        return true;
    }

    public GeoSparqlConfig getConfig() {
        return config;
    }

    @VisibleForTesting
    public void setConfig(GeoSparqlConfig config) {
        this.config = config;
    }

    Geometry getGeometryFromLiteralId(long id, long geometryTypeId, Entities entities) {
        return getGeometryFromString(entities.get(id).stringValue(), geometryTypeId);
    }

    Geometry getGeometryFromString(String literalValue, long geometryTypeId) {
        try {
            if (geometryTypeId == asWKT) {
                return GeoConvert.wktToGeometry(literalValue);
            } else if (geometryTypeId == asGML) {
                return gmlConverter.gmlToGeometry(literalValue);
            }
        } catch (InvalidGeometryException e) {
            // ignore and pretend value isn't there
            getLogger().debug("Invalid geometry value: " + literalValue);
        }

        return null;
    }

    private void initPluginFeatures(Entities entities) {
        enableGeoSparqlPredicates(entities);

        for (GeoSparqlFunction function : GeoSparqlFunction.values()) {
            long predicateUriId = entities.put(function.getPredicateUri(), Entities.Scope.DEFAULT);
            predicateIds2Function.put(predicateUriId, function);
        }

        try {
            this.gmlConverter = new GmlConverter();
        } catch (JAXBException e) {
            throw new PluginException("Unable to init GML converter.", e);
        }

        initializeGeoIndexer();

        FunctionLoader.loadFunctionsInPackage("com.useekm.geosparql");
    }

    /**
     * These predicates are used only for control/status check and belong in the SYSTEM scope.
     */
    private void initControlPredicates(Entities entities) {
        contextId = entities.put(CONTEXT_IRI, Entities.Scope.SYSTEM);
        enabledPredicateId = entities.put(ENABLED_PREDICATE_IRI, Entities.Scope.SYSTEM);
        forceReindexPredicateId = entities.put(FORCE_REINDEX_PREDICATE_IRI, Entities.Scope.SYSTEM);
        prefixTreePredicateId = entities.put(PREFIXTREE_PREDICATE_IRI, Entities.Scope.SYSTEM);
        precisionPredicateId = entities.put(PRECISION_PREDICATE_IRI, Entities.Scope.SYSTEM);
        currentPrefixTreePredicateId = entities.put(CURRENT_PREFIXTREE_PREDICATE_IRI, Entities.Scope.SYSTEM);
        currentPrecisionPredicateId = entities.put(CURRENT_PRECISION_PREDICATE_IRI, Entities.Scope.SYSTEM);
        ignoreErrorsPredicateId = entities.put(IGNORE_ERRORS_PREDICATE_IRI, Entities.Scope.SYSTEM);
        maxBufferedDocsPredicateId = entities.put(MAX_BUFFERED_DOCS_PREDICATE_IRI, Entities.Scope.SYSTEM);
        ramBufferSizePredicateId = entities.put(RAM_BUFFER_SIZE_MB_PREDICATE_IRI, Entities.Scope.SYSTEM);
    }

    /**
     * These predicates are used in the data model and belong in the DEFAULT scope.
     */
    private void enableGeoSparqlPredicates(Entities entities) {
        asWKT = entities.put(GeoConstants.GEO_AS_WKT, Entities.Scope.DEFAULT);
        asGML = entities.put(GeoConstants.GEO_AS_GML, Entities.Scope.DEFAULT);
        hasDefaultGeometry = entities.put(GeoConstants.GEO_HAS_DEFAULT_GEOMETRY, Entities.Scope.DEFAULT);
    }

    private void initializeGeoIndexer() {
        if (config.isEnabled() && indexer == null) {
            try {
                getLogger().info(">>>>>>>> GeoSPARQL: Initializing Lucene indexer...");
                indexer = new LuceneGeoIndexer(this);
                indexer.initialize();
                getLogger().info(">>>>>>>> GeoSPARQL: Lucene indexer initialized!");
            } catch (Exception e) {
                throw new PluginException("Cannot initialize GeoSPARQL indexer!");
            }
        }
    }

    private void indexAllData(boolean forced, PluginConnection pluginConnection) {
        config.updateCurrentSettings();
        indexer.initSettings();
        try {
            if (forced) {
                getLogger().info(">>>>>>>> GeoSPARQL: Initializing force reindexing process...");
                new GeoSparqlForceReindexer(indexer, this).reindex(pluginConnection);
            } else {
                getLogger().info(">>>>>>>> GeoSPARQL: Initializing indexing process...");
                indexer.begin();
                new GeoSparqlForceReindexer(indexer, this).reindex(pluginConnection);
                indexer.commit();
            }
            GeoSparqlUtils.saveConfig(config, getDataDir().toPath());
            getLogger().info(">>>>>>>> GeoSPARQL: Indexing completed!");
        } catch (Exception e) {
            throw new PluginException("Unable to index GeoSPARQL geometries.", e);
        }
    }

    @Override
    public boolean statementAdded(long subject, long predicate, long object, long context, boolean explicit, PluginConnection pluginConnection) {
        return updateListener.statementAdded(subject, predicate, object, context, explicit, pluginConnection);
    }

    @Override
    public boolean statementRemoved(long subject, long predicate, long object, long context, boolean explicit, PluginConnection pluginConnection) {
        return updateListener.statementRemoved(subject, predicate, object, context, explicit, pluginConnection);
    }

    @Override
    public void transactionStarted(PluginConnection pluginConnection) {
        updateListener.transactionStarted(pluginConnection);
    }

    @Override
    public void transactionCommit(PluginConnection pluginConnection) {
        updateListener.transactionCommit(pluginConnection);
    }

    @Override
    public void transactionCompleted(PluginConnection pluginConnection) {
        updateListener.transactionCompleted(pluginConnection);
    }

    @Override
    public void transactionAborted(PluginConnection pluginConnection) {
        updateListener.transactionAborted(pluginConnection);
    }

    @Override
    public void transactionAbortedByUser(PluginConnection pluginConnection) {
        updateListener.transactionAbortedByUser(pluginConnection);
    }
}
