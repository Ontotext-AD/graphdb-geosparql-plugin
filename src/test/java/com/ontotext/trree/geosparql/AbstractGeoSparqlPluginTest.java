package com.ontotext.trree.geosparql;

import com.ontotext.graphdb.Config;
import com.ontotext.graphdb.GraphDBRepositoryManager;
import com.ontotext.test.RepositorySetup;
import com.ontotext.test.TemporaryLocalFolder;
import com.ontotext.test.TemporaryRepositoryManager;
import com.ontotext.test.utils.OwlimSeRepositoryDescription;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.io.FileUtil;
import org.eclipse.rdf4j.common.io.IOUtil;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by avataar on 20.04.15..
 */
public abstract class AbstractGeoSparqlPluginTest {
	static {
		// Don't care about assertions in this class as it breaks testing
		AbstractGeoSparqlPluginTest.class.getClassLoader().setClassAssertionStatus("org.locationtech.spatial4j.shape.jts.JtsGeometry", false);
	}

	protected static final ValueFactory VF = SimpleValueFactory.getInstance();

	@ClassRule
	public static TemporaryLocalFolder tmpFolder = new TemporaryLocalFolder();

	@Rule
	public TemporaryRepositoryManager tmpManager = new TemporaryRepositoryManager(new RepositorySetup() {
		@Override
		public void setup(GraphDBRepositoryManager repositoryManager) throws Exception {
			OwlimSeRepositoryDescription repositoryDescription = new OwlimSeRepositoryDescription();
			repositoryDescription.getOwlimSailConfig().setRuleset("owl-horst");

			RepositoryConfig repositoryConfig = repositoryDescription.getRepositoryConfig();
			repositoryManager.addRepositoryConfig(repositoryConfig);

			repository = repositoryManager.getRepository(repositoryConfig.getID());

			connection = repository.getConnection();
		}
	});

	protected Repository repository;
	protected RepositoryConnection connection;

	@BeforeClass
	public static void setWorkDir() {
		System.setProperty("graphdb.home.work", String.valueOf(tmpFolder.getRoot()));
		Config.reset();
	}
	@AfterClass
	public static void resetWorkDir() {
		System.clearProperty("graphdb.home.work");
		Config.reset();
	}

	@After
	public void closeConn() throws RepositoryException {
		connection.close();
	}

	protected void restartRepository() throws RepositoryException {
		connection.close();
		repository.shutDown();
		repository.init();
		connection = repository.getConnection();
	}

	protected void restartRepositoryAndDeleteIndex() throws Exception {
		connection.close();
		repository.shutDown();
        FileUtil.deleteDir(getGeoSparqlStorageDir());
		repository.init();
		connection = repository.getConnection();
	}

    protected File getGeoSparqlStorageDir() {
        return new File(repository.getDataDir(), "storage/GeoSPARQL");
    }

    protected List<Value> executeSparqlQueryWithResult(String query, String binding) throws Exception {
		List<Value> result = new ArrayList<Value>();
		try (TupleQueryResult tqr = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
			while (tqr.hasNext()) {
				BindingSet bs = tqr.next();
				result.add(bs.getValue(binding));
			}
		}
		return result;
	}

	protected void executeSparqlUpdateQuery(String query) throws RDF4JException {
        Update uq = connection.prepareUpdate(QueryLanguage.SPARQL, query);
        uq.execute();
        connection.commit();
	}

    protected List<Value> executeSparqlQueryWithResultFromFile(String fileName, String binding) throws Exception {
        String query = IOUtil.readString(
				getClass().getResourceAsStream("/" + fileName + ".sparql"));

        return executeSparqlQueryWithResult(query, binding);
    }

    protected void executeSparqlUpdateQueryFromFile(String fileName) throws Exception {
        String query = IOUtil.readString(
                getClass().getResourceAsStream("/" + fileName + ".sparql"));

        executeSparqlUpdateQuery(query);
    }

    protected void importData(String fileName, RDFFormat rdfFormat) throws RepositoryException, RDFParseException, IOException {
        connection.add(getClass().getResourceAsStream("/" + fileName),
				"urn:base", rdfFormat);
    }

	protected void forceReindex() {
		executePluginControl(GeoSparqlPlugin.FORCE_REINDEX_PREDICATE_IRI, VF.createLiteral(true));
	}

	protected void enablePlugin() {
		executePluginControl(GeoSparqlPlugin.ENABLED_PREDICATE_IRI, VF.createLiteral(true));
	}

	protected void disablePlugin() {
		executePluginControl(GeoSparqlPlugin.ENABLED_PREDICATE_IRI, VF.createLiteral(false));
	}

	protected void executePluginControl(IRI command, Literal argument) {
		connection.begin();
		Update uq = connection.prepareUpdate(QueryLanguage.SPARQL,
				String.format("INSERT DATA { _:s <%s> %s }", command.stringValue(), argument.toString()));
		uq.execute();
		connection.commit();
	}
}
