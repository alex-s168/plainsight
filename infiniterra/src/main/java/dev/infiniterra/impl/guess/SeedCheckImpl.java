package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
import dev.infiniterra.api.SeedCheck;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * Compares the bedrock of real chunks, as they load, with the pattern of the seed being used (see
 * {@link SeedCheck}). A few chunks a tick, on the render thread.
 */
public final class SeedCheckImpl {
	/** A chunk matches with at least this share of its random bedrock layers equal, mismatches below the other. */
	static final double MATCH = 0.97, MISMATCH = 0.85;
	private static final int PER_TICK = 8;

	private static String key;
	private static long seed;
	private static @Nullable BedrockPattern pattern;
	private static final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private static final LongOpenHashSet done = new LongOpenHashSet();
	private static int matched, mismatched, uncomparable;
	private static SeedCheck.Verdict verdict = SeedCheck.Verdict.UNKNOWN;

	private SeedCheckImpl() {
	}

	/** Every tick: the world and seed being used (from TerrainGuess.update). */
	public static void update(@Nullable String worldKey, OptionalLong seed) {
		Minecraft mc = Minecraft.getInstance();
		String k = worldKey == null || mc.level == null || seed.isEmpty() ? null
				: worldKey + "|" + mc.level.dimension().identifier() + "|" + seed.getAsLong();
		if (!java.util.Objects.equals(k, key)) start(k, seed, mc);
		if (pattern == null) return;
		for (int n = 0; n < PER_TICK && !queue.isEmpty(); n++) {
			long p = queue.dequeueLong();
			LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(p), ChunkPos.getZ(p), ChunkStatus.FULL, false) instanceof LevelChunk c ? c : null;
			if (chunk != null && done.add(p)) check(chunk);
		}
	}

	private static void start(@Nullable String k, OptionalLong s, Minecraft mc) {
		key = k;
		queue.clear();
		done.clear();
		matched = mismatched = uncomparable = 0;
		verdict = SeedCheck.Verdict.UNKNOWN;
		pattern = null;
		if (k == null) return;
		seed = s.getAsLong();
		BedrockPattern.Kind kind = BedrockPattern.kindOf(mc.level.dimension());
		if (kind == null || mc.level.getMinY() != kind.minY) return; // no known pattern (the End, other heights)
		pattern = new BedrockPattern(seed, kind);
		// the chunks loaded already
		if (mc.player != null) {
			int r = mc.options.getEffectiveRenderDistance(), cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
			for (int x = cx - r; x <= cx + r; x++)
				for (int z = cz - r; z <= cz + r; z++) queue.enqueue(ChunkPos.pack(x, z));
		}
	}

	/** A chunk arrived from the server (client level). */
	public static void loaded(LevelChunk chunk) {
		if (pattern != null) queue.enqueue(chunk.getPos().pack());
	}

	private static void check(LevelChunk chunk) {
		BedrockPattern p = pattern;
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// vanilla bedrock at all? (void, sky or flat-bedrock worlds have none to compare: not a mismatch)
		int solid = 0, solidTotal = 0;
		for (int y : p.solidLayers())
			for (int x = 0; x < 16; x++)
				for (int z = 0; z < 16; z++) {
					solidTotal++;
					if (chunk.getBlockState(pos.set(x0 + x, y, z0 + z)).is(Blocks.BEDROCK)) solid++;
				}
		int equal = 0, total = 0, anyBedrock = 0;
		for (int y : p.randomLayers())
			for (int x = 0; x < 16; x++)
				for (int z = 0; z < 16; z++) {
					boolean real = chunk.getBlockState(pos.set(x0 + x, y, z0 + z)).is(Blocks.BEDROCK);
					if (real) anyBedrock++;
					total++;
					if (real == p.bedrock(x0 + x, y, z0 + z)) equal++;
				}
		if (solid < solidTotal * 0.95 || anyBedrock == 0) {
			uncomparable++;
			return;
		}
		double accuracy = equal / (double) total;
		if (accuracy >= MATCH) matched++;
		else if (accuracy < MISMATCH) mismatched++;
		else uncomparable++;
		SeedCheck.Verdict before = verdict;
		verdict = verdictOf(matched, mismatched);
		if (verdict != before)
			InfiniTerraMod.LOG.info("Seed check: {} ({} chunks match, {} do not)", verdict, matched, mismatched);
	}

	/** Mismatch once two chunks clearly do not match (one could have been edited) and not many more match; match after 8. */
	static SeedCheck.Verdict verdictOf(int matched, int mismatched) {
		if (mismatched >= 2 && mismatched * 4 > matched) return SeedCheck.Verdict.MISMATCH;
		if (matched >= 8 && mismatched * 4 <= matched) return SeedCheck.Verdict.MATCHES;
		return SeedCheck.Verdict.UNKNOWN;
	}

	public static SeedCheck.@Nullable Result result() {
		if (key == null) return null;
		return new SeedCheck.Result(seed, pattern != null, matched, mismatched, uncomparable, verdict);
	}
}
