package dev.plainsight.guess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeedHiderTest {
	private static final String M = SeedHider.MASK;

	@Test
	void longNumbersAndKnownSeeds() {
		assertEquals("Seed: [" + M + "]", SeedHider.mask("Seed: [-4172144997902289642]", new long[0]));
		assertEquals("seed " + M + " found", SeedHider.mask("seed 12345 found", new long[]{12345}));
		assertEquals("seed " + M, SeedHider.mask("seed -98765", new long[]{-98765}));
	}

	@Test
	void shortSeedsAndDecimalsStay() {
		// seed 0 (a flat world) must not hide every 0 in chat
		assertEquals("Teleported Dev to 4500.5, 80.0, 0.5", SeedHider.mask("Teleported Dev to 4500.5, 80.0, 0.5", new long[]{0}));
		// a known seed's digits inside a decimal or a longer number are not the seed
		assertEquals("at 12345.5 and 123456", SeedHider.mask("at 12345.5 and 123456", new long[]{12345}));
	}
}
