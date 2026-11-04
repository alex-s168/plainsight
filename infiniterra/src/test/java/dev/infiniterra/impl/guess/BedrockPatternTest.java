package dev.infiniterra.impl.guess;

import dev.infiniterra.api.SeedCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPatternTest {
	private static double share(BedrockPattern p, int y) {
		int n = 0;
		for (int x = 0; x < 64; x++)
			for (int z = 0; z < 64; z++) if (p.bedrock(x, y, z)) n++;
		return n / 4096.0;
	}

	@Test
	void overworldFloorIsAGradient() {
		var p = new BedrockPattern(12345, BedrockPattern.Kind.OVERWORLD);
		assertEquals(1.0, share(p, -64));
		assertEquals(0.8, share(p, -63), 0.03);
		assertEquals(0.6, share(p, -62), 0.03);
		assertEquals(0.4, share(p, -61), 0.03);
		assertEquals(0.2, share(p, -60), 0.03);
		assertEquals(0.0, share(p, -59));
		assertFalse(p.bedrock(0, 100, 0));
	}

	@Test
	void netherHasAFloorAndARoof() {
		var p = new BedrockPattern(12345, BedrockPattern.Kind.NETHER);
		assertEquals(1.0, share(p, 0));
		assertEquals(0.8, share(p, 1), 0.03);
		assertEquals(0.0, share(p, 64));
		assertEquals(0.2, share(p, 123), 0.03);
		assertEquals(0.8, share(p, 126), 0.03);
		assertEquals(1.0, share(p, 127));
	}

	@Test
	void anotherSeedMatchesOnlyPartly() {
		var a = new BedrockPattern(12345, BedrockPattern.Kind.OVERWORLD);
		var b = new BedrockPattern(12346, BedrockPattern.Kind.OVERWORLD);
		int equal = 0, total = 0;
		for (int y : a.randomLayers())
			for (int x = 0; x < 16; x++)
				for (int z = 0; z < 16; z++) {
					total++;
					if (a.bedrock(x, y, z) == b.bedrock(x, y, z)) equal++;
				}
		double accuracy = equal / (double) total;
		assertTrue(accuracy < SeedCheckImpl.MISMATCH, "a wrong seed is a clear mismatch: " + accuracy);
	}

	@Test
	void verdict() {
		assertEquals(SeedCheck.Verdict.UNKNOWN, SeedCheckImpl.verdictOf(3, 0));
		assertEquals(SeedCheck.Verdict.MATCHES, SeedCheckImpl.verdictOf(8, 0));
		assertEquals(SeedCheck.Verdict.MATCHES, SeedCheckImpl.verdictOf(40, 1), "one edited chunk is tolerated");
		assertEquals(SeedCheck.Verdict.MISMATCH, SeedCheckImpl.verdictOf(0, 2));
		assertEquals(SeedCheck.Verdict.MATCHES, SeedCheckImpl.verdictOf(40, 2), "a couple among many matching chunks");
		assertEquals(SeedCheck.Verdict.MISMATCH, SeedCheckImpl.verdictOf(4, 2));
	}
}
