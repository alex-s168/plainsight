package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
import dev.infiniterra.api.SeedCheck;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;

/**
 * The worldgen server of the seed in use mirrors the real world: every real chunk the client has (when
 * it arrives, and again after blocks in it changed) is copied into the server's world of the same
 * dimension, which keeps it on disk. That world is then real wherever the client has been, and the LOD
 * built from it (Voxy's guessed LOD, Distant Horizons) shows the real terrain there. Not while the seed
 * check rejects the seed. A few chunks a tick; the copy is made on the render thread, written on the
 * server's.
 */
public final class WorldMirror {
	private static final int PER_TICK = 8;
	/** A changed chunk is copied again at most this often (ms). */
	private static final long CHANGE_INTERVAL = 1000;

	private static @Nullable String key;
	private static @Nullable SeedServer server;
	private static final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	/** Chunks waiting to be copied, and when they may be (changed ones wait a little). */
	private static final Long2LongOpenHashMap due = new Long2LongOpenHashMap();
	public static volatile int mirrored, failed;

	private WorldMirror() {
	}

	/** Every tick: the world and seed in use (from TerrainGuess.update). Render thread. */
	public static void update(@Nullable String worldKey, OptionalLong seed) {
		Minecraft mc = Minecraft.getInstance();
		var check = SeedCheckImpl.result();
		boolean rejected = check != null && check.verdict() == SeedCheck.Verdict.MISMATCH;
		String k = worldKey == null || mc.level == null || seed.isEmpty() || rejected ? null
				: worldKey + "|" + mc.level.dimension().identifier() + "|" + seed.getAsLong();
		if (!java.util.Objects.equals(k, key)) restart(k, seed, mc);
		if (server == null || mc.level == null) return;
		var s = server.serverNow();
		if (s == null) return;
		long now = System.currentTimeMillis();
		int n = 0, checked = 0, size = queue.size();
		while (n < PER_TICK && checked++ < size && !queue.isEmpty()) {
			long p = queue.dequeueLong();
			long when = due.get(p);
			if (when > now) {
				queue.enqueue(p);
				continue;
			}
			due.remove(p);
			if (!(mc.level.getChunkSource().getChunk(ChunkPos.getX(p), ChunkPos.getZ(p), ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
			copy(chunk, s.getLevel(mc.level.dimension()), mc.level.dimension());
			n++;
		}
	}

	private static void restart(@Nullable String k, OptionalLong seed, Minecraft mc) {
		key = k;
		queue.clear();
		due.clear();
		if (server != null) server.release();
		server = null;
		if (k == null) return;
		server = SeedServer.acquire(seed.getAsLong());
		// the chunks the client has already
		if (mc.player != null) {
			int r = mc.options.getEffectiveRenderDistance(), cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
			for (int x = cx - r; x <= cx + r; x++)
				for (int z = cz - r; z <= cz + r; z++) mark(ChunkPos.pack(x, z), 0);
		}
	}

	private static void mark(long pos, long notBefore) {
		if (!due.containsKey(pos)) queue.enqueue(pos);
		due.put(pos, Math.max(due.get(pos), notBefore));
	}

	/** A chunk arrived from the server (client level). */
	public static void loaded(LevelChunk chunk) {
		if (server != null) mark(chunk.getPos().pack(), 0);
	}

	/** A block changed in a loaded chunk (client level, render thread). */
	public static void blockChanged(int cx, int cz) {
		if (server != null) mark(ChunkPos.pack(cx, cz), System.currentTimeMillis() + CHANGE_INTERVAL);
	}

	/** Copies the client chunk's sections now and writes them into the server's chunk on its thread. */
	private static void copy(LevelChunk real, @Nullable ServerLevel level, ResourceKey<Level> dimension) {
		if (level == null || level.getMinSectionY() != real.getMinSectionY() || level.getSectionsCount() != real.getSectionsCount()) return;
		LevelChunkSection[] sections = real.getSections();
		LevelChunkSection[] copies = new LevelChunkSection[sections.length];
		for (int i = 0; i < sections.length; i++) copies[i] = sections[i].copy();
		int cx = real.getPos().x(), cz = real.getPos().z();
		level.getServer().execute(() -> level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true).whenCompleteAsync((result, err) -> {
			if (err != null || !(result.orElse(null) instanceof LevelChunk chunk)) {
				failed++;
				return;
			}
			try {
				write(level, chunk, copies);
				mirrored++;
			} catch (RuntimeException e) {
				failed++;
				InfiniTerraMod.LOG.warn("Could not mirror chunk {}, {} into the worldgen world", cx, cz, e);
			}
		}, level.getServer()));
	}

	/** Server thread: the real sections replace the generated ones; light and heightmaps are made anew. */
	private static void write(ServerLevel level, LevelChunk chunk, LevelChunkSection[] copies) {
		// the generated chunk's block entities (chests, spawners) are not the real ones
		for (BlockPos p : List.copyOf(chunk.getBlockEntitiesPos())) chunk.removeBlockEntity(p);
		LevelChunkSection[] sections = chunk.getSections();
		var light = level.getChunkSource().getLightEngine();
		for (int i = 0; i < sections.length; i++) {
			sections[i] = copies[i];
			light.updateSectionStatus(SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i)), copies[i].hasOnlyAir());
		}
		Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types.class));
		// the generated chunk's light is dropped and propagated again from the real blocks; not lightChunk,
		// whose end marks the full chunk unsaved on the light thread (a race with the server thread)
		for (int i = 0; i < sections.length; i++) {
			SectionPos sp = SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(i));
			light.queueSectionData(net.minecraft.world.level.LightLayer.SKY, sp, null);
			light.queueSectionData(net.minecraft.world.level.LightLayer.BLOCK, sp, null);
		}
		chunk.initializeLightSources();
		light.propagateLightSources(chunk.getPos());
		chunk.markUnsaved();
	}

	/** "mirrored failed" (tests). */
	public static String counts() {
		return mirrored + " " + failed;
	}

	/** The worldgen server's block at a position of the current dimension, "none" without a server (tests). */
	public static String serverBlock(int x, int y, int z) throws Exception {
		SeedServer s = server;
		var mc = Minecraft.getInstance();
		if (s == null || s.serverNow() == null || mc.level == null) return "none";
		ServerLevel level = s.serverNow().getLevel(mc.level.dimension());
		return s.serverNow().submit(() -> level.getChunk(x >> 4, z >> 4).getBlockState(new BlockPos(x, y, z)).toString())
				.get(60, java.util.concurrent.TimeUnit.SECONDS);
	}
}
