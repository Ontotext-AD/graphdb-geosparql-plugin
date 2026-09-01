package com.ontotext.trree.geosparql;

import com.ontotext.graphdb.Config;
import com.ontotext.graphdb.GraphDBRepositoryManager;
import com.ontotext.test.utils.OwlimSeRepositoryDescription;
import com.ontotext.trree.OwlimSchemaRepository;
import com.ontotext.trree.geosparql.lucene.LuceneGeoIndexer;
import com.ontotext.trree.geosparql.util.GeoSparqlUtils;
import com.ontotext.trree.geosparql.vocabulary.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.repository.sail.SailRepository;

import java.io.IOException;
import java.nio.file.Path;

/** Process entry point used to terminate GraphDB at persistent GeoSPARQL transaction boundaries. */
public final class GeoSparqlCrashProcess {
	static final int HALT_CODE = 23;
	private static final ValueFactory VF = SimpleValueFactory.getInstance();
	private static final IRI OLD_GEOMETRY = VF.createIRI("http://example.com/crash/old-geometry");
	private static final IRI NEW_GEOMETRY = VF.createIRI("http://example.com/crash/new-geometry");
	private static final Literal OLD_WKT = VF.createLiteral("POINT(1 1)", GeoConstants.GEO_WKT_LITERAL);
	private static final Literal NEW_WKT = VF.createLiteral("POINT(2 2)", GeoConstants.GEO_WKT_LITERAL);

	private GeoSparqlCrashProcess() {
	}

	public static void main(String[] arguments) {
		try {
			CrashBoundary boundary = CrashBoundary.valueOf(arguments[0]);
			Path managerDir = Path.of(arguments[1]);
			System.setProperty("graphdb.home.work", managerDir.resolve("work").toString());
			Config.reset();

			GraphDBRepositoryManager manager = new GraphDBRepositoryManager(managerDir.toFile());
			manager.init();
			OwlimSeRepositoryDescription description = new OwlimSeRepositoryDescription();
			description.getOwlimSailConfig().setRuleset("owl-horst");
			RepositoryConfig repositoryConfig = description.getRepositoryConfig();
			manager.addRepositoryConfig(repositoryConfig);
			Repository repository = manager.getRepository(repositoryConfig.getID());
			RepositoryConnection connection = repository.getConnection();

			addGeometry(connection, OLD_GEOMETRY, OLD_WKT);
			executeControl(connection, GeoSparqlPlugin.ENABLED_PREDICATE_IRI, true);
			GeoSparqlPlugin plugin = activePlugin(repository);

			if (boundary == CrashBoundary.AFTER_MARKER) {
				installCrashIndexer(plugin, boundary);
				connection.begin();
				connection.add(NEW_GEOMETRY, GeoConstants.GEO_AS_WKT, NEW_WKT);
				connection.commit();
				throw new IllegalStateException("GraphDB transaction did not reach the marker boundary.");
			}

			executeControl(connection, GeoSparqlPlugin.ENABLED_PREDICATE_IRI, false);
			addGeometry(connection, NEW_GEOMETRY, NEW_WKT);
			installCrashIndexer(plugin, boundary);
			executeControl(connection, GeoSparqlPlugin.ENABLED_PREDICATE_IRI, true);
			throw new IllegalStateException("GraphDB transaction did not reach the requested crash boundary.");
		} catch (Throwable failure) {
			failure.printStackTrace(System.err);
			Runtime.getRuntime().halt(24);
		}
	}

	private static void addGeometry(RepositoryConnection connection, IRI subject, Literal wkt) {
		connection.begin();
		connection.add(subject, GeoConstants.GEO_AS_WKT, wkt);
		connection.commit();
	}

	private static void executeControl(RepositoryConnection connection, IRI predicate, boolean value) {
		connection.begin();
		connection.prepareUpdate(QueryLanguage.SPARQL,
				"INSERT DATA { _:plugin <" + predicate + "> " + value + " }").execute();
		connection.commit();
	}

	private static GeoSparqlPlugin activePlugin(Repository repository) {
		return (GeoSparqlPlugin) ((OwlimSchemaRepository) ((SailRepository) repository).getSail())
				.getPlugin("GeoSPARQL");
	}

	private static void installCrashIndexer(GeoSparqlPlugin plugin, CrashBoundary boundary) throws Exception {
		CrashingLuceneGeoIndexer indexer = new CrashingLuceneGeoIndexer(plugin, boundary);
		indexer.initialize();
		plugin.indexer = indexer;
	}

	static GeoSparqlPlugin plugin(Path dataDir, GeoSparqlConfig config) {
		GeoSparqlPlugin plugin = new GeoSparqlPlugin();
		plugin.setConfig(config);
		plugin.setDataDir(dataDir.toFile());
		plugin.setLogger(org.slf4j.LoggerFactory.getLogger(GeoSparqlCrashProcess.class));
		return plugin;
	}

	enum CrashBoundary {
		AFTER_MARKER(true, false),
		AFTER_CONFIG_REPLACEMENT(true, false),
		AFTER_PROVISIONAL_COMMIT(true, true),
		AFTER_GRAPHDB_COMMIT(true, true),
		DURING_ABORT_RESTORATION(false, true);

		private final boolean enabledAfterCrash;
		private final boolean newIndexCommitPublished;

		CrashBoundary(boolean enabledAfterCrash, boolean newIndexCommitPublished) {
			this.enabledAfterCrash = enabledAfterCrash;
			this.newIndexCommitPublished = newIndexCommitPublished;
		}

		boolean enabledAfterCrash() {
			return enabledAfterCrash;
		}

		boolean newIndexCommitPublished() {
			return newIndexCommitPublished;
		}
	}

	private static final class CrashingLuceneGeoIndexer extends LuceneGeoIndexer {
		private final Path dataDir;
		private final CrashBoundary boundary;

		private CrashingLuceneGeoIndexer(GeoSparqlPlugin parent, CrashBoundary boundary) {
			super(parent);
			this.dataDir = parent.getDataDir().toPath();
			this.boundary = boundary;
		}

		@Override
		public void begin() throws Exception {
			if (boundary == CrashBoundary.AFTER_MARKER) {
				haltWithPendingMarker();
			}
			super.begin();
		}

		@Override
		public void freshIndex() throws Exception {
			if (boundary == CrashBoundary.AFTER_CONFIG_REPLACEMENT) {
				haltWithPendingMarker();
			}
			super.freshIndex();
		}

		@Override
		public void commit() throws Exception {
			super.commit();
			if (boundary == CrashBoundary.AFTER_PROVISIONAL_COMMIT) {
				haltWithPendingMarker();
			}
			if (boundary == CrashBoundary.DURING_ABORT_RESTORATION) {
				throw new IOException("Simulated GraphDB commit failure after GeoSPARQL publication.");
			}
		}

		@Override
		public void complete() throws Exception {
			if (boundary == CrashBoundary.AFTER_GRAPHDB_COMMIT) {
				haltWithPendingMarker();
			}
			super.complete();
		}

		@Override
		public void rollback(boolean recoveryRequired) throws Exception {
			if (boundary == CrashBoundary.DURING_ABORT_RESTORATION) {
				if (GeoSparqlUtils.readConfig(dataDir).isEnabled()) {
					throw new IllegalStateException("Configuration was not restored before Lucene rollback.");
				}
				haltWithPendingMarker();
			}
			super.rollback(recoveryRequired);
		}

		private void haltWithPendingMarker() {
			if (!new GeoSparqlTransactionMarker(dataDir).exists()) {
				throw new IllegalStateException("GeoSPARQL transaction marker is missing at " + boundary + ".");
			}
			Runtime.getRuntime().halt(HALT_CODE);
		}
	}
}
