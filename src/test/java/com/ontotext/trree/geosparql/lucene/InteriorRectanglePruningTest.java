package com.ontotext.trree.geosparql.lucene;

import org.junit.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Joining the cells of the remainder into the rectangles that become clauses of the candidate query. */
public class InteriorRectanglePruningTest {
	@Test
	public void cellsSharingWholeSidesAreJoinedIntoOneRectangle() {
		List<Envelope> block = List.of(new Envelope(1, 2, 1, 2), new Envelope(0, 1, 0, 1),
				new Envelope(1, 2, 0, 1), new Envelope(0, 1, 1, 2));

		assertEquals(List.of(new Envelope(0, 2, 0, 2)), InteriorRectanglePruning.joined(block));
	}

	/** A rectangle joining these would cover points that none of the cells covers. */
	@Test
	public void cellsMeetingAtACornerOrAlongPartOfASideStaySeparate() {
		List<Envelope> corner = List.of(new Envelope(0, 1, 0, 1), new Envelope(1, 2, 1, 2));
		List<Envelope> partOfASide = List.of(new Envelope(0, 1, 0, 2), new Envelope(1, 2, 0, 1));

		for (List<Envelope> cells : List.of(corner, partOfASide)) {
			assertEquals(new HashSet<>(cells), new HashSet<>(InteriorRectanglePruning.joined(cells)));
		}
	}

	@Test
	public void framesAroundAnInsetJoinTheirNeighbours() {
		// The frame of the cell (0, 16) x (0, 16) around its inset (1, 15) x (1, 15), with the cell to its left.
		List<Envelope> cells = List.of(new Envelope(0, 1, 0, 16), new Envelope(15, 16, 0, 16),
				new Envelope(0, 16, 0, 1), new Envelope(0, 16, 15, 16), new Envelope(-16, 0, 0, 16));

		assertEquals(new HashSet<>(List.of(new Envelope(-16, 1, 0, 16), new Envelope(15, 16, 0, 16),
						new Envelope(0, 16, 0, 1), new Envelope(0, 16, 15, 16))),
				new HashSet<>(InteriorRectanglePruning.joined(cells)));
	}
}
