package dev.infiniterra.api;

import dev.infiniterra.impl.guess.SeedCheckImpl;
import org.jspecify.annotations.Nullable;

/**
 * Whether the seed given to {@link TerrainGuess#update} is really the world's: the bedrock of every real
 * chunk that loads is compared with the pattern the seed gives (the overworld's floor, the nether's floor
 * and roof; computed from the seed, nothing is generated). A wrong seed matches about 60% of the random
 * bedrock layers, the right one all of them. Chunks without vanilla bedrock (void or flat-bedrock worlds)
 * are not counted. The host decides what to do with the verdict.
 */
public final class SeedCheck {
	private SeedCheck() {
	}

	public enum Verdict {
		/** Not enough chunks compared yet (or this dimension has no bedrock pattern). */
		UNKNOWN,
		/** Enough chunks match and few or none do not. */
		MATCHES,
		/** Several chunks clearly do not match: the seed is not this world's (or its generation differs). */
		MISMATCH
	}

	/**
	 * @param comparable whether this dimension has a bedrock pattern to compare (not the End)
	 * @param uncomparable chunks without vanilla bedrock, or between match and mismatch
	 */
	public record Result(long seed, boolean comparable, int matched, int mismatched, int uncomparable, Verdict verdict) {
	}

	/** The check of the current world, dimension and seed; null without a seed. */
	public static @Nullable Result result() {
		return SeedCheckImpl.result();
	}
}
