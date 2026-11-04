package dev.plainsight.editor.selection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SelectionTextTest {
	@Test
	void wholeBlockBox() {
		// blocks 1..7, -61..-57, 4..10 (OrientedBox.ofBlocks(1, -61, 4, 7, -57, 10) has bounds 1..8 etc.)
		double[] b = {1, -61, 4, 8, -56, 11};
		assertEquals("min 1 -61 4, max 7 -57 10", SelectionText.specs(b));
		assertEquals("4 -59 7", SelectionText.center(b));
	}

	@Test
	void singleBlockAndEntity() {
		assertEquals("min -3 64 5, max -3 64 5", SelectionText.specs(new double[]{-3, 64, 5, -2, 65, 6}));
		// an entity's box: the block its centre is in
		assertEquals("10 64 -5", SelectionText.center(new double[]{9.7, 64, -5.3, 10.3, 65.8, -4.7}));
	}

	@Test
	void union() {
		assertArrayEquals(new double[]{0, 0, 0, 5, 6, 7}, SelectionText.union(Arrays.asList(new double[]{0, 1, 2, 3, 4, 5}, null, new double[]{1, 0, 0, 5, 6, 7})));
		assertNull(SelectionText.union(Arrays.asList((double[]) null)));
	}
}
