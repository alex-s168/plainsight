package dev.plainsight.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CutawayRegionTest {
	/** Camera 10 blocks north of and 6 above a player standing at (0.5, 64, 0.5); radius 1 at the camera, 4 at the player. */
	private static CutawayRegion region() {
		return new CutawayRegion(List.of(new CutawayRegion.Segment(0.5, 70, -9.5, 0.5, 65, 0.5, 64)), 1, 4, 15);
	}

	@Test
	void cutsAConeWideningTowardsThePlayer() {
		CutawayRegion r = region();
		// near the camera only a thin line is cut
		assertTrue(r.contains(0, 69, -9));
		assertFalse(r.contains(2, 69, -9), "outside the thin end");
		// around the player a wide area is cut, not just the line of sight
		assertTrue(r.contains(3, 65, 0), "3 blocks beside the player");
		assertTrue(r.contains(-3, 66, -1));
		assertFalse(r.contains(6, 65, 0), "outside the wide end");
	}

	@Test
	void keepsTheGroundAndWhatIsBehindThePlayer() {
		CutawayRegion r = region();
		assertFalse(r.contains(0, 63, 0), "the ground under the player");
		assertFalse(r.contains(3, 63, -1), "the ground around the player");
		assertTrue(r.contains(3, 65, 1), "right beside the player, slightly past it");
		assertFalse(r.contains(0, 66, 4), "behind the player");
		assertFalse(r.contains(0, 72, -12), "behind the camera");
	}

	@Test
	void nearIncludesTheGroundAndAMargin() {
		CutawayRegion r = region();
		assertTrue(r.near(0, 63, 0, 1.0), "the kept ground is still meshed without the cut's shadows");
		assertTrue(r.near(5, 65, 0, 1.5));
		assertFalse(r.near(9, 65, 0, 1.5));
	}

	@Test
	void sectionsCoverEveryAffectedBlock() {
		CutawayRegion r = new CutawayRegion(List.of(new CutawayRegion.Segment(-20.3, 90, 40.7, 13.2, 64, -7.1, 63)), 1.5, 5, 15);
		var sections = r.sections();
		for (int x = -30; x <= 25; x++)
			for (int y = 55; y <= 100; y++)
				for (int z = -15; z <= 50; z++)
					if (r.near(x, y, z, 2.5)) assertTrue(sections.contains(List.of(x >> 4, y >> 4, z >> 4)), "section of " + x + "," + y + "," + z);
	}

	@Test
	void differsOnlyWhenMovedBeyondTolerance() {
		CutawayRegion a = region();
		CutawayRegion b = new CutawayRegion(List.of(new CutawayRegion.Segment(0.6, 70, -9.5, 0.5, 65, 0.5, 64)), 1, 4, 15);
		CutawayRegion c = new CutawayRegion(List.of(new CutawayRegion.Segment(1.5, 70, -9.5, 0.5, 65, 0.5, 64)), 1, 4, 15);
		assertFalse(a.differs(b, 0.45));
		assertTrue(a.differs(c, 0.45));
		assertTrue(a.differs(CutawayRegion.EMPTY, 0.45));
		assertTrue(a.differs(new CutawayRegion(List.of(new CutawayRegion.Segment(0.5, 70, -9.5, 0.5, 65, 0.5, 64)), 1, 4, 7), 0.45), "light changed");
	}
}
