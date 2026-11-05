package dev.plainsight.editor.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TreeSearchTest {
	private static GroupNode group(SelectionTree t, String name, GroupNode parent) {
		GroupNode g = t.add(new GroupNode(), parent, -1);
		g.rename(name);
		return g;
	}

	private static PointNode point(SelectionTree t, String name, GroupNode parent) {
		PointNode p = t.add(new PointNode(0, 0, 0), parent, -1);
		p.rename(name);
		return p;
	}

	@Test
	void fuzzyMatch() {
		assertTrue(TreeSearch.matches("nprt", "Nether portal"));
		assertTrue(TreeSearch.matches("NETHER p", "nether portal"));
		assertTrue(TreeSearch.matches("", "anything"));
		assertFalse(TreeSearch.matches("ptn", "Nether portal"), "letters out of order");
		assertFalse(TreeSearch.matches("xyz", "Nether portal"));
	}

	@Test
	void showsMatchesWithTheirGroupsOpened() {
		SelectionTree t = new SelectionTree();
		GroupNode base = group(t, "base", null);
		GroupNode farms = group(t, "farms", base);
		PointNode iron = point(t, "iron farm", farms);
		PointNode wheat = point(t, "wheat", farms);
		PointNode spawn = point(t, "spawn", null);
		TreeSearch s = new TreeSearch(t.root(), "iron");
		assertEquals(1, s.matches());
		assertTrue(s.shows(base) && s.shows(farms) && s.shows(iron));
		assertFalse(s.shows(wheat) || s.shows(spawn));
		assertEquals(java.util.Set.of(base, farms), s.expand);
		// the groups' own state is not touched
		assertTrue(base.expanded && farms.expanded);
	}

	@Test
	void matchingGroupShowsAllOfItsContents() {
		SelectionTree t = new SelectionTree();
		GroupNode farms = group(t, "farms", null);
		PointNode wheat = point(t, "wheat", farms);
		TreeSearch s = new TreeSearch(t.root(), "farms");
		assertTrue(s.shows(farms) && s.shows(wheat));
		assertFalse(s.expand.contains(farms), "nothing inside matched: it is not opened");
	}

	@Test
	void detectedGroupsStayClosed() {
		SelectionTree t = new SelectionTree();
		GroupNode portals = group(t, "Nether portals", null);
		portals.detected = true;
		for (int i = 0; i < 1000; i++) point(t, "Nether portal", portals);
		GroupNode mine = group(t, "mine", null);
		PointNode own = point(t, "my nether portal", mine);
		TreeSearch s = new TreeSearch(t.root(), "nether portal");
		assertTrue(s.shows(portals) && s.shows(own));
		assertFalse(s.expand.contains(portals), "a detected group is not opened");
		assertTrue(s.expand.contains(mine));
	}
}
