package dev.plainsight.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CutawayRegionTest {
	/** Camera 10 blocks north of and 6 above a player standing at (0.5, 64, 0.5). */
	private static CutawayRegion region() {
		return new CutawayRegion(List.of(new CutawayRegion.Segment(0.5, 70, -9.5, 0.5, 65, 0.5, 64)), 2);
	}

	@Test
	void cutsBlocksOnTheLineOfSight() {
		CutawayRegion r = region();
		// halfway along the line: (0.5, 67.5, -4.5) is the centre of block (0, 67, -5)
		assertTrue(r.contains(0, 67, -5));
		assertTrue(r.contains(1, 67, -5), "within the radius");
		assertFalse(r.contains(4, 67, -5), "outside the radius");
	}

	@Test
	void keepsTheTargetSurroundingsAndGround() {
		CutawayRegion r = region();
		assertFalse(r.contains(0, 65, 0), "the block the target is in");
		assertFalse(r.contains(0, 63, 0), "the ground under the target");
		assertFalse(r.contains(0, 72, -12), "behind the camera");
	}

	@Test
	void sectionsCoverEveryCutBlock() {
		CutawayRegion r = new CutawayRegion(List.of(new CutawayRegion.Segment(-20.3, 90, 40.7, 13.2, 64, -7.1, 63)), 3);
		var sections = r.sections();
		for (int x = -30; x <= 20; x++)
			for (int y = 55; y <= 100; y++)
				for (int z = -15; z <= 50; z++)
					if (r.contains(x, y, z)) assertTrue(sections.contains(List.of(x >> 4, y >> 4, z >> 4)), "section of " + x + "," + y + "," + z);
	}

	@Test
	void differsOnlyWhenMovedBeyondTolerance() {
		CutawayRegion a = region();
		CutawayRegion b = new CutawayRegion(List.of(new CutawayRegion.Segment(0.6, 70, -9.5, 0.5, 65, 0.5, 64)), 2);
		CutawayRegion c = new CutawayRegion(List.of(new CutawayRegion.Segment(1.5, 70, -9.5, 0.5, 65, 0.5, 64)), 2);
		assertFalse(a.differs(b, 0.45));
		assertTrue(a.differs(c, 0.45));
		assertTrue(a.differs(CutawayRegion.EMPTY, 0.45));
	}
}
