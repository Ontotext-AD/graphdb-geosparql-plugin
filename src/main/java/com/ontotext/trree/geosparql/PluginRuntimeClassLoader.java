package com.ontotext.trree.geosparql;

import javax.management.loading.PrivateClassLoader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Loads the GeoSPARQL plugin and its bundled dependencies once per JVM.
 *
 * <p>Classes and resources follow GraphDB's external plugin class loader: bundled jars take precedence over the host,
 * so the plugin's Lucene, Jena, Apache SIS and Derby are used even when the host provides other versions. Classes the
 * plugin does not bundle, including the GraphDB SDK, RDF4J and SLF4J, come from the host. Service descriptors for
 * Lucene and ANTLR come only from the plugin jars, as GraphDB excludes host copies of those for external plugins.
 *
 * <p>The class loader is published as an MBean so every repository's entry point can find it. It is private to that
 * lookup and does not join the MBean server's class loader repository.
 */
public final class PluginRuntimeClassLoader extends URLClassLoader implements PluginRuntimeClassLoaderMBean,
		PrivateClassLoader {
	private static final List<String> PLUGIN_ONLY_RESOURCE_NAME_PARTS = List.of("org.apache.lucene", "org.antlr.v4.runtime");

	static {
		registerAsParallelCapable();
	}

	public PluginRuntimeClassLoader(URL[] jars, ClassLoader host) {
		super("GeoSPARQL plugin runtime", jars, host);
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> type = findLoadedClass(name);
			if (type == null) {
				try {
					type = findClass(name);
				} catch (ClassNotFoundException notBundled) {
					return super.loadClass(name, resolve);
				}
			}
			if (resolve) {
				resolveClass(type);
			}
			return type;
		}
	}

	@Override
	public URL getResource(String name) {
		URL resource = findResource(name);
		return resource != null ? resource : super.getResource(name);
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		List<URL> resources = Collections.list(findResources(name));
		if (PLUGIN_ONLY_RESOURCE_NAME_PARTS.stream().noneMatch(name::contains)) {
			resources.addAll(Collections.list(getParent().getResources(name)));
		}
		return Collections.enumeration(resources);
	}
}
