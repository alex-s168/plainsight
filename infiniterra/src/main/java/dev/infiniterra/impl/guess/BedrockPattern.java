package dev.infiniterra.impl.guess;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jspecify.annotations.Nullable;

/**
 * Where a seed puts bedrock: vanilla's bedrock floor (and the nether's roof) is a vertical gradient of
 * positional randomness from the seed (VanillaMaterialRules: {@code bedrock_floor} from the bottom to 5
 * above it, {@code bedrock_roof} from 5 below the top to the top), so it can be computed for any block
 * without generating anything. Only the layers in between are random: those tell a seed apart.
 */
public final class BedrockPattern {
	/** The vanilla dimensions with a bedrock pattern: their generation height and random algorithm. */
	public enum Kind {
		OVERWORLD(-64, 384, false, false),
		NETHER(0, 128, true, true);

		final int minY, height;
		final boolean legacyRandom, roof;

		Kind(int minY, int height, boolean legacyRandom, boolean roof) {
			this.minY = minY;
			this.height = height;
			this.legacyRandom = legacyRandom;
			this.roof = roof;
		}

		/** The bedrock floor: always at minY, random up to minY + 4. */
		int floorAlways() {
			return minY;
		}

		/** The roof (nether): always at the top, random down to top - 4. */
		int top() {
			return minY + height - 1;
		}
	}

	public static @Nullable Kind kindOf(ResourceKey<Level> dimension) {
		if (dimension == Level.OVERWORLD) return Kind.OVERWORLD;
		if (dimension == Level.NETHER) return Kind.NETHER;
		return null;
	}

	final Kind kind;
	private final PositionalRandomFactory floor, roof;

	public BedrockPattern(long seed, Kind kind) {
		this.kind = kind;
		PositionalRandomFactory base = (kind.legacyRandom ? WorldgenRandom.Algorithm.LEGACY : WorldgenRandom.Algorithm.XOROSHIRO)
				.newInstance(seed).forkPositional();
		floor = base.fromHashOf(Identifier.withDefaultNamespace("bedrock_floor")).forkPositional();
		roof = base.fromHashOf(Identifier.withDefaultNamespace("bedrock_roof")).forkPositional();
	}

	/** Whether the seed puts bedrock at this block (only the floor and roof rules; other blocks: false). */
	public boolean bedrock(int x, int y, int z) {
		int lo = kind.floorAlways(), hi = lo + 5;
		if (y <= lo) return true;
		if (y < hi) return floor.at(x, y, z).nextFloat() < Mth.map(y, lo, hi, 1.0, 0.0);
		if (!kind.roof) return false;
		int top = kind.top(), below = top - 5;
		if (y >= top) return true;
		// not(gradient): bedrock where the gradient condition is false
		if (y > below) return !(roof.at(x, y, z).nextFloat() < Mth.map(y, below, top, 1.0, 0.0));
		return false;
	}

	/** The random layers (the only ones that tell seeds apart), bottom to top. */
	public int[] randomLayers() {
		int lo = kind.floorAlways();
		if (!kind.roof) return new int[]{lo + 1, lo + 2, lo + 3, lo + 4};
		int top = kind.top();
		return new int[]{lo + 1, lo + 2, lo + 3, lo + 4, top - 4, top - 3, top - 2, top - 1};
	}

	/** The layers that are always bedrock (to tell vanilla bedrock from none at all). */
	public int[] solidLayers() {
		return kind.roof ? new int[]{kind.floorAlways(), kind.top()} : new int[]{kind.floorAlways()};
	}
}
