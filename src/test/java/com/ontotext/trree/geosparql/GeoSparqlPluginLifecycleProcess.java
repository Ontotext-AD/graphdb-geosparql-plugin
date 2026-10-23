package com.ontotext.trree.geosparql;

import com.ontotext.graphdb.Config;
import com.ontotext.graphdb.GraphDBRepositoryManager;
import com.ontotext.test.utils.OwlimSeRepositoryDescription;
import com.ontotext.trree.OwlimSchemaRepository;
import com.ontotext.trree.sdk.Entities;
import com.ontotext.trree.sdk.InitReason;
import com.ontotext.trree.sdk.Plugin;
import com.ontotext.trree.sdk.PluginBase;
import com.ontotext.trree.sdk.PluginConnection;
import com.ontotext.trree.sdk.ShutdownReason;
import com.ontotext.trree.sdk.impl.ServiceLocator;
import org.slf4j.LoggerFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

import javax.management.ObjectName;
import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Exercises repository lifetimes with dependencies loaded from the assembled plugin archive. */
public final class GeoSparqlPluginLifecycleProcess {
	private static final String INTERSECTS = "http://www.opengis.net/def/function/geosparql/sfIntersects";
	private static final String INTERSECTS_QUERY = """
			PREFIX geo: <http://www.opengis.net/ont/geosparql#>
			PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
			ASK {
			  VALUES (?left ?right) { ("POINT(1 1)"^^geo:wktLiteral "POINT(1 1)"^^geo:wktLiteral) }
			  FILTER(geof:sfIntersects(?left, ?right))
			}
			""";
	private static final String ENABLE_GEOSPARQL = """
			PREFIX plugin: <http://www.ontotext.com/plugins/geosparql#>
			INSERT DATA { [] plugin:enabled true }
			""";
	private static final String EPSG_WITHIN_QUERY = """
			PREFIX geo: <http://www.opengis.net/ont/geosparql#>
			PREFIX ex: <http://example.com/lifecycle/>
			ASK { ex:thing geo:sfWithin ex:container }
			""";

	private static final int QUERY_THREADS_PER_REPOSITORY = 4;
	private static final int QUERIES_PER_THREAD = 20;

	/** Projected CRSes that need the SIS_DATA EPSG database, each with a point inside its area of use. */
	private static final List<ProjectedPoint> PROJECTED_POINTS = List.of(
			new ProjectedPoint(3006, 522000, 6704000),
			new ProjectedPoint(2154, 652000, 6862000),
			new ProjectedPoint(3035, 4321000, 3210000),
			new ProjectedPoint(2193, 1750000, 5430000));

	private record ProjectedPoint(int epsgCode, long easting, long northing) {
		String insertGeometries() {
			return """
					PREFIX geo: <http://www.opengis.net/ont/geosparql#>
					PREFIX ex: <http://example.com/lifecycle/>
					INSERT DATA {
					  ex:container geo:hasDefaultGeometry ex:containerGeometry .
					  ex:containerGeometry geo:asWKT "%s"^^geo:wktLiteral .
					  ex:thing geo:hasDefaultGeometry ex:thingGeometry .
					  ex:thingGeometry geo:asWKT "%s"^^geo:wktLiteral .
					}
					""".formatted(
					wkt("POLYGON((%d %d,%d %d,%d %d,%d %d,%d %d))".formatted(
							easting - 10, northing - 10, easting - 10, northing + 10, easting + 10, northing + 10,
							easting + 10, northing - 10, easting - 10, northing - 10)),
					wkt("POINT(%d %d)".formatted(easting, northing)));
		}

		/** Asks whether two points 3 and 4 metres apart in this CRS are five metres apart. */
		String fiveMetreDistanceQuery() {
			return """
					PREFIX geo: <http://www.opengis.net/ont/geosparql#>
					PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
					PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
					ASK {
					  BIND(geof:distance("%s"^^geo:wktLiteral, "%s"^^geo:wktLiteral, uom:metre) AS ?distance)
					  FILTER(abs(?distance - 5.0) < 0.01)
					}
					""".formatted(wkt("POINT(%d %d)".formatted(easting, northing)),
					wkt("POINT(%d %d)".formatted(easting + 3, northing + 4)));
		}

		private String wkt(String geometry) {
			return "<http://www.opengis.net/def/crs/EPSG/0/" + epsgCode + "> " + geometry;
		}
	}

	private GeoSparqlPluginLifecycleProcess() {
	}

	public static void main(String[] arguments) throws Exception {
		Path work = Path.of(arguments[1]);
		System.setProperty("graphdb.home.work", work.resolve("work").toString());
		System.setProperty("register-external-plugins", arguments[2]);
		System.setProperty("graphdb.stats.default", "disabled");
		Config.reset();
		GraphDBRepositoryManager manager = new GraphDBRepositoryManager(work.toFile());
		manager.init();
		try {
			switch (arguments[0]) {
				case "deleted-repositories" -> assertDeletedRepositoriesCollected(manager);
				case "close-oldest" -> assertOtherRepositoryRemainsUsable(manager, false);
				case "close-newest" -> assertOtherRepositoryRemainsUsable(manager, true);
				case "restart" -> assertRestartRemainsUsable(manager);
				case "disable-enable" -> assertPluginCanBeEnabledAgain(manager);
				case "initialization-failure" -> assertFailedInitializationCollected(Path.of(arguments[2]), work);
				case "unstopped-sis" -> assertUnstoppedSisRetainsPluginLoader(Path.of(arguments[2]));
				case "indexed-epsg-repositories" -> assertIndexedEpsgRepositoriesCollected(manager);
				case "concurrent-epsg-repositories" -> assertRepositoriesUseEpsgDataConcurrently(manager);
				case "concurrent-repository-creation" -> assertRepositoriesCreatedTogetherShareOneRuntime(manager);
				case "delete-during-epsg-queries" -> assertFirstEpsgRepositoryDeletedWhileOthersQuery(manager);
				case "recreated-repository" -> assertRecreatedRepositoryIndexesEpsgData(manager);
				case "initialization-failure-recovery" ->
						assertRuntimeUsableAfterFailedInitializations(manager, Path.of(arguments[2]), work);
				case "isolated-epsg-database" -> assertIsolatedRuntimesCannotShareEpsgDatabase(Path.of(arguments[2]));
				case "separate-installations" -> assertSeparateInstallationsUseSeparateRuntimes(Path.of(arguments[2]));
				case "concurrent-runtime-creation" -> assertEntryPointsLoadedTogetherShareOneRuntime(Path.of(arguments[2]));
				case "first-epsg-loader-retained" -> assertFirstEpsgLoaderRetained(manager);
				case "last-loader-retained" -> assertLastQueriedLoaderRetained(manager);
				default -> throw new IllegalArgumentException("Unknown lifecycle scenario: " + arguments[0]);
			}
		} finally {
			manager.shutDown();
		}
	}

	private static Repository create(GraphDBRepositoryManager manager, String id) {
		OwlimSeRepositoryDescription description = new OwlimSeRepositoryDescription();
		description.setId(id);
		description.getOwlimSailConfig().setRuleset("empty");
		manager.addRepositoryConfig(description.getRepositoryConfig());
		Repository repository = manager.getRepository(id);
		if (!loader(repository).getClass().getName().contains("ExternalPluginLoader")) {
			throw new AssertionError("Expected the GraphDB external plugin loader");
		}
		return repository;
	}

	private static ClassLoader loader(Repository repository) {
		return ((OwlimSchemaRepository) ((SailRepository) repository).getSail()).getPlugin("GeoSPARQL")
				.getClass().getClassLoader();
	}

	/** Returns the plugin instance that serves the repository behind GraphDB's entry point. */
	private static Object servingPlugin(Repository repository) throws Exception {
		Object servingPlugin = forwardedPlugin(((OwlimSchemaRepository) ((SailRepository) repository).getSail())
				.getPlugin("GeoSPARQL"));
		if (servingPlugin.getClass().getClassLoader() != runtimeClassLoader()) {
			throw new AssertionError("Repositories must share one plugin runtime class loader");
		}
		return servingPlugin;
	}

	/** Returns the plugin instance that a GraphDB entry point forwards to. */
	private static Object forwardedPlugin(Object entryPoint) throws Exception {
		var plugin = entryPoint.getClass().getDeclaredMethod("plugin");
		plugin.setAccessible(true);
		return plugin.invoke(entryPoint);
	}

	private static ClassLoader runtimeClassLoader() throws Exception {
		var server = ManagementFactory.getPlatformMBeanServer();
		var names = server.queryNames(new ObjectName(GeoSparqlPluginFacade.RUNTIME_CLASS_LOADER_NAME_PREFIX + ",*"), null);
		if (names.size() != 1) {
			throw new AssertionError("Expected one plugin runtime class loader: " + names);
		}
		return server.getClassLoader(names.iterator().next());
	}

	/** Weak references to a deleted repository's plugin class loader and the plugin instance that served it. */
	private record Retired(WeakReference<ClassLoader> loader, WeakReference<Object> plugin) {
	}

	/**
	 * Selects the references that must be collected: every serving plugin and every repository class loader except
	 * the first {@code keptByDerby} and the last {@code keptByHost}. Derby keeps the class loader of the repository
	 * that first opened the EPSG database, and GraphDB keeps the class loader of the last repository a thread queried;
	 * the {@code first-epsg-loader-retained} and {@code last-loader-retained} scenarios cover both.
	 */
	private static List<WeakReference<?>> collectable(List<Retired> retired, int keptByDerby, int keptByHost) {
		List<WeakReference<?>> references = new ArrayList<>();
		for (int index = 0; index < retired.size(); index++) {
			references.add(retired.get(index).plugin());
			if (index >= keptByDerby && index < retired.size() - keptByHost) {
				references.add(retired.get(index).loader());
			}
		}
		return references;
	}

	/** Deletes the repository and returns weak references to its plugin class loader and serving plugin instance. */
	private static Retired delete(GraphDBRepositoryManager manager, String id, Repository repository)
			throws Exception {
		Retired retired = new Retired(new WeakReference<>(loader(repository)),
				new WeakReference<>(servingPlugin(repository)));
		if (!manager.removeRepository(id)) {
			throw new AssertionError("Repository must be deleted: " + id);
		}
		return retired;
	}

	private static Retired createAndDelete(GraphDBRepositoryManager manager, String id) throws Exception {
		return createAndDelete(manager, id, false);
	}

	private static Retired createAndDelete(GraphDBRepositoryManager manager, String id,
			boolean evaluateFunction) throws Exception {
		Repository repository = create(manager, id);
		if (evaluateFunction) {
			assertFunction(repository);
		}
		return delete(manager, id, repository);
	}

	private static void assertDeletedRepositoriesCollected(GraphDBRepositoryManager manager) throws Exception {
		List<Retired> retired = new ArrayList<>();
		for (int index = 0; index < 6; index++) {
			retired.add(createAndDelete(manager, "repository-" + index, index % 2 != 0));
		}
		ClassLoader currentFunctions = FunctionRegistry.getInstance().get(INTERSECTS).orElseThrow()
				.getClass().getClassLoader();
		if (currentFunctions != runtimeClassLoader()) {
			throw new AssertionError("Registered functions must belong to the shared plugin runtime");
		}
		awaitCollection(collectable(retired, 0, 1));
	}

	private static void assertIndexedEpsgRepositoriesCollected(GraphDBRepositoryManager manager) throws Exception {
		List<Retired> retired = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			retired.add(indexEpsgDataAndDelete(manager, "indexed-" + index));
		}
		awaitCollection(collectable(retired, 1, 1));
		assertDependencyThreadsStartedOnce();
	}

	/**
	 * Repositories that each use a different projected CRS build their indexes at the same time, then query
	 * concurrently, so first-time EPSG lookups and repeated lookups all go through the shared SIS and Derby runtime.
	 */
	private static void assertRepositoriesUseEpsgDataConcurrently(GraphDBRepositoryManager manager) throws Exception {
		List<Repository> repositories = new ArrayList<>();
		for (ProjectedPoint point : PROJECTED_POINTS) {
			Repository repository = create(manager, "concurrent-" + point.epsgCode());
			try (RepositoryConnection connection = repository.getConnection()) {
				connection.prepareUpdate(point.insertGeometries()).execute();
			}
			repositories.add(repository);
		}
		ExecutorService executor = Executors.newFixedThreadPool(repositories.size() * QUERY_THREADS_PER_REPOSITORY);
		try {
			CyclicBarrier indexing = new CyclicBarrier(repositories.size());
			List<Callable<Void>> enable = new ArrayList<>();
			for (Repository repository : repositories) {
				enable.add(() -> {
					indexing.await();
					try (RepositoryConnection connection = repository.getConnection()) {
						connection.prepareUpdate(ENABLE_GEOSPARQL).execute();
					}
					return null;
				});
			}
			awaitAll(executor.invokeAll(enable, 60, TimeUnit.SECONDS), "concurrent index builds");

			CyclicBarrier querying = new CyclicBarrier(repositories.size() * QUERY_THREADS_PER_REPOSITORY);
			List<Callable<Void>> queries = new ArrayList<>();
			for (int index = 0; index < repositories.size(); index++) {
				for (int thread = 0; thread < QUERY_THREADS_PER_REPOSITORY; thread++) {
					queries.add(epsgQueries(repositories.get(index), PROJECTED_POINTS.get(index), querying));
				}
			}
			awaitAll(executor.invokeAll(queries, 60, TimeUnit.SECONDS), "concurrent queries");
		} finally {
			executor.shutdownNow();
		}
		assertDependencyThreadsStartedOnce();
	}

	/** Runs the repository's property-relation and distance queries repeatedly once the barrier opens. */
	private static Callable<Void> epsgQueries(Repository repository, ProjectedPoint point, CyclicBarrier querying) {
		return () -> {
			querying.await();
			try (RepositoryConnection connection = repository.getConnection()) {
				for (int query = 0; query < QUERIES_PER_THREAD; query++) {
					assertEpsgQueries(connection, point);
				}
			}
			return null;
		};
	}

	/**
	 * Repositories created at the same instant share the published runtime, and each indexes its own projected CRS
	 * through it, which starts the runtime's dependency threads once.
	 */
	private static void assertRepositoriesCreatedTogetherShareOneRuntime(GraphDBRepositoryManager manager)
			throws Exception {
		CyclicBarrier creating = new CyclicBarrier(PROJECTED_POINTS.size());
		ExecutorService executor = Executors.newFixedThreadPool(PROJECTED_POINTS.size());
		List<Repository> repositories;
		try {
			List<Callable<Repository>> creations = new ArrayList<>();
			for (ProjectedPoint point : PROJECTED_POINTS) {
				creations.add(() -> {
					creating.await();
					return create(manager, "created-" + point.epsgCode());
				});
			}
			repositories = awaitAll(executor.invokeAll(creations, 60, TimeUnit.SECONDS),
					"concurrent repository creations");
		} finally {
			executor.shutdownNow();
		}
		ClassLoader runtime = runtimeClassLoader();
		for (int index = 0; index < repositories.size(); index++) {
			if (servingPlugin(repositories.get(index)).getClass().getClassLoader() != runtime) {
				throw new AssertionError("Repositories created together must share the published runtime");
			}
			indexEpsgData(repositories.get(index), PROJECTED_POINTS.get(index));
		}
		assertDependencyThreadsStartedOnce();
	}

	/**
	 * The repository whose calls created the runtime and opened the EPSG database is deleted while the other
	 * repositories query their projected CRSes on worker threads. Those queries keep succeeding, the deleted
	 * repository's plugin instance is released, and the runtime keeps its one set of dependency threads.
	 */
	private static void assertFirstEpsgRepositoryDeletedWhileOthersQuery(GraphDBRepositoryManager manager)
			throws Exception {
		ProjectedPoint firstPoint = PROJECTED_POINTS.getFirst();
		String firstId = "epsg-" + firstPoint.epsgCode();
		Repository first = indexEpsgData(manager, firstId, firstPoint);
		List<ProjectedPoint> points = PROJECTED_POINTS.subList(1, PROJECTED_POINTS.size());
		List<Repository> repositories = new ArrayList<>();
		for (ProjectedPoint point : points) {
			repositories.add(indexEpsgData(manager, "epsg-" + point.epsgCode(), point));
		}
		assertDependencyThreadsStartedOnce();
		ExecutorService executor = Executors.newFixedThreadPool(repositories.size() * QUERY_THREADS_PER_REPOSITORY);
		Retired retired;
		try {
			// The deleting thread joins the barrier, so the deletion starts once every query thread is querying.
			CyclicBarrier querying = new CyclicBarrier(repositories.size() * QUERY_THREADS_PER_REPOSITORY + 1);
			List<Future<Void>> queries = new ArrayList<>();
			for (int index = 0; index < repositories.size(); index++) {
				for (int thread = 0; thread < QUERY_THREADS_PER_REPOSITORY; thread++) {
					queries.add(executor.submit(epsgQueries(repositories.get(index), points.get(index), querying)));
				}
			}
			querying.await();
			retired = delete(manager, firstId, first);
			awaitAll(queries, "queries during repository deletion");
		} finally {
			executor.shutdownNow();
		}
		awaitCollection(List.of(retired.plugin()));
		assertDependencyThreadsStartedOnce();
	}

	private static <T> List<T> awaitAll(List<Future<T>> tasks, String work) throws Exception {
		List<T> results = new ArrayList<>();
		for (Future<T> task : tasks) {
			if (task.isCancelled()) {
				throw new AssertionError("The " + work + " did not finish in time");
			}
			try {
				results.add(task.get());
			} catch (ExecutionException failure) {
				throw new AssertionError("The " + work + " failed", failure.getCause());
			}
		}
		return results;
	}

	/** Apache SIS starts its daemon threads once for the class loader that owns it. */
	private static void assertDependencyThreadsStartedOnce() {
		Map<String, Long> threads = Thread.getAllStackTraces().keySet().stream()
				.collect(Collectors.groupingBy(thread -> thread.getName().replaceAll("\\d+", "#"), TreeMap::new,
						Collectors.counting()));
		System.out.println("thread-census " + threads);
		for (String name : List.of("ReferenceQueueConsumer", "DelayedExecutor")) {
			if (threads.getOrDefault(name, 0L) != 1) {
				throw new AssertionError("Expected one " + name + " thread: " + threads);
			}
		}
	}

	private static Retired indexEpsgDataAndDelete(GraphDBRepositoryManager manager, String id) throws Exception {
		return delete(manager, id, indexEpsgData(manager, id, PROJECTED_POINTS.getFirst()));
	}

	private static Repository indexEpsgData(GraphDBRepositoryManager manager, String id, ProjectedPoint point) {
		Repository repository = create(manager, id);
		indexEpsgData(repository, point);
		return repository;
	}

	/** Indexes the point's geometries and checks its property relation and distance. */
	private static void indexEpsgData(Repository repository, ProjectedPoint point) {
		try (RepositoryConnection connection = repository.getConnection()) {
			connection.prepareUpdate(point.insertGeometries()).execute();
			connection.prepareUpdate(ENABLE_GEOSPARQL).execute();
			assertEpsgQueries(connection, point);
		}
	}

	private static void assertEpsgQueries(RepositoryConnection connection, ProjectedPoint point) {
		if (!connection.prepareBooleanQuery(QueryLanguage.SPARQL, EPSG_WITHIN_QUERY).evaluate()) {
			throw new AssertionError("Indexed EPSG:" + point.epsgCode() + " property relation must match");
		}
		if (!connection.prepareBooleanQuery(QueryLanguage.SPARQL, point.fiveMetreDistanceQuery()).evaluate()) {
			throw new AssertionError("EPSG:" + point.epsgCode() + " distance must be five metres");
		}
	}

	/**
	 * A repository created with a deleted repository's ID indexes and queries EPSG data again. Every repository's
	 * index uses the runtime's one Lucene lock registry, so the deleted repository's index lock must have been released.
	 */
	private static void assertRecreatedRepositoryIndexesEpsgData(GraphDBRepositoryManager manager) throws Exception {
		Retired deleted = indexEpsgDataAndDelete(manager, "recreated");
		indexEpsgData(manager, "recreated", PROJECTED_POINTS.getFirst());
		awaitCollection(List.of(deleted.plugin()));
	}

	private static void awaitCollection(List<? extends WeakReference<?>> retired) throws Exception {
		for (int attempt = 0; attempt < 30; attempt++) {
			System.gc();
			Thread.sleep(100);
			if (retired.stream().allMatch(reference -> reference.refersTo(null))) {
				return;
			}
		}
		long retained = retired.stream().filter(reference -> !reference.refersTo(null)).count();
		throw new AssertionError("Deleted repositories' plugin class loaders or instances remain reachable: " + retained
				+ " / " + retired.size());
	}

	private static void assertOtherRepositoryRemainsUsable(GraphDBRepositoryManager manager,
			boolean closeNewest) throws Exception {
		Repository first = create(manager, "first");
		Repository second = create(manager, "second");
		Repository survivor = closeNewest ? first : second;
		String deleted = closeNewest ? "second" : "first";
		try (RepositoryConnection connection = survivor.getConnection()) {
			var query = connection.prepareBooleanQuery(QueryLanguage.SPARQL, INTERSECTS_QUERY);
			if (!query.evaluate()) {
				throw new AssertionError("GeoSPARQL filter function must match before repository deletion");
			}
			if (!manager.removeRepository(deleted)) {
				throw new AssertionError("Repository must be deleted: " + deleted);
			}
			if (!query.evaluate()) {
				throw new AssertionError("GeoSPARQL filter function must match after repository deletion");
			}
			assertFunction(survivor);
			createAndDelete(manager, "replacement");
			if (!query.evaluate()) {
				throw new AssertionError("GeoSPARQL filter function must match after function replacement");
			}
			assertFunction(survivor);
		}
	}

	private static void assertFailedInitializationCollected(Path plugins, Path work) throws Exception {
		awaitCollection(collectable(failInitializations(plugins, work), 0, 0));
	}

	/** Failed initializations leave the shared runtime usable by a repository created afterwards. */
	private static void assertRuntimeUsableAfterFailedInitializations(GraphDBRepositoryManager manager, Path plugins,
			Path work) throws Exception {
		failInitializations(plugins, work);
		Repository repository = indexEpsgData(manager, "recovered", PROJECTED_POINTS.getFirst());
		assertFunction(repository);
	}

	/** Fails initialization before and after geometry initialization. */
	private static List<Retired> failInitializations(Path plugins, Path work) throws Exception {
		List<Retired> retired = new ArrayList<>();
		for (int index = 0; index < 3; index++) {
			retired.add(failInitialization(plugins.resolve("geosparql-plugin"), work.resolve("failed-" + index), false));
			retired.add(failInitialization(plugins.resolve("geosparql-plugin"), work.resolve("failed-early-" + index), true));
		}
		return retired;
	}

	private static Retired failInitialization(Path pluginDirectory, Path data,
			boolean beforeGeometryInitialization) throws Exception {
		PluginBase plugin = locatePlugin(pluginDirectory);
		ClassLoader loader = plugin.getClass().getClassLoader();
		WeakReference<Object> serving = new WeakReference<>(forwardedPlugin(plugin));
		plugin.setDataDir(Files.createDirectories(data).toFile());
		plugin.setLogger(LoggerFactory.getLogger("GeoSPARQL"));
		Entities entities = (Entities) Proxy.newProxyInstance(Entities.class.getClassLoader(),
				new Class<?>[]{Entities.class}, (proxy, method, arguments) -> {
			if (method.getName().equals("put")) {
				if (beforeGeometryInitialization
						|| arguments[0].toString().equals("http://www.opengis.net/ont/geosparql#asWKT")) {
					throw new IllegalStateException("Entity allocation failed");
				}
				return 1L;
			}
			throw new AssertionError("Unexpected entity operation: " + method.getName());
		});
		PluginConnection connection = (PluginConnection) Proxy.newProxyInstance(PluginConnection.class.getClassLoader(),
				new Class<?>[]{PluginConnection.class}, (proxy, method, arguments) -> {
			if (method.getName().equals("getEntities")) {
				return entities;
			}
			throw new AssertionError("Unexpected connection operation: " + method.getName());
		});
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(loader);
		try {
			try {
				plugin.initialize(InitReason.DEFAULT, connection);
				throw new AssertionError("Initialization must reject entity allocation failure");
			} catch (IllegalStateException expected) {
				if (!expected.getMessage().equals("Entity allocation failed")) {
					throw expected;
				}
			}
			plugin.shutdown(ShutdownReason.DEFAULT);
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
			((Closeable) loader).close();
		}
		return new Retired(new WeakReference<>(loader), serving);
	}

	private static PluginBase locatePlugin(Path pluginDirectory) throws Exception {
		for (Plugin candidate : ServiceLocator.locateExternal(pluginDirectory.toFile(), Plugin.class)) {
			if (candidate.getName().equals("GeoSPARQL")) {
				return (PluginBase) candidate;
			}
		}
		throw new AssertionError("Expected the GeoSPARQL plugin from the archive");
	}

	private static void assertUnstoppedSisRetainsPluginLoader(Path plugins) throws Exception {
		Path pluginDirectory = plugins.resolve("geosparql-plugin");
		awaitCollection(List.of(initializeAndClose(pluginDirectory, true)));
		WeakReference<ClassLoader> unstopped = initializeAndClose(pluginDirectory, false);
		for (int attempt = 0; attempt < 10; attempt++) {
			System.gc();
			Thread.sleep(100);
		}
		if (unstopped.refersTo(null)) {
			throw new AssertionError("Apache SIS released the plugin class loader without Shutdown.stop");
		}
	}

	/** Initializes the archive's Jena and SIS runtime without plugin initialization, shuts down Jena, and closes the loader. */
	private static WeakReference<ClassLoader> initializeAndClose(Path pluginDirectory, boolean stopSis) throws Exception {
		ClassLoader loader = locatePlugin(pluginDirectory).getClass().getClassLoader();
		Class<?> adapter = Class.forName("com.ontotext.trree.geosparql.jena.JenaGeometryAdapter", true, loader);
		adapter.getMethod("initialize").invoke(null);
		Class.forName("org.apache.jena.sys.JenaSystem", true, loader).getMethod("shutdown").invoke(null);
		if (stopSis) {
			Class.forName("org.apache.sis.system.Shutdown", true, loader).getMethod("stop", Class.class)
					.invoke(null, adapter);
		}
		((Closeable) loader).close();
		return new WeakReference<>(loader);
	}

	/** Entry points from one plugin directory share a runtime; a separately installed directory loads its own. */
	private static void assertSeparateInstallationsUseSeparateRuntimes(Path plugins) throws Exception {
		Path installation = plugins.resolve("geosparql-plugin");
		Path separateInstallation = plugins.resolveSibling("separate-plugins").resolve("geosparql-plugin");
		try (var paths = Files.walk(installation)) {
			for (Path source : paths.toList()) {
				Path target = separateInstallation.resolve(installation.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.copy(source, target);
				}
			}
		}
		ClassLoader runtime = forwardedPlugin(locatePlugin(installation)).getClass().getClassLoader();
		if (forwardedPlugin(locatePlugin(installation)).getClass().getClassLoader() != runtime) {
			throw new AssertionError("Entry points from one plugin directory must share a runtime");
		}
		if (forwardedPlugin(locatePlugin(separateInstallation)).getClass().getClassLoader() == runtime) {
			throw new AssertionError("A separately installed plugin directory must load its own runtime");
		}
		var names = ManagementFactory.getPlatformMBeanServer()
				.queryNames(new ObjectName(GeoSparqlPluginFacade.RUNTIME_CLASS_LOADER_NAME_PREFIX + ",*"), null);
		if (names.size() != 2) {
			throw new AssertionError("Expected one runtime class loader per plugin directory: " + names);
		}
	}

	/** Entry points that GraphDB loads from one plugin directory at the same instant create and share one runtime. */
	private static void assertEntryPointsLoadedTogetherShareOneRuntime(Path plugins) throws Exception {
		Path installation = plugins.resolve("geosparql-plugin");
		int entryPoints = 8;
		CyclicBarrier loading = new CyclicBarrier(entryPoints);
		ExecutorService executor = Executors.newFixedThreadPool(entryPoints);
		List<ClassLoader> runtimes;
		try {
			List<Callable<ClassLoader>> loads = new ArrayList<>();
			for (int index = 0; index < entryPoints; index++) {
				loads.add(() -> {
					loading.await();
					return forwardedPlugin(locatePlugin(installation)).getClass().getClassLoader();
				});
			}
			runtimes = awaitAll(executor.invokeAll(loads, 60, TimeUnit.SECONDS), "concurrent entry point loads");
		} finally {
			executor.shutdownNow();
		}
		if (runtimes.stream().anyMatch(runtime -> runtime != runtimes.getFirst())) {
			throw new AssertionError("Entry points loaded together must share one runtime");
		}
		if (runtimeClassLoader() != runtimes.getFirst()) {
			throw new AssertionError("The published runtime must be the one the entry points use");
		}
	}

	private static void assertLastQueriedLoaderRetained(GraphDBRepositoryManager manager) throws Exception {
		List<Retired> retired = new ArrayList<>();
		for (int index = 0; index < 3; index++) {
			retired.add(createAndDelete(manager, "repository-" + index, true));
		}
		awaitCollection(collectable(retired, 0, 1));
		if (retired.getLast().loader().refersTo(null)) {
			throw new AssertionError("The last queried repository class loader was collected");
		}
	}

	private static void assertFirstEpsgLoaderRetained(GraphDBRepositoryManager manager) throws Exception {
		List<Retired> retired = new ArrayList<>();
		for (int index = 0; index < 3; index++) {
			retired.add(indexEpsgDataAndDelete(manager, "indexed-" + index));
		}
		awaitCollection(collectable(retired, 1, 1));
		if (retired.getFirst().loader().refersTo(null)) {
			throw new AssertionError("The first EPSG repository class loader was collected");
		}
	}

	/** A second class loader cannot open the EPSG database that another class loader's Derby engine has open. */
	private static void assertIsolatedRuntimesCannotShareEpsgDatabase(Path plugins) throws Exception {
		Path pluginDirectory = plugins.resolve("geosparql-plugin");
		ClassLoader first = locatePlugin(pluginDirectory).getClass().getClassLoader();
		ClassLoader second = locatePlugin(pluginDirectory).getClass().getClassLoader();
		if (!resolvesEpsg3006(first)) {
			throw new AssertionError("The first class loader must resolve EPSG:3006 from SIS_DATA");
		}
		if (resolvesEpsg3006(second)) {
			throw new AssertionError("A second Derby engine opened the EPSG database another engine has open");
		}
		String derbyLog = Files.readString(Path.of(System.getProperty("derby.stream.error.file")));
		if (!derbyLog.contains("XSDB6")) {
			throw new AssertionError("Expected Derby to report the database as already booted:\n" + derbyLog);
		}
	}

	private static boolean resolvesEpsg3006(ClassLoader loader) throws Exception {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			Class.forName("org.apache.sis.referencing.CRS", true, loader).getMethod("forCode", String.class)
					.invoke(null, "EPSG:3006");
			return true;
		} catch (InvocationTargetException unresolved) {
			return false;
		} finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static void assertPluginCanBeEnabledAgain(GraphDBRepositoryManager manager) throws Exception {
		Repository first = create(manager, "first");
		Repository second = create(manager, "second");
		var plugins = ((OwlimSchemaRepository) ((SailRepository) first).getSail()).getPluginManager();
		ClassLoader original = loader(first);
		try (RepositoryConnection connection = first.getConnection()) {
			connection.prepareUpdate("INSERT DATA { [] <http://www.ontotext.com/owlim/system#stopplugin> \"GeoSPARQL\" }")
					.execute();
			if (plugins.getPlugin("GeoSPARQL") != null) {
				throw new AssertionError("GeoSPARQL plugin must be disabled");
			}
			System.gc();
			assertFunction(second);
			connection.prepareUpdate("INSERT DATA { [] <http://www.ontotext.com/owlim/system#startplugin> \"GeoSPARQL\" }")
					.execute();
		}
		if (loader(first) != original) {
			throw new AssertionError("Plugin must be enabled with its existing class loader");
		}
		assertFunction(first);
		assertFunction(second);
	}

	private static void assertRestartRemainsUsable(GraphDBRepositoryManager manager) {
		Repository repository = create(manager, "restart");
		assertFunction(repository);
		manager.restartRepository("restart");
		assertFunction(manager.getRepository("restart"));
	}

	private static void assertFunction(Repository repository) {
		try (RepositoryConnection connection = repository.getConnection()) {
			if (!connection.prepareBooleanQuery(QueryLanguage.SPARQL, INTERSECTS_QUERY).evaluate()) {
				throw new AssertionError("GeoSPARQL filter function must match");
			}
		}
	}
}
