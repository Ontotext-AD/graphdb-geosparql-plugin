package com.ontotext.trree.geosparql.jena;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.lang.ref.SoftReference;

/**
 * The two geometry literals most recently converted on the current thread.
 *
 * <p>A GeoSPARQL function is called once per solution, usually with one geometry that changes and one that is the
 * same for every solution. Keeping the last two literals converts the unchanging one once, and lets it keep the
 * operand that Jena derives from it for another CRS.
 *
 * <p>A literal is kept by its text and datatype alone, and its source geometry literal is built from a copy of
 * those. A value passed to a function can belong to the repository connection of its query and become unreadable
 * when that connection closes, so none is kept. Each thread has its own literals, held softly so that a large
 * geometry is released when memory is short. A kept literal holds its parsed geometry and one transformed copy for
 * each CRS it has been evaluated against as a right operand.
 */
final class RecentSourceGeometryLiterals {
	private static final ThreadLocal<SoftReference<RecentSourceGeometryLiterals>> RECENT = new ThreadLocal<>();
	private static final ValueFactory VALUES = SimpleValueFactory.getInstance();

	private Kept latest;
	private Kept earlier;

	private record Kept(String label, String datatype, SourceGeometryLiteral source) {
		boolean is(String otherLabel, String otherDatatype) {
			return label.equals(otherLabel) && datatype.equals(otherDatatype);
		}
	}

	private RecentSourceGeometryLiterals() {
	}

	/** Returns the source geometry literal of a value, converting the value unless it is one of the last two. */
	static SourceGeometryLiteral of(Value value) {
		if (!(value instanceof Literal literal) || literal.getLanguage().isPresent()) {
			return JenaGeometryAdapter.toSourceGeometryLiteral(value, true);
		}
		IRI datatype = literal.getDatatype();
		if (datatype == null) {
			return JenaGeometryAdapter.toSourceGeometryLiteral(value, true);
		}
		SoftReference<RecentSourceGeometryLiterals> reference = RECENT.get();
		RecentSourceGeometryLiterals recent = reference == null ? null : reference.get();
		if (recent == null) {
			recent = new RecentSourceGeometryLiterals();
			RECENT.set(new SoftReference<>(recent));
		}
		return recent.get(literal.getLabel(), datatype.stringValue());
	}

	private SourceGeometryLiteral get(String label, String datatype) {
		if (latest != null && latest.is(label, datatype)) {
			return latest.source();
		}
		Kept kept = earlier != null && earlier.is(label, datatype)
				? earlier
				: new Kept(label, datatype, JenaGeometryAdapter.toSourceGeometryLiteral(
						VALUES.createLiteral(label, VALUES.createIRI(datatype)), true));
		earlier = latest;
		latest = kept;
		return kept.source();
	}
}
