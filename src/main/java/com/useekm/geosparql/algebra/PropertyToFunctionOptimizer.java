/*
 * Copyright 2010 by TalkingTrends (Amsterdam, The Netherlands)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://opensahara.com/licenses/apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.useekm.geosparql.algebra;

import com.useekm.indexing.GeoConstants;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.*;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

import java.util.*;

/**
 * A query optimizer that rewrites queries with GeoSPARQL virtual properties into the equivalent query with a FILTER function.
 */
public class PropertyToFunctionOptimizer implements QueryOptimizer {
    private static final Collection<IRI> PROPERTY_TO_FUNCTION = new HashSet<IRI>();
    private static final String CONST_ASWKT = "geo__aswkt__";
    static {
        PROPERTY_TO_FUNCTION.add(GeoConstants.GEO_DIMENSION);
        PROPERTY_TO_FUNCTION.add(GeoConstants.GEO_COORDINATE_DIMENSION);
        PROPERTY_TO_FUNCTION.add(GeoConstants.GEO_SPATIAL_DIMENSION);
        PROPERTY_TO_FUNCTION.add(GeoConstants.GEO_IS_EMPTY);
        PROPERTY_TO_FUNCTION.add(GeoConstants.GEO_IS_SIMPLE);
    }
    private static final Map<IRI, IRI> PROPERTY_RELATION = new HashMap<IRI, IRI>();
    static {
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_EQUALS, GeoConstants.GEOF_SF_EQUALS);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_DISJOINT, GeoConstants.GEOF_SF_DISJOINT);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_INTERSECTS, GeoConstants.GEOF_SF_INTERSECTS);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_TOUCHES, GeoConstants.GEOF_SF_TOUCHES);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_CROSSES, GeoConstants.GEOF_SF_CROSSES);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_WITHIN, GeoConstants.GEOF_SF_WITHIN);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_CONTAINS, GeoConstants.GEOF_SF_CONTAINS);
        PROPERTY_RELATION.put(GeoConstants.GEO_SF_OVERLAPS, GeoConstants.GEOF_SF_OVERLAPS);

        PROPERTY_RELATION.put(GeoConstants.GEO_EH_EQUALS, GeoConstants.GEO_EH_EQUALS);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_DISJOINT, GeoConstants.GEO_EH_DISJOINT);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_MEET, GeoConstants.GEO_EH_MEET);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_OVERLAP, GeoConstants.GEO_EH_OVERLAP);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_COVERS, GeoConstants.GEO_EH_COVERS);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_COVERED_BY, GeoConstants.GEO_EH_COVERED_BY);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_INSIDE, GeoConstants.GEO_EH_INSIDE);
        PROPERTY_RELATION.put(GeoConstants.GEO_EH_CONTAINS, GeoConstants.GEO_EH_CONTAINS);

        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_EQ, GeoConstants.GEO_RCC8_EQ);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_DC, GeoConstants.GEO_RCC8_DC);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_EC, GeoConstants.GEO_RCC8_EC);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_PO, GeoConstants.GEO_RCC8_PO);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_TPPI, GeoConstants.GEO_RCC8_TPPI);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_TPP, GeoConstants.GEO_RCC8_TPP);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_NTPP, GeoConstants.GEO_RCC8_NTPP);
        PROPERTY_RELATION.put(GeoConstants.GEO_RCC8_NTPPI, GeoConstants.GEO_RCC8_NTPPI);
    }
    private static final Collection<IRI> ALL_PROPERTIES = new HashSet<IRI>();
    static {
        ALL_PROPERTIES.addAll(PROPERTY_TO_FUNCTION);
        ALL_PROPERTIES.addAll(PROPERTY_RELATION.keySet());
    }

    @Override public void optimize(TupleExpr tupleExpr, Dataset dataset, BindingSet bindings) {
        PatternVisitor visitor = new PatternVisitor(bindings);
        tupleExpr.visit(visitor);

        int n = 0;
        //TODO (Optimization): when virtual properties share a subject, the rewrite should let them share an object 
        for (StatementPattern pattern: visitor.patterns) {
            IRI pred = (IRI)getValue(pattern.getPredicateVar(), bindings);
            if (PROPERTY_TO_FUNCTION.contains(pred))
                rewriteProperty(pattern, bindings, pred, n++);
            else
                rewriteRelation(pattern, pred, n++);
        }
    }

    private void rewriteProperty(StatementPattern pattern, BindingSet bindings, IRI pred, int n) {
        String geo = "geo__priv__" + n + "__";
        Var object = pattern.getObjectVar();
        Value objValue = getValue(object, bindings);
        TupleExpr replacement = new StatementPattern(pattern.getSubjectVar(), new Var(CONST_ASWKT, GeoConstants.GEO_AS_WKT), new Var(geo));
        if (objValue == null)
            replacement = new Extension(
                replacement,
                new ExtensionElem(new FunctionCall(pred.stringValue(), new Var(geo)), object.getName()));
        else
            replacement = new Filter(replacement, new Compare(new FunctionCall(pred.stringValue(), new Var(geo)), new ValueConstant(objValue)));
        pattern.replaceWith(replacement);
    }

    private void rewriteRelation(StatementPattern pattern, IRI pred, int n) {
        String geo1 = "geo__priv__" + n + "a__";
        String geo2 = "geo__priv__" + n + "b__";
        String function = PROPERTY_RELATION.get(pred).stringValue();
        TupleExpr replacement = new Filter(
            new Join(
                new StatementPattern(pattern.getSubjectVar(), new Var(CONST_ASWKT, GeoConstants.GEO_AS_WKT), new Var(geo1)),
                new StatementPattern(pattern.getObjectVar(), new Var(CONST_ASWKT, GeoConstants.GEO_AS_WKT), new Var(geo2))),
            new FunctionCall(function, new Var(geo1), new Var(geo2)));
        pattern.replaceWith(replacement);
    }

    private static Value getValue(Var var, BindingSet bindings) {
        Value val = var.getValue();
        if (val == null)
            val = bindings.getValue(var.getName());
        return val;
    }

    private static final class PatternVisitor extends AbstractQueryModelVisitor<RuntimeException> {
        private BindingSet bindings;
        private List<StatementPattern> patterns = new ArrayList<StatementPattern>();

        private PatternVisitor(BindingSet bindings) {
            this.bindings = bindings;
        }

        @Override
        public void meet(StatementPattern pattern) {
            Var pred = pattern.getPredicateVar();
            Value predVal = getValue(pred, bindings);
            if (predVal instanceof IRI && ALL_PROPERTIES.contains(predVal))
                patterns.add(pattern);
        }
    }
}