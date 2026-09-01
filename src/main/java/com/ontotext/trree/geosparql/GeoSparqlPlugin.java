package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.function.GeoSparqlFunctionRegistration;
import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.JenaGeoSparqlException;
import com.ontotext.trree.geosparql.jena.JenaGeometryAdapter;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.sdk.*;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import gnu.trove.TLongObjectHashMap;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.List;

/**
 * GraphDB plugin entry point for GeoSPARQL indexing and query evaluation.
 *
 * <p>The plugin coordinates configuration, repository geometry discovery, Lucene candidate indexing, property
 * relation iteration, and Jena-backed function registration. Geometry literal conversion preserves the source
 * geometry literal used for exact evaluation while deriving one CRS84 index envelope for each non-empty source
 * geometry literal. An empty source produces a non-spatial sentinel document.
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

    private TLongObjectHashMap<GeoSparqlPropertyRelation> predicateIdsToRelation =
			new TLongObjectHashMap<>(GeoSparqlPropertyRelation.values().length);

	private GeoSparqlUpdateListener updateListener;

	@Override
	public String getName() {
		return "GeoSPARQL";
	}

	@Override
	public void initialize(InitReason reason, PluginConnection pluginConnection) {
        GeoSparqlUtils.migrateConfig(getDataDir().toPath(), getLogger());
        config = GeoSparqlUtils.readConfig(getDataDir().toPath());
        CrsDataEnvironment.inspectSystem().log(getLogger());

        initControlPredicates(pluginConnection.getEntities());

        initPluginFeatures(pluginConnection.getEntities());

        updateListener = new GeoSparqlUpdateListener(this, asWKT, asGML, hasDefaultGeometry);
    }

    @Override
	public double estimate(long subject, long predicate, long object, long context, PluginConnection pluginConnection,
                           RequestContext requestContext) {
		if ((subject != 0 || object != 0) && predicateIdsToRelation.contains(predicate)) {
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
            return new GeoSparqlConfigIterator(this, predicate, object, pluginConnection.getEntities());
        } else if (predicate == enabledPredicateId || predicate == prefixTreePredicateId
                || predicate == precisionPredicateId || predicate == currentPrefixTreePredicateId
                || predicate == currentPrecisionPredicateId || predicate == maxBufferedDocsPredicateId
                || predicate == ramBufferSizePredicateId) {
            return new GeoSparqlConfigIterator(this, predicate, pluginConnection.getEntities());
        }

        if (!config.isEnabled()) {
            return null;
        }

        if (predicateIdsToRelation.contains(predicate)) {
            if (subject == 0 && object == 0) {
                return StatementIterator.EMPTY;
            }

            return new GeoSparqlRelationIterator(this, predicateIdsToRelation.get(predicate), subject, predicate,
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
                if (config.isEnabled()) {
                    initializeGeoIndexer();
                }
                updateListener.saveConfigForTransaction();
                if (config.isEnabled()) {
                    indexAllData(false, pluginConnection);
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
            updateListener.saveConfigForTransaction();
        } else if (predicate == maxBufferedDocsPredicateId) {
            String maxBufferedDocsString = pluginConnection.getEntities().get(object).stringValue();
            try {
                int maxBufferedDocs = Integer.parseInt(maxBufferedDocsString);
                config.setMaxBufferedDocs(maxBufferedDocs);
                updateListener.saveConfigForTransaction();
            } catch (NumberFormatException e) {
                throw new PluginException("Maximum buffered documents must be an integer number.");
            }
        } else if (predicate == ramBufferSizePredicateId) {
            String ramBufferSizeString = pluginConnection.getEntities().get(object).stringValue();
            try {
                double ramBufferSize = Double.parseDouble(ramBufferSizeString);
                config.setRamBufferSizeMb(ramBufferSize);
                updateListener.saveConfigForTransaction();
            } catch (NumberFormatException e) {
                throw new PluginException("Ram buffer size must be a double number.");
            }
        }

        return true;
    }

    public GeoSparqlConfig getConfig() {
        return config;
    }

    public void setConfig(GeoSparqlConfig config) {
        this.config = config;
    }

	IndexGeometry getIndexGeometryFromLiteral(Literal literal, IRI fallbackDatatype) {
		SourceGeometryLiteral sourceGeometryLiteral = JenaGeometryAdapter.toSourceGeometryLiteral(literal,
				fallbackDatatype);
		return JenaGeometryAdapter.toIndexGeometry(sourceGeometryLiteral);
	}

	/**
	 * Returns one index geometry for a repository source literal, or {@code null} when the object is not a literal or
	 * {@code ignoreErrors} deliberately skips an invalid repository geometry.
	 */
	IndexGeometry getIndexGeometryFromLiteralId(long geometryResourceId, long literalId, long predicateId,
			Entities entities) {
		Value value = entities.get(literalId);
		if (!(value instanceof Literal)) {
			return null;
		}
		IRI datatype = predicateId == asGML ? GeoConstants.GEO_GML_LITERAL : GeoConstants.GEO_WKT_LITERAL;
		try {
			return getIndexGeometryFromLiteral((Literal) value, datatype);
		} catch (JenaGeoSparqlException e) {
			String subjectText = entities.get(geometryResourceId).stringValue();
			String failureContext = indexingFailureContext((Literal) value, datatype, e);
			if (config.isIgnoreErrors()) {
				getLogger().warn("Skipping GeoSPARQL geometry for subject {} because it cannot be indexed. {}",
						subjectText, failureContext);
				return null;
			}
			throw new PluginException("Could not index GeoSPARQL geometry for subject " + subjectText
					+ ". " + failureContext
					+ ". Check that the geometry CRS URI is correct and that Apache SIS CRS data, such as SIS_DATA "
					+ "and required grid files, is configured when the CRS is supported. To skip invalid or unsupported "
					+ "repository geometries, configure ignoreErrors = true and rebuild the index.", e);
		}
	}

	private static String indexingFailureContext(Literal literal, IRI fallbackDatatype,
			JenaGeoSparqlException cause) {
		StringBuilder context = new StringBuilder("Source geometry literal datatype: ");
		context.append(literal.getDatatype());
		if (!literal.getDatatype().equals(fallbackDatatype)) {
			context.append("; predicate/fallback datatype: ").append(fallbackDatatype);
		}
		String crsUri = sourceGeometryCrs(literal, fallbackDatatype);
		if (crsUri != null) {
			context.append("; CRS: ").append(crsUri);
		}
		if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
			context.append("; cause: ").append(cause.getMessage());
		}
		return context.toString();
	}

	private static String sourceGeometryCrs(Literal literal, IRI fallbackDatatype) {
		try {
			return JenaGeometryAdapter.toSourceGeometryLiteral(literal, fallbackDatatype).effectiveCrsUri();
		} catch (JenaGeoSparqlException e) {
			if (GeoConstants.GEO_WKT_LITERAL.equals(fallbackDatatype)) {
				String explicitCrs = explicitWktCrs(literal.stringValue());
				if (explicitCrs != null) {
					return explicitCrs;
				}
				return IndexGeometry.INDEX_CRS + " (implicit GeoSPARQL WKT default)";
			}
			return null;
		}
	}

	private static String explicitWktCrs(String lexicalForm) {
		String trimmed = lexicalForm.trim();
		if (!trimmed.startsWith("<")) {
			return null;
		}
		int end = trimmed.indexOf('>');
		if (end <= 1) {
			return null;
		}
		return trimmed.substring(1, end);
	}

    private void initPluginFeatures(Entities entities) {
        JenaGeometryAdapter.initialize();

        enableGeoSparqlPredicates(entities);

        for (GeoSparqlPropertyRelation relation : GeoSparqlPropertyRelation.values()) {
            long predicateUriId = entities.put(relation.getPredicateUri(), Entities.Scope.DEFAULT);
            predicateIdsToRelation.put(predicateUriId, relation);
        }

        initializeGeoIndexer();

        GeoSparqlFunctionRegistration.registerAll();
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
                getLogger().debug(">>>>>>>> GeoSPARQL: Initializing Lucene indexer...");
                indexer = new LuceneGeoIndexer(this);
                indexer.initialize();
                getLogger().debug(">>>>>>>> GeoSPARQL: Lucene indexer initialized!");
            } catch (Exception e) {
                throw new PluginException("Cannot initialize GeoSPARQL indexer.", e);
            }
        }
    }

    private void indexAllData(boolean forced, PluginConnection pluginConnection) {
        updateListener.prepareConfigMutation();
        config.updateCurrentSettings();
        indexer.initSettings();
        try {
			boolean luceneTransactionAlreadyActive = indexer.isTransactionActive();
			if (!luceneTransactionAlreadyActive) {
				updateListener.beginIndexTransactionForPersistentMutation();
			}
            if (forced) {
                getLogger().info(">>>>>>>> GeoSPARQL: Initializing force reindexing process...");
                new GeoSparqlFullIndexer(indexer, this).reindex(pluginConnection);
            } else {
                getLogger().info(">>>>>>>> GeoSPARQL: Initializing indexing process...");
                new GeoSparqlFullIndexer(indexer, this).reindex(pluginConnection);
                // The enclosing GraphDB transaction, or an existing Lucene transaction, owns the outcome.
                if (!updateListener.isGraphDbTransactionActive() && !luceneTransactionAlreadyActive) {
                    indexer.commit();
                }
            }
            updateListener.saveConfigForTransaction();
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
