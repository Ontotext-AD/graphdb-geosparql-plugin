package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.util.DurableFileOperations;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.sdk.*;
import gnu.trove.TLongHashSet;
import gnu.trove.TLongProcedure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Listener for incremental indexing of GeoSPARQL data.
 */
class GeoSparqlUpdateListener implements ParallelTransactionListener, StatementListener {
	private static final DurableFileOperations DURABLE_FILES = new DurableFileOperations();

	private final GeoSparqlPlugin parent;
	private final long asWKT;
	private final long asGML;
	private final long hasDefaultGeometry;
	private final GeoSparqlTransactionMarker transactionMarker;

	private final TLongHashSet geometriesToUpdate = new TLongHashSet();
	private final TLongHashSet featuresToUpdate = new TLongHashSet();
	private GeoSparqlConfig configBeforeTransaction;
	private byte[] configFileBeforeTransaction;
	private boolean configFileExistedBeforeTransaction;
	private boolean configMutationAttempted;
	private boolean configPersistenceStarted;
	private boolean graphDbTransactionActive;
	private boolean indexTransactionStarted;
	private boolean markerExistedBeforeTransaction;
	private boolean persistentMutationMarked;

	GeoSparqlUpdateListener(GeoSparqlPlugin parent, long asWKT, long asGML, long hasDefaultGeometry) {
		this.parent = parent;
		this.asWKT = asWKT;
		this.asGML = asGML;
		this.hasDefaultGeometry = hasDefaultGeometry;
		this.transactionMarker = new GeoSparqlTransactionMarker(parent.getDataDir().toPath());
	}

	@Override
	public boolean statementAdded(long subject, long predicate, long object, long context, boolean explicit,
								  PluginConnection pluginConnection) {
		if (! parent.getConfig().isEnabled()) {
			return false;
		}

		if (predicate == asWKT || predicate == asGML) {
			geometriesToUpdate.add(subject);
		} else if (predicate == hasDefaultGeometry) {
			featuresToUpdate.add(subject);
		}
		return false;
	}

	@Override
	public boolean statementRemoved(long subject, long predicate, long object, long context, boolean explicit,
									PluginConnection pluginConnection) {
		if (! parent.getConfig().isEnabled()) {
			return false;
		}

		if (predicate == asWKT || predicate == asGML) {
			geometriesToUpdate.add(subject);
		} else if (predicate == hasDefaultGeometry) {
			featuresToUpdate.add(subject);
		}
		return false;
	}

	@Override
	public void transactionStarted(PluginConnection pluginConnection) {
		parent.tmpPrefixTree = null;
		parent.tmpPrecision = 0;
		captureConfigState();
		graphDbTransactionActive = true;
		indexTransactionStarted = false;
		markerExistedBeforeTransaction = transactionMarker.exists();
		persistentMutationMarked = false;
		configMutationAttempted = false;
		configPersistenceStarted = false;
	}

	@Override
	public void transactionCommit(PluginConnection pluginConnection) {
		// In case of changed PrefixTree or precision in GeoSparqlPlugin should validate provided
		// parameters and change them respectively. Note that user could change both values or only single one.
		// In secondary case we'll validate changed value and already set value
		if (parent.tmpPrefixTree != null || parent.tmpPrecision != 0) {
			GeoSparqlConfig config = parent.getConfig();
			GeoSparqlConfig.PrefixTree prefixTree = parent.tmpPrefixTree != null ? parent.tmpPrefixTree : config.getPrefixTree();
			int precision = parent.tmpPrecision != 0 ? parent.tmpPrecision : config.getPrecision();
			GeoSparqlUtils.validateParams(prefixTree, precision);
			config.setPrefixTree(prefixTree);
			config.setPrecision(precision);
			saveConfigForTransaction();
		}

		if (! parent.getConfig().isEnabled()) {
			cleanupAfterTransaction();
			if (hasIndexTransaction()) {
				try {
					parent.indexer.discardUncommittedChanges();
				} catch (Exception e) {
					throw new PluginException("Unable to discard uncommitted GeoSPARQL Lucene changes.", e);
				}
			}
			return;
		}

		if (!geometriesToUpdate.isEmpty() || !featuresToUpdate.isEmpty()) {
			beginIndexTransactionForPersistentMutation();
		}

		final TLongHashSet processedFeatures = new TLongHashSet();

		RepositoryGeometrySource source = new RepositoryGeometrySource(parent, pluginConnection);

		geometriesToUpdate.forEach(new TLongProcedure() {
			@Override
			public boolean execute(long value) {
				parent.indexer.indexGeometryList(value, source.subjectMapper(),
						source.geometriesForGeometryResource(value));
				source.forEachFeatureUsingGeometry(value, (featureId, geometries) -> {
					parent.indexer.indexGeometryList(featureId, source.subjectMapper(), geometries);
					processedFeatures.add(featureId);
				});
				return true;
			}
		});

		featuresToUpdate.forEach(new TLongProcedure() {
			@Override
			public boolean execute(long value) {

				// unless we already processed that feature as part of the geometries update
				if (!processedFeatures.contains(value)) {
					parent.indexer.indexGeometryList(value, source.subjectMapper(), source.geometriesForFeature(value));
				}
				return true;
			}
		});

		cleanupAfterTransaction();

		if (hasIndexTransaction()) {
			try {
				parent.indexer.commit();
				indexTransactionStarted = true;
			} catch (Exception e) {
				throw new PluginException("Unable to commit the GeoSPARQL Lucene index.", e);
			}
		}
	}

    @Override
    public void transactionCompleted(PluginConnection pluginConnection) {
		cleanupAfterTransaction();
		if (hasIndexTransaction()) {
			try {
				parent.indexer.complete();
			} catch (Exception e) {
				parent.getLogger().warn("Unable to finalize the GeoSPARQL Lucene index transaction.", e);
			}
		} else {
			removeTransactionMarkerIfOwned();
		}
		clearOutcomeState();
    }

    @Override
	public void transactionAborted(PluginConnection pluginConnection) {
		cleanupAfterTransaction();
		boolean configRestored = restoreConfigState();
		if (hasIndexTransaction()) {
			try {
				parent.indexer.rollback(!configRestored);
			} catch (Exception e) {
				parent.getLogger().warn("Unable to rollback indexer transaction.", e);
			}
		} else if (configRestored) {
			removeTransactionMarkerIfOwned();
		}
		clearOutcomeState();
	}

	void preparePersistentMutation() {
		if (!graphDbTransactionActive || persistentMutationMarked) {
			return;
		}
		try {
			transactionMarker.create();
			persistentMutationMarked = true;
		} catch (IOException e) {
			throw new PluginException("Unable to persist the GeoSPARQL transaction marker.", e);
		}
	}

	void beginIndexTransactionForPersistentMutation() {
		preparePersistentMutation();
		if (hasIndexTransaction()) {
			return;
		}
		try {
			parent.indexer.begin();
			indexTransactionStarted = true;
		} catch (Exception e) {
			throw new PluginException("Unable to start indexer transaction.", e);
		}
	}

	void prepareConfigMutation() {
		configMutationAttempted = true;
		preparePersistentMutation();
	}

	void saveConfigForTransaction() {
		prepareConfigMutation();
		configPersistenceStarted = true;
		GeoSparqlUtils.saveConfig(parent.getConfig(), parent.getDataDir().toPath());
	}

	private void cleanupAfterTransaction() {
		// Reuse the accumulators while discarding all transaction-local entity ids.
		geometriesToUpdate.clear();
		featuresToUpdate.clear();
	}

	private void captureConfigState() {
		configBeforeTransaction = new GeoSparqlConfig();
		configBeforeTransaction.setFromProperties(parent.getConfig().getAsProperties());
		Path configPath = GeoSparqlConfig.resolveConfigPath(parent.getDataDir().toPath());
		configFileExistedBeforeTransaction = Files.exists(configPath);
		try {
			configFileBeforeTransaction = configFileExistedBeforeTransaction ? Files.readAllBytes(configPath) : null;
		} catch (IOException e) {
			throw new PluginException("Unable to retain GeoSPARQL configuration for transaction rollback.", e);
		}
	}

	private boolean restoreConfigState() {
		if (!configMutationAttempted || configBeforeTransaction == null) {
			return true;
		}
		parent.setConfig(configBeforeTransaction);
		if (!configPersistenceStarted) {
			return true;
		}
		Path configPath = GeoSparqlConfig.resolveConfigPath(parent.getDataDir().toPath());
		try {
			restoreConfigFile(configPath);
			return true;
		} catch (IOException e) {
			parent.getLogger().warn("Unable to restore GeoSPARQL configuration after transaction abort.", e);
			return false;
		}
	}

	protected void restoreConfigFile(Path configPath) throws IOException {
		if (configFileExistedBeforeTransaction) {
			DURABLE_FILES.replace(configPath, configFileBeforeTransaction);
		} else {
			DURABLE_FILES.delete(configPath);
		}
	}

	private void removeTransactionMarkerIfOwned() {
		if (!persistentMutationMarked || markerExistedBeforeTransaction) {
			return;
		}
		try {
			transactionMarker.remove();
		} catch (IOException e) {
			parent.getLogger().warn("Unable to finalize the GeoSPARQL transaction marker.", e);
		}
	}

	private void clearOutcomeState() {
		configBeforeTransaction = null;
		configFileBeforeTransaction = null;
		configFileExistedBeforeTransaction = false;
		configMutationAttempted = false;
		configPersistenceStarted = false;
		graphDbTransactionActive = false;
		indexTransactionStarted = false;
		markerExistedBeforeTransaction = false;
		persistentMutationMarked = false;
	}

	boolean isGraphDbTransactionActive() {
		return graphDbTransactionActive;
	}

	private boolean hasIndexTransaction() {
		return indexTransactionStarted || parent.indexer != null && parent.indexer.isTransactionActive();
	}
}
