package dev.plainsight.editor.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PanelWidthsTest {
	@Test
	void draggedPanelKeepsMinimumAndViewport() {
		assertEquals(400, PanelWidths.dragged(400, 320, 1600));
		assertEquals(PanelWidths.MIN_PANEL, PanelWidths.dragged(20, 320, 1600));
		assertEquals(1600 - 320 - PanelWidths.MIN_VIEWPORT, PanelWidths.dragged(1500, 320, 1600));
	}

	@Test
	void fitLeavesWidthsThatFit() {
		assertArrayEquals(new float[]{300, 320}, PanelWidths.fit(300, 320, 1600));
		assertArrayEquals(new float[]{180, 320}, PanelWidths.fit(50, 320, 1600));
	}

	@Test
	void fitShrinksBothInANarrowWindow() {
		float[] w = PanelWidths.fit(600, 400, 1000);
		assertEquals(1000 - PanelWidths.MIN_VIEWPORT, w[0] + w[1], 1e-3);
		assertTrue(w[0] > w[1], "in proportion to what they have above the minimum");
		assertTrue(w[1] >= PanelWidths.MIN_PANEL);
		// a tiny window: both panels share what is left, the viewport keeps its minimum
		float[] t = PanelWidths.fit(300, 320, 500);
		assertEquals(130, t[0], 1e-3);
		assertEquals(130, t[1], 1e-3);
	}
}
