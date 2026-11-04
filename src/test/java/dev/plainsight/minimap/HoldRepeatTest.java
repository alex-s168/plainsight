package dev.plainsight.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldRepeatTest {
	@Test
	void aShortPressIsOneStep() {
		HoldRepeat r = new HoldRepeat(0.35, 8, 30, 120);
		assertEquals(1, r.update(1, false, 0.05), "pressed and released within one update");
		assertEquals(0, r.update(0, false, 0.05));
		assertEquals(1, r.update(1, true, 0.05), "pressed");
		assertEquals(0, r.update(0, true, 0.2), "held shortly: nothing more");
		assertEquals(0, r.update(0, false, 0.05), "released");
	}

	@Test
	void holdingRepeatsFasterOverTime() {
		HoldRepeat r = new HoldRepeat(0.35, 8, 30, 120);
		r.update(1, true, 0.05);
		int first = 0, later = 0;
		for (int i = 0; i < 20; i++) first += r.update(0, true, 0.05); // 0 .. 1 s
		for (int i = 0; i < 20; i++) later += r.update(0, true, 0.05); // 1 .. 2 s
		assertTrue(first > 2, "repeats after the delay: " + first);
		assertTrue(later > first * 2, "and faster later: " + first + " then " + later);
	}
}
