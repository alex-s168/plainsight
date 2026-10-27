package dev.plainsight.editor.selection;

import dev.plainsight.editor.math.OrientedBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SelectionTreeTest {
	private static BoxNode box(int size) {
		return new BoxNode(OrientedBox.ofBlocks(0, 0, 0, size - 1, 0, 0));
	}

	private static List<String> labels(GroupNode g) {
		return g.children().stream().map(SelectionNode::label).toList();
	}

	/** The example from the specification. */
	@Test
	void exampleTree() {
		SelectionTree t = new SelectionTree();
		GroupNode target = new GroupNode();
		target.rename("target");
		t.add(target);
		t.add(box(500), target, -1);
		GroupNode g1 = t.add(new GroupNode(), target, -1);
		t.add(box(23111), g1, -1);
		t.add(box(10), g1, -1);
		GroupNode g2 = t.add(new GroupNode());
		t.add(new EntityNode(UUID.randomUUID(), "player", "Steve"), g2, -1);
		t.add(new EntityNode(UUID.randomUUID(), "player", "Alex"), g2, -1);
		t.add(box(20), g2, -1);

		assertEquals(List.of("target", "group 2"), labels(t.root()));
		assertEquals(List.of("box 1 (500 blocks)", "group 1"), labels(target));
		assertEquals(List.of("box 2 (23111 blocks)", "box 3 (10 blocks)"), labels(g1));
		assertEquals(List.of("player: Steve", "player: Alex", "box 4 (20 blocks)"), labels(g2));
		assertEquals(500 + 23111 + 10, target.blockCount());
	}

	@Test
	void clearingCustomNameAssignsGeneratedName() {
		SelectionTree t = new SelectionTree();
		GroupNode g = new GroupNode();
		g.rename("walls");
		t.add(g);
		t.rename(g, null);
		assertEquals("group 1", g.name());
	}

	@Test
	void namesAreNotReusedAfterRemoval() {
		SelectionTree t = new SelectionTree();
		BoxNode a = t.add(box(1));
		t.remove(a);
		assertEquals("box 2", t.add(box(1)).name());
	}

	@Test
	void moveReordersWithinParent() {
		SelectionTree t = new SelectionTree();
		BoxNode a = t.add(box(1)), b = t.add(box(1)), c = t.add(box(1));
		assertTrue(t.move(a, null, 3));
		assertEquals(List.of(b, c, a), t.root().children());
		assertTrue(t.moveBefore(a, b));
		assertEquals(List.of(a, b, c), t.root().children());
		assertTrue(t.moveAfter(a, c));
		assertEquals(List.of(b, c, a), t.root().children());
	}

	@Test
	void moveIntoOwnDescendantIsRefused() {
		SelectionTree t = new SelectionTree();
		GroupNode outer = t.add(new GroupNode());
		GroupNode inner = t.add(new GroupNode(), outer, -1);
		assertFalse(t.move(outer, inner, 0));
		assertFalse(t.move(outer, outer, 0));
		assertSame(t.root(), outer.parent());
	}

	@Test
	void groupKeepsPositionAndOrder() {
		SelectionTree t = new SelectionTree();
		BoxNode a = t.add(box(1)), b = t.add(box(1)), c = t.add(box(1)), d = t.add(box(1));
		GroupNode g = t.group(List.of(d, b));
		assertEquals(List.of(a, g, c), t.root().children());
		assertEquals(List.of(b, d), g.children());
		t.ungroup(g);
		assertEquals(List.of(a, b, d, c), t.root().children());
		assertTrue(t.find(g.id()).isEmpty());
	}

	@Test
	void groupingNodeWithItsAncestorMovesAncestorOnly() {
		SelectionTree t = new SelectionTree();
		GroupNode g = t.add(new GroupNode());
		BoxNode inside = t.add(box(1), g, -1);
		GroupNode outer = t.group(List.of(inside, g));
		assertEquals(List.of(g), outer.children());
		assertEquals(List.of(inside), g.children());
	}

	@Test
	void duplicateCopiesSubtreeWithNewIds() {
		SelectionTree t = new SelectionTree();
		GroupNode g = t.add(new GroupNode());
		t.rename(g, "walls");
		BoxNode b = t.add(box(3), g, -1);
		GroupNode copy = (GroupNode) t.duplicate(g);
		assertEquals(List.of(g, copy), t.root().children());
		assertEquals("walls", copy.name());
		BoxNode copiedBox = (BoxNode) copy.children().get(0);
		assertNotEquals(b.id(), copiedBox.id());
		assertEquals(3, copiedBox.blockCount());
		copiedBox.box().center.x += 10;
		assertNotEquals(b.box().center.x, copiedBox.box().center.x);
	}

	@Test
	void removeDropsSubtreeFromIndexAndSelection() {
		SelectionTree t = new SelectionTree();
		GroupNode g = t.add(new GroupNode());
		BoxNode b = t.add(box(1), g, -1);
		t.select(b, false);
		t.remove(g);
		assertTrue(t.find(b.id()).isEmpty());
		assertTrue(t.selected().isEmpty());
	}

	@Test
	void blockCountFollowsBoxEdits() {
		SelectionTree t = new SelectionTree();
		BoxNode b = t.add(box(2));
		assertEquals(2, b.blockCount());
		b.box().half.x += 1;
		b.box().center.x += 1;
		assertEquals(4, b.blockCount());
	}

	@Test
	void entityRenameOverridesLabel() {
		SelectionTree t = new SelectionTree();
		EntityNode e = t.add(new EntityNode(UUID.randomUUID(), "player", "Steve"));
		assertEquals("player: Steve", e.label());
		t.rename(e, "the builder");
		assertEquals("the builder", e.label());
		t.rename(e, "  ");
		assertEquals("player: Steve", e.label());
	}

	@Test
	void jsonRoundTrip() {
		SelectionTree t = new SelectionTree();
		GroupNode target = t.add(new GroupNode());
		t.rename(target, "target");
		BoxNode b = t.add(box(5), target, -1);
		b.box().rotation.rotateY(0.25);
		b.visible = false;
		t.add(new EntityNode(UUID.randomUUID(), "player", "Alex"));

		SelectionTree loaded = new SelectionTree();
		SelectionStore.fromJson(loaded, SelectionStore.toJson(t));
		assertEquals(SelectionStore.toJson(t), SelectionStore.toJson(loaded));
		BoxNode lb = (BoxNode) loaded.find(b.id()).orElseThrow();
		assertFalse(lb.visible);
		assertEquals(b.blockCount(), lb.blockCount());
		// counters continue after loaded names
		assertEquals("box 2", loaded.add(box(1)).name());
		assertEquals("group 2", loaded.add(new GroupNode()).name());
	}
}
