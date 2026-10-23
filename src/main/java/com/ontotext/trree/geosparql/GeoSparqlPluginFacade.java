package com.ontotext.trree.geosparql;

import com.ontotext.trree.sdk.InitReason;
import com.ontotext.trree.sdk.ParallelTransactionListener;
import com.ontotext.trree.sdk.PatternInterpreter;
import com.ontotext.trree.sdk.PluginBase;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.RequestContext;
import com.ontotext.trree.sdk.ShutdownReason;
import com.ontotext.trree.sdk.StatementIterator;
import com.ontotext.trree.sdk.StatementListener;
import com.ontotext.trree.sdk.UpdateInterpreter;
import org.slf4j.Logger;

import javax.management.InstanceAlreadyExistsException;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.Supplier;

/**
 * GraphDB plugin entry point that forwards each repository's calls to a {@link GeoSparqlPlugin} loaded by one JVM-wide
 * {@link PluginRuntimeClassLoader}.
 *
 * <p>GraphDB loads the plugin for each repository through a separate external plugin class loader and closes it when
 * the repository shuts down. Apache SIS and its embedded Derby database keep JVM-wide state that one class loader must
 * own for the life of the JVM, so the plugin and its dependencies load once and every repository's entry point shares
 * them. The runtime class loader is never closed. It is registered in the platform MBean server under type
 * {@link #RUNTIME_CLASS_LOADER_NAME_PREFIX}, keyed by the plugin jar location, so separately installed plugin directories do
 * not share a runtime.
 *
 * <p>This class refers only to GraphDB SDK, SLF4J and JDK types, so a repository's class loader defines no other plugin
 * class and can be collected after GraphDB closes it. When the class loader that defines the entry point also defines
 * the GraphDB SDK, the plugin is on the host class path, its dependencies already have the JVM lifetime, and the plugin
 * loads alongside the entry point.
 */
public final class GeoSparqlPluginFacade extends PluginBase implements PatternInterpreter, UpdateInterpreter,
		ParallelTransactionListener, StatementListener {
	static final String RUNTIME_CLASS_LOADER_NAME_PREFIX = "com.ontotext.trree.geosparql:type=PluginRuntimeClassLoader";
	private static final String PLUGIN_CLASS = "com.ontotext.trree.geosparql.GeoSparqlPlugin";
	private static final String RUNTIME_CLASS_LOADER_CLASS = "com.ontotext.trree.geosparql.PluginRuntimeClassLoader";

	private final PluginBase plugin;

	public GeoSparqlPluginFacade() {
		try {
			plugin = (PluginBase) Class.forName(PLUGIN_CLASS, true, runtimeClassLoader()).getConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot load the GeoSPARQL plugin runtime.", e);
		}
	}

	/** Returns the plugin instance that serves this repository. */
	PluginBase plugin() {
		return plugin;
	}

	private static ClassLoader runtimeClassLoader() {
		ClassLoader repositoryLoader = GeoSparqlPluginFacade.class.getClassLoader();
		if (!(repositoryLoader instanceof URLClassLoader pluginJars)
				|| PluginBase.class.getClassLoader() == repositoryLoader) {
			return repositoryLoader;
		}
		try {
			ObjectName name = new ObjectName(RUNTIME_CLASS_LOADER_NAME_PREFIX + ",location="
					+ ObjectName.quote(pluginJar().toString()));
			return publishedRuntimeClassLoader(ManagementFactory.getPlatformMBeanServer(), name,
					() -> newRuntimeClassLoader(pluginJars.getURLs(), repositoryLoader.getParent()));
		} catch (JMException | IOException e) {
			throw new IllegalStateException("Cannot publish the GeoSPARQL plugin runtime class loader.", e);
		}
	}

	/**
	 * Returns the runtime class loader published under {@code name}, first publishing one from {@code create} if none
	 * is. When another repository publishes first, the created class loader is closed and the published one is used.
	 */
	static ClassLoader publishedRuntimeClassLoader(MBeanServer server, ObjectName name, Supplier<ClassLoader> create)
			throws JMException, IOException {
		if (!server.isRegistered(name)) {
			ClassLoader created = create.get();
			try {
				server.registerMBean(created, name);
			} catch (InstanceAlreadyExistsException publishedConcurrently) {
				((Closeable) created).close();
			}
		}
		return server.getClassLoader(name);
	}

	/**
	 * Defines the runtime class loader's own class outside the repository's class loader, which GraphDB closes when the
	 * repository shuts down.
	 */
	private static ClassLoader newRuntimeClassLoader(URL[] jars, ClassLoader host) {
		try {
			Class<?> type = Class.forName(RUNTIME_CLASS_LOADER_CLASS, true,
					new URLClassLoader(new URL[]{pluginJar()}, host));
			return (ClassLoader) type.getConstructor(URL[].class, ClassLoader.class).newInstance(jars, host);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot create the GeoSPARQL plugin runtime class loader.", e);
		}
	}

	private static URL pluginJar() {
		return GeoSparqlPluginFacade.class.getProtectionDomain().getCodeSource().getLocation();
	}

	@Override
	public String getName() {
		return plugin.getName();
	}

	@Override
	public Logger getLogger() {
		return plugin.getLogger();
	}

	@Override
	public void setLogger(Logger logger) {
		plugin.setLogger(logger);
	}

	@Override
	public File getDataDir() {
		return plugin.getDataDir();
	}

	@Override
	public void setDataDir(File dataDir) {
		plugin.setDataDir(dataDir);
	}

	@Override
	public long getFingerprint() {
		return plugin.getFingerprint();
	}

	@Override
	public void setFingerprint(long fingerprint) {
		plugin.setFingerprint(fingerprint);
	}

	/** Initializes the plugin with its runtime as the context class loader, so dependency service lookups use it. */
	@Override
	public void initialize(InitReason reason, PluginConnection pluginConnection) {
		ClassLoader repositoryLoader = GeoSparqlPluginFacade.class.getClassLoader();
		if (plugin.getClass().getClassLoader() == repositoryLoader && PluginBase.class.getClassLoader() != repositoryLoader) {
			getLogger().error("GeoSPARQL loads its runtime for each repository because GraphDB's plugin class loader does "
					+ "not expose the plugin jars. Repositories cannot share the SIS_DATA EPSG database, and deleted "
					+ "repositories keep their runtime in memory.");
		}
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(plugin.getClass().getClassLoader());
		try {
			plugin.initialize(reason, pluginConnection);
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	@Override
	public void shutdown(ShutdownReason reason) {
		plugin.shutdown(reason);
	}

	@Override
	public double estimate(long subject, long predicate, long object, long context, PluginConnection pluginConnection,
			RequestContext requestContext) {
		return ((PatternInterpreter) plugin).estimate(subject, predicate, object, context, pluginConnection,
				requestContext);
	}

	@Override
	public StatementIterator interpret(long subject, long predicate, long object, long context,
			PluginConnection pluginConnection, RequestContext requestContext) {
		return ((PatternInterpreter) plugin).interpret(subject, predicate, object, context, pluginConnection,
				requestContext);
	}

	@Override
	public long[] getPredicatesToListenFor() {
		return ((UpdateInterpreter) plugin).getPredicatesToListenFor();
	}

	@Override
	public boolean interpretUpdate(long subject, long predicate, long object, long context, boolean isAddition,
			boolean isExplicit, PluginConnection pluginConnection) {
		return ((UpdateInterpreter) plugin).interpretUpdate(subject, predicate, object, context, isAddition, isExplicit,
				pluginConnection);
	}

	@Override
	public boolean statementAdded(long subject, long predicate, long object, long context, boolean explicit,
			PluginConnection pluginConnection) {
		return ((StatementListener) plugin).statementAdded(subject, predicate, object, context, explicit,
				pluginConnection);
	}

	@Override
	public boolean statementRemoved(long subject, long predicate, long object, long context, boolean explicit,
			PluginConnection pluginConnection) {
		return ((StatementListener) plugin).statementRemoved(subject, predicate, object, context, explicit,
				pluginConnection);
	}

	@Override
	public void transactionStarted(PluginConnection pluginConnection) {
		((ParallelTransactionListener) plugin).transactionStarted(pluginConnection);
	}

	@Override
	public void transactionCommit(PluginConnection pluginConnection) {
		((ParallelTransactionListener) plugin).transactionCommit(pluginConnection);
	}

	@Override
	public void transactionCompleted(PluginConnection pluginConnection) {
		((ParallelTransactionListener) plugin).transactionCompleted(pluginConnection);
	}

	@Override
	public void transactionAborted(PluginConnection pluginConnection) {
		((ParallelTransactionListener) plugin).transactionAborted(pluginConnection);
	}

	@Override
	public void transactionAbortedByUser(PluginConnection pluginConnection) {
		((ParallelTransactionListener) plugin).transactionAbortedByUser(pluginConnection);
	}
}
