package com.ontotext.trree.geosparql;

import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

public class GraphDbPackagingSmokeIT {
	private static final String REPOSITORY_ID = "packaging-smoke";
	private static final String DOCKERFILE_PROPERTY = "graphdb.packagingSmoke.dockerfile";
	private static final String PLUGIN_ZIP_PROPERTY = "graphdb.packagingSmoke.pluginZip";
	private static final String SIS_DATA_DIR_PROPERTY = "graphdb.packagingSmoke.sisDataDir";
	private static final String GRAPHDB_IMAGE_PROPERTY = "graphdb.packagingSmoke.graphdbImage";
	private static final String JAVA_IMAGE_PROPERTY = "graphdb.packagingSmoke.javaImage";

	/*
	 * The EPSG:3006 points reuse the GDB-10773 3-4-5 metre fixture. The surrounding polygon is the smallest
	 * additional data needed to exercise an indexed GeoSPARQL property relation with the same external CRS definition.
	 */
	private static final String INSERT_GEOMETRIES = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/packaging-smoke/>\n"
			+ "INSERT DATA {\n"
			+ "  ex:container a geo:Feature ; geo:hasDefaultGeometry ex:containerGeometry .\n"
			+ "  ex:containerGeometry a geo:Geometry ;\n"
			+ "    geo:asWKT \"POLYGON((0 0,0 4,4 4,4 0,0 0))\"^^geo:wktLiteral .\n"
			+ "  ex:thing a geo:Feature ; geo:hasDefaultGeometry ex:thingGeometry .\n"
			+ "  ex:thingGeometry a geo:Geometry ;\n"
			+ "    geo:asWKT \"POINT(1 1)\"^^geo:wktLiteral .\n"
			+ "  ex:projectedContainer a geo:Feature ; geo:hasDefaultGeometry ex:projectedContainerGeometry .\n"
			+ "  ex:projectedContainerGeometry a geo:Geometry ;\n"
			+ "    geo:asWKT \"<http://www.opengis.net/def/crs/EPSG/0/3006> "
			+ "POLYGON((521990 6703990,521990 6704010,522010 6704010,522010 6703990,521990 6703990))\""
			+ "^^geo:wktLiteral .\n"
			+ "  ex:projectedThing a geo:Feature ; geo:hasDefaultGeometry ex:projectedThingGeometry .\n"
			+ "  ex:projectedThingGeometry a geo:Geometry ;\n"
			+ "    geo:asWKT \"<http://www.opengis.net/def/crs/EPSG/0/3006> POINT(522000 6704000)\""
			+ "^^geo:wktLiteral .\n"
			+ "}";

	private static final String ENABLE_GEOSPARQL = ""
			+ "PREFIX plugin: <http://www.ontotext.com/plugins/geosparql#>\n"
			+ "INSERT DATA { [] plugin:enabled true }";

	private static final String WITHIN_QUERY = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/packaging-smoke/>\n"
			+ "ASK { ex:thing geo:sfWithin ex:container }";

	private static final String EPSG_3006_WITHIN_QUERY = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX ex: <http://example.com/packaging-smoke/>\n"
			+ "ASK { ex:projectedThing geo:sfWithin ex:projectedContainer }";

	private static final String EPSG_3006_DISTANCE_QUERY = ""
			+ "PREFIX geo: <http://www.opengis.net/ont/geosparql#>\n"
			+ "PREFIX geof: <http://www.opengis.net/def/function/geosparql/>\n"
			+ "PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>\n"
			+ "ASK {\n"
			+ "  BIND(geof:distance(\n"
			+ "    \"<http://www.opengis.net/def/crs/EPSG/0/3006> POINT Z (522000 6704000 100)\"^^geo:wktLiteral,\n"
			+ "    \"<http://www.opengis.net/def/crs/EPSG/0/3006> POINT Z (522003 6704004 100)\"^^geo:wktLiteral,\n"
			+ "    uom:metre\n"
			+ "  ) AS ?distance)\n"
			+ "  FILTER(abs(?distance - 5.0) < 0.01)\n"
			+ "}";

	private static final Pattern TRUE_BOOLEAN_RESULT =
			Pattern.compile("\\\"boolean\\\"\\s*:\\s*true");

	private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile()
			.withFileFromPath("Dockerfile", requiredPath(DOCKERFILE_PROPERTY, "packaging-smoke Dockerfile"))
			.withFileFromPath("geosparql-plugin-graphdb-plugin.zip",
					requiredPath(PLUGIN_ZIP_PROPERTY, "assembled plugin ZIP"))
			.withFileFromPath("sis-data",
					requiredDirectory(SIS_DATA_DIR_PROPERTY, "packaging-smoke Apache SIS data directory"))
			.withBuildArg("GRAPHDB_IMAGE", requiredProperty(GRAPHDB_IMAGE_PROPERTY))
			.withBuildArg("JAVA_IMAGE", requiredProperty(JAVA_IMAGE_PROPERTY));

	@SuppressWarnings("resource") // JUnit's class rule stops the container after the test class finishes.
	@ClassRule
	public static final GenericContainer<?> GRAPHDB = new GenericContainer<>(IMAGE)
			.withExposedPorts(7200)
			.waitingFor(Wait.forHttp("/rest/repositories")
					.forStatusCode(200)
					.withStartupTimeout(Duration.ofMinutes(2)));

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@Test
	public void assembledPluginUsesExternalCrsDataForIndexedPropertyRelationAndDistanceFunction() throws Exception {
		createRepository();
		executeUpdate(INSERT_GEOMETRIES, "insert smoke-test geometries");
		executeUpdate(ENABLE_GEOSPARQL, "enable GeoSPARQL and build its index");

		assertAskTrue(WITHIN_QUERY, "execute indexed geo:sfWithin query");
		assertAskTrue(EPSG_3006_WITHIN_QUERY, "execute EPSG:3006 indexed geo:sfWithin query");
		assertAskTrue(EPSG_3006_DISTANCE_QUERY, "evaluate EPSG:3006 five-metre distance function");
	}

	private void assertAskTrue(String query, String operation) throws Exception {
		HttpResponse<String> response = send(HttpRequest.newBuilder(
				endpoint("/repositories/" + REPOSITORY_ID + "?query=" + encode(query)))
				.header("Accept", "application/sparql-results+json")
				.timeout(Duration.ofMinutes(2))
				.GET()
				.build(), operation);

		assertTrue(failureMessage("Expected the ASK query to return true", response),
				TRUE_BOOLEAN_RESULT.matcher(response.body()).find());
	}

	private void createRepository() throws Exception {
		byte[] repositoryConfig = readResource("/graphdb-packaging-smoke-repository.ttl");
		String boundary = "GraphDbPackagingSmokeBoundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"config\"; "
				+ "filename=\"graphdb-packaging-smoke-repository.ttl\"\r\n"
				+ "Content-Type: text/turtle\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(repositoryConfig);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		send(HttpRequest.newBuilder(endpoint("/rest/repositories"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.timeout(Duration.ofMinutes(2))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build(), "create ephemeral GraphDB repository");
	}

	private void executeUpdate(String update, String operation) throws Exception {
		send(HttpRequest.newBuilder(endpoint("/repositories/" + REPOSITORY_ID + "/statements"))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(Duration.ofMinutes(2))
				.POST(HttpRequest.BodyPublishers.ofString("update=" + encode(update)))
				.build(), operation);
	}

	private HttpResponse<String> send(HttpRequest request, String operation) throws Exception {
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertTrue(failureMessage("Failed to " + operation, response),
				response.statusCode() >= 200 && response.statusCode() < 300);
		return response;
	}

	private URI endpoint(String path) {
		return URI.create("http://" + GRAPHDB.getHost() + ":" + GRAPHDB.getMappedPort(7200) + path);
	}

	private String failureMessage(String message, HttpResponse<String> response) {
		return message + ". HTTP status: " + response.statusCode()
				+ ", response body: " + response.body()
				+ "\nGraphDB container logs:\n" + GRAPHDB.getLogs();
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static byte[] readResource(String resourceName) throws IOException {
		try (InputStream input = GraphDbPackagingSmokeIT.class.getResourceAsStream(resourceName)) {
			if (input == null) {
				throw new IllegalStateException("Missing test resource: " + resourceName);
			}
			return input.readAllBytes();
		}
	}

	private static Path requiredPath(String propertyName, String description) {
		Path path = Paths.get(requiredProperty(propertyName)).toAbsolutePath().normalize();
		if (!Files.isRegularFile(path)) {
			throw new IllegalStateException("Missing " + description + " at " + path
					+ ". Run this test with mvn -Pgraphdb-packaging-smoke verify.");
		}
		return path;
	}

	private static Path requiredDirectory(String propertyName, String description) {
		Path path = Paths.get(requiredProperty(propertyName)).toAbsolutePath().normalize();
		if (!Files.isDirectory(path)) {
			throw new IllegalStateException("Missing " + description + " at " + path
					+ ". Run this test with mvn -Pgraphdb-packaging-smoke verify.");
		}
		return path;
	}

	private static String requiredProperty(String propertyName) {
		String value = System.getProperty(propertyName);
		if (value == null || value.trim().isEmpty()) {
			throw new IllegalStateException("Missing required system property: " + propertyName
					+ ". Run this test with mvn -Pgraphdb-packaging-smoke verify.");
		}
		return value;
	}
}
