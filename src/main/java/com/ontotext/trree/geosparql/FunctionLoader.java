package com.ontotext.trree.geosparql;

import org.eclipse.rdf4j.query.algebra.evaluation.function.Function;
import org.eclipse.rdf4j.query.algebra.evaluation.function.FunctionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Created by avataar on 16.06.2015..
 */
public class FunctionLoader {
    private static Logger LOGGER = LoggerFactory.getLogger(FunctionLoader.class);

    public static void loadFunctionsInPackage(String pkg) {
        final FunctionRegistry functionRegistry = FunctionRegistry.getInstance();
        final ServiceLoader<Function> sl = ServiceLoader.load(Function.class, FunctionLoader.class.getClassLoader());
        sl.reload();

        int count = 0;
        for (Function f : sl) {
            if (f.getClass().getPackage().getName().equals(pkg)) {
                functionRegistry.add(f);
                LOGGER.debug("Registering function {} from package {}.", f.getURI(), pkg);
                count++;
            }
        }

        LOGGER.debug("Registered {} functions from package {}.", count, pkg);
    }
}
