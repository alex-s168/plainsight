package dev.plainsight.editor.selection;

import dev.plainsight.editor.math.OrientedBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DimensionTreesTest {
	@TempDir
	Path tmp;

	@Test
	void eachDimensionHasItsOwnFile() throws Exception {
		Path dir = tmp.resolve("world/plainsight/selections");
		DimensionTrees trees = new DimensionTrees(dir, null);
		trees.tree(DimensionTrees.OVERWORLD).add(new BoxNode(OrientedBox.ofBlocks(0, 0, 0, 1, 1, 1)));
		trees.tree(DimensionTrees.NETHER).add(new PointNode(1, 2, 3));
		assertTrue(trees.changed());
		trees.saveChanged();
		assertFalse(trees.changed());
		assertTrue(Files.exists(dir.resolve("minecraft_overworld.json")));
		assertTrue(Files.exists(dir.resolve("minecraft_the_nether.json")));
		assertFalse(Files.exists(dir.resolve("minecraft_the_end.json")), "an untouched tree is not written");

		DimensionTrees again = new DimensionTrees(dir, null);
		assertEquals(1, again.tree(DimensionTrees.OVERWORLD).all().size());
		assertInstanceOf(PointNode.class, again.tree(DimensionTrees.NETHER).all().getFirst());
		assertTrue(again.tree(DimensionTrees.END).all().isEmpty());
	}

	@Test
	void oldPerWorldFileMovesToTheOverworld() throws Exception {
		SelectionTree old = new SelectionTree();
		old.add(new PointNode(5, 6, 7));
		Path legacy = tmp.resolve("config/sp_New_World.json");
		SelectionStore.save(old, legacy);
		Path dir = tmp.resolve("world/plainsight/selections");
		DimensionTrees trees = new DimensionTrees(dir, legacy);
		assertFalse(Files.exists(legacy), "the old file is moved, so another world of the same name does not take it too");
		assertEquals(1, trees.tree(DimensionTrees.OVERWORLD).all().size());
		assertTrue(trees.tree(DimensionTrees.NETHER).all().isEmpty());
	}

	@Test
	void existingOverworldFileIsKept() throws Exception {
		Path dir = tmp.resolve("sel");
		SelectionTree ow = new SelectionTree();
		ow.add(new PointNode(1, 1, 1));
		ow.add(new PointNode(2, 2, 2));
		SelectionStore.save(ow, dir.resolve("minecraft_overworld.json"));
		Path legacy = tmp.resolve("mp_server.json");
		SelectionStore.save(new SelectionTree(), legacy);
		DimensionTrees trees = new DimensionTrees(dir, legacy);
		assertEquals(2, trees.tree(DimensionTrees.OVERWORLD).all().size());
		assertTrue(Files.exists(legacy));
	}
}
