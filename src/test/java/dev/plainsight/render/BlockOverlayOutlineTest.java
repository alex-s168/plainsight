package dev.plainsight.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockOverlayOutlineTest {
	private static LongOpenHashSet cells(int[]... ps) {
		LongOpenHashSet s = new LongOpenHashSet();
		for (int[] p : ps) s.add(BlockPos.asLong(p[0], p[1], p[2]));
		return s;
	}

	private static int count(LongOpenHashSet cells) {
		return BlockOverlayRenderer.edges(cells).length / 6;
	}

	@Test
	void singleBlockHasTwelveEdges() {
		assertEquals(12, count(cells(new int[]{0, 0, 0})));
	}

	@Test
	void flatNeighboursShareNoEdge() {
		// a 2x1x1 bar: 8 long-side unit edges + 4 + 4 end edges; the 4 edges of the shared face are not drawn
		assertEquals(16, count(cells(new int[]{0, 0, 0}, new int[]{1, 0, 0})));
		// a 3x3 floor: only its rim, 12 unit edges on each of the 4 sides... top and bottom rings (2*12) plus 4 vertical
		int[][] floor = new int[9][];
		for (int i = 0; i < 9; i++) floor[i] = new int[]{i % 3, 0, i / 3};
		assertEquals(28, count(cells(floor)));
	}

	@Test
	void innerCornerIsDrawnOnce() {
		// an L of three blocks: the concave edge is one line, not one per block
		float[] e = BlockOverlayRenderer.edges(cells(new int[]{0, 0, 0}, new int[]{1, 0, 0}, new int[]{0, 0, 1}));
		int concave = 0;
		for (int i = 0; i < e.length; i += 6) {
			boolean vertical = e[i + 1] != e[i + 4];
			if (vertical && Math.abs(e[i] - 1) < 0.1 && Math.abs(e[i + 2] - 1) < 0.1) concave++;
		}
		assertEquals(1, concave);
	}

	@Test
	void edgesLieOutsideTheBlocks() {
		float[] e = BlockOverlayRenderer.edges(cells(new int[]{0, 0, 0}));
		for (int i = 0; i < e.length; i += 6) {
			// every coordinate across the edge is just below 0 or just above 1
			for (int k = 0; k < 3; k++) {
				if (e[i + k] != e[i + 3 + k]) continue;
				assertTrue(e[i + k] < 0 || e[i + k] > 1, "edge on the block surface: " + e[i + k]);
			}
		}
	}
}
