package com.ontotext.trree.geosparql;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class CandidateEntityTest {
	@Test
	public void candidateEntityOwnsAnImmutableDefensiveCopy() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt("POINT(0 0)");
		Set<SourceGeometryLiteral> input = new LinkedHashSet<>();
		input.add(source);

		CandidateEntity candidate = new CandidateEntity(7, input);
		input.clear();

		assertEquals(7, candidate.entityId());
		assertEquals(List.of(source), candidate.matchingSourceGeometryLiterals());
		assertThrows(UnsupportedOperationException.class,
				() -> candidate.matchingSourceGeometryLiterals().add(source));
	}

	@Test
	public void candidateEntityRejectsInvalidState() {
		SourceGeometryLiteral source = SourceGeometryLiteral.fromWkt("POINT(0 0)");

		assertThrows(IllegalArgumentException.class, () -> new CandidateEntity(0, Set.of(source)));
		assertThrows(NullPointerException.class, () -> new CandidateEntity(1, null));
		assertThrows(IllegalArgumentException.class, () -> new CandidateEntity(1, Set.of()));
		Set<SourceGeometryLiteral> sourceWithNull = new LinkedHashSet<>();
		sourceWithNull.add(source);
		sourceWithNull.add(null);
		assertThrows(NullPointerException.class, () -> new CandidateEntity(1, sourceWithNull));
	}
}
