package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.IndexGeometry;
import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;

public final class TestIndexGeometries {
	private TestIndexGeometries() {
	}

	public static IndexGeometry fromSource(SourceGeometryLiteral sourceGeometryLiteral) {
		return IndexGeometry.fromSourceGeometryLiteral(sourceGeometryLiteral);
	}

	public static IndexGeometry fromWkt(String wkt) {
		return fromSource(SourceGeometryLiteral.fromWkt(wkt));
	}
}
