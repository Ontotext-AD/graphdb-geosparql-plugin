package com.ontotext.trree.geosparql;

import com.ontotext.trree.sdk.PluginBase;
import org.junit.Test;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GeoSparqlPluginFacadeTest {
	private static final String SDK_PACKAGE = PluginBase.class.getPackageName();

	@Test
	public void entryPointImplementsTheGraphDbSdkInterfacesOfThePlugin() {
		assertEquals(sdkInterfaces(GeoSparqlPlugin.class), sdkInterfaces(GeoSparqlPluginFacade.class));
	}

	@Test
	public void entryPointOverridesEveryGraphDbSdkMethodOfThePlugin() {
		for (Method method : sdkMethods(GeoSparqlPlugin.class)) {
			try {
				GeoSparqlPluginFacade.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
			} catch (NoSuchMethodException e) {
				fail("The GraphDB entry point does not override " + method);
			}
		}
	}

	@Test
	public void runtimePublishedByAnotherRepositoryIsUsedAndTheCreatedOneClosed() throws Exception {
		ClassLoader published = new URLClassLoader(new URL[0]);
		ObjectName name = new ObjectName(GeoSparqlPluginFacade.RUNTIME_CLASS_LOADER_NAME_PREFIX + ",location=\"jar\"");
		MBeanServer server = (MBeanServer) Proxy.newProxyInstance(MBeanServer.class.getClassLoader(),
				new Class<?>[]{MBeanServer.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "isRegistered" -> false;
					case "registerMBean" -> throw new InstanceAlreadyExistsException(name.toString());
					case "getClassLoader" -> published;
					default -> throw new AssertionError("Unexpected MBean server call: " + method.getName());
				});
		ClosingClassLoader created = new ClosingClassLoader();

		assertSame(published, GeoSparqlPluginFacade.publishedRuntimeClassLoader(server, name, () -> created));
		assertTrue("The class loader that lost the publication must be closed", created.closed);
	}

	private static final class ClosingClassLoader extends URLClassLoader {
		private boolean closed;

		ClosingClassLoader() {
			super(new URL[0]);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	private static Set<Class<?>> sdkInterfaces(Class<?> type) {
		Set<Class<?>> interfaces = new LinkedHashSet<>();
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			collectSdkInterfaces(current.getInterfaces(), interfaces);
		}
		return interfaces;
	}

	private static void collectSdkInterfaces(Class<?>[] candidates, Set<Class<?>> interfaces) {
		for (Class<?> candidate : candidates) {
			if (candidate.getPackageName().equals(SDK_PACKAGE)) {
				interfaces.add(candidate);
			}
			collectSdkInterfaces(candidate.getInterfaces(), interfaces);
		}
	}

	/** Methods GraphDB can call: every SDK interface method, including defaults, and the public {@link PluginBase} API. */
	private static List<Method> sdkMethods(Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Class<?> sdkInterface : sdkInterfaces(type)) {
			for (Method method : sdkInterface.getMethods()) {
				if (!Modifier.isStatic(method.getModifiers())) {
					methods.add(method);
				}
			}
		}
		for (Method method : PluginBase.class.getDeclaredMethods()) {
			if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
					&& !method.getName().equals("toString")) {
				methods.add(method);
			}
		}
		return methods;
	}
}
