package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The guessed LOD world is a second Voxy world (its own storage) holding generated chunks plus a copy of
 * every real chunk Voxy ingests while it exists ({@link #mirror}, called from a mixin on Voxy's ingest
 * service); real data is ingested after guesses and replaces them. In the editor Voxy's renderer is
 * rebuilt on that world, outside the editor on the normal one.
 */
final class VoxyGuessImpl {
	private static volatile GuessWorld world;
	private static volatile WorldEngine guessEngine;
	private static boolean wantGuessed;
	private static final ThreadLocal<Boolean> MIRRORING = ThreadLocal.withInitial(() -> false);
	private static Method createEngineDirect;
	private static Field rendererWorld;

	private VoxyGuessImpl() {
	}

	static boolean running() {
		var instance = VoxyCommon.getInstance();
		return instance != null && instance.isRunning();
	}

	static void attach(GuessWorld w) {
		Minecraft mc = Minecraft.getInstance();
		var instance = VoxyCommon.getInstance();
		if (instance == null || mc.level == null) return;
		WorldIdentifier real = WorldIdentifier.of(mc.level);
		if (real == null) return;
		Identifier id = Identifier.fromNamespaceAndPath(InfiniTerraMod.ID, "guess/" + w.dimension.identifier().getNamespace() + "/"
				+ w.dimension.identifier().getPath() + "/" + Long.toHexString(w.seed));
		WorldEngine engine = instance.getOrCreate(new WorldIdentifier(ResourceKey.create(Registries.DIMENSION, id), real.biomeSeed, real.dimension));
		if (engine == null) return;
		engine.acquireRef();
		guessEngine = engine;
		world = w;
		// chunks already loaded were ingested before the guess world existed: copy them now
		var cache = mc.level.getChunkSource();
		int r = mc.options.getEffectiveRenderDistance() + 2;
		var centre = mc.player != null ? mc.player.chunkPosition() : new net.minecraft.world.level.ChunkPos(0, 0);
		for (int x = centre.x() - r; x <= centre.x() + r; x++)
			for (int z = centre.z() - r; z <= centre.z() + r; z++) {
				LevelChunk chunk = cache.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (chunk != null && instance.getIngestService().enqueueIngest(engine, chunk)) w.markReal(x, z);
			}
	}

	static void detach() {
		show(false);
		WorldEngine e = guessEngine;
		guessEngine = null;
		world = null;
		if (e != null) e.releaseRef();
	}

	/**
	 * Gives a generated chunk to the guessed world, except its 16-block sections that real data was
	 * copied for ({@code realSections}, bit i = section minSectionY + i).
	 * Server thread (the light comes from the worldgen server's light engine).
	 */
	static boolean ingestGuessed(LevelChunk chunk, long realSections) {
		WorldEngine e = guessEngine;
		var instance = VoxyCommon.getInstance();
		if (e == null || instance == null || !e.isLive()) return false;
		MIRRORING.set(true);
		try {
			if (realSections == 0) return instance.getIngestService().enqueueIngest(e, chunk);
			var light = chunk.getLevel().getLightEngine();
			var blockLayer = light.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
			var skyLayer = light.getLayerListener(net.minecraft.world.level.LightLayer.SKY);
			int sy = chunk.getMinSectionY() - 1;
			for (LevelChunkSection section : chunk.getSections()) {
				sy++;
				int bit = sy - chunk.getMinSectionY();
				if (bit >= 0 && bit < 64 && (realSections & (1L << bit)) != 0) continue;
				var pos = net.minecraft.core.SectionPos.of(chunk.getPos(), sy);
				DataLayer bl = blockLayer.getDataLayerData(pos), sl = skyLayer.getDataLayerData(pos);
				VoxelIngestService.rawIngest(e, section, chunk.getPos().x(), sy, chunk.getPos().z(), bl == null ? null : bl.copy(), sl == null ? null : sl.copy());
			}
			return true;
		} finally {
			MIRRORING.set(false);
		}
	}

	/** Voxy ingests a real chunk (into the normal world): copy it into the guessed world. */
	static void mirror(VoxelIngestService service, WorldEngine engine, LevelChunk chunk) {
		WorldEngine g = guessEngine;
		GuessWorld w = world;
		if (g == null || w == null || engine == g || MIRRORING.get() || !g.isLive()) return;
		MIRRORING.set(true);
		try {
			if (service.enqueueIngest(g, chunk)) w.markReal(chunk.getPos().x(), chunk.getPos().z());
		} finally {
			MIRRORING.set(false);
		}
	}

	/** Same for a single section (Voxy re-ingests sections whose mesh changed). */
	static void mirrorSection(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer blockLight, DataLayer skyLight) {
		WorldEngine g = guessEngine;
		GuessWorld w = world;
		if (g == null || w == null || engine == g || MIRRORING.get() || !g.isLive()) return;
		MIRRORING.set(true);
		try {
			// one section is not the chunk: it stays to be generated (and keeps this real section)
			VoxelIngestService.rawIngest(g, section, x, y, z, blockLight, skyLight);
		} finally {
			MIRRORING.set(false);
		}
	}

	static net.minecraft.world.level.block.state.BlockState lodBlock(int x, int y, int z) {
		WorldEngine engine = renderedWorld();
		if (engine == null || !engine.isLive()) return null;
		var section = engine.acquireIfExists(0, x >> 5, y >> 5, z >> 5);
		if (section == null) return null;
		try {
			long v = section._unsafeGetRawDataArray()[me.cortex.voxy.common.world.WorldSection.getIndex(x & 31, y & 31, z & 31)];
			if (me.cortex.voxy.common.world.other.Mapper.isAir(v)) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			return engine.getMapper().getBlockStateFromBlockId(me.cortex.voxy.common.world.other.Mapper.getBlockId(v));
		} finally {
			section.release();
		}
	}

	private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap blockMap = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
	private static final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap biomeMap = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
	private static WorldEngine mappedFrom, mappedTo;

	/**
	 * Copies the normal LOD world's data for the 2x2 chunks around (cx, cz) (one 32-block LOD column) into
	 * the guessed world, so terrain already seen (in earlier sessions too) shows as it really is. Returns,
	 * per chunk (index x + 2z within the column), which of its 16-block sections were copied (bit i:
	 * section minSectionY + i). Decided per chunk: a LOD section often holds real data for only some of
	 * its four chunks (the edge of where the player was), the others are empty in it. Worker thread.
	 */
	static synchronized long[] copyReal(int cx, int cz) {
		long[] found = new long[4];
		WorldEngine g = guessEngine;
		Minecraft mc = Minecraft.getInstance();
		var instance = VoxyCommon.getInstance();
		var level = mc.level;
		if (g == null || instance == null || level == null || !g.isLive()) return found;
		WorldIdentifier id = WorldIdentifier.of(level);
		if (id == null) return found;
		WorldEngine real = instance.getOrCreate(id);
		if (real == null || real == g || !real.isLive()) return found;
		if (mappedFrom != real || mappedTo != g) {
			blockMap.clear();
			biomeMap.clear();
			mappedFrom = real;
			mappedTo = g;
		}
		real.acquireRef();
		g.acquireRef();
		try {
			int sx = cx >> 1, sz = cz >> 1, base = level.getMinY() >> 5, top = (level.getMaxY() - 1) >> 5;
			int n = top - base + 1;
			long[][] sections = new long[n][];
			for (int i = 0; i < n; i++) {
				var src = real.acquireIfExists(0, sx, base + i, sz);
				if (src == null) continue;
				try {
					sections[i] = src.copyData();
				} finally {
					src.release();
				}
			}
			// per chunk: its column of 16-block parts (sub s = part oy = s & 1 of LOD section s >> 1)
			int subs = n * 2, firstSub = base * 2 - level.getMinSectionY();
			for (int q = 0; q < 4; q++) {
				int ox = q & 1, oz = q >> 1;
				boolean[] present = new boolean[subs], hasBlocks = new boolean[subs], topBlocks = new boolean[subs], bottomBlocks = new boolean[subs];
				for (int s = 0; s < subs; s++) {
					long[] data = sections[s >> 1];
					if (data == null) continue;
					present[s] = true;
					hasBlocks[s] = partHasBlocks(data, ox, s & 1, oz);
					topBlocks[s] = hasBlocks[s] && !layerAir(data, ox, (s & 1) * 16 + 15, oz);
					bottomBlocks[s] = hasBlocks[s] && !layerAir(data, ox, (s & 1) * 16, oz);
				}
				boolean[] unknown = LodParts.unknown(present, hasBlocks, topBlocks, bottomBlocks);
				for (int s = 0; s < subs; s++) {
					long[] data = sections[s >> 1];
					if (data == null || unknown[s]) continue;
					int oy = s & 1, bit = firstSub + s;
					if (bit >= 0 && bit < 64) found[q] |= 1L << bit;
					var vs = me.cortex.voxy.common.voxelization.VoxelizedSection.createEmpty().setPosition(sx * 2 + ox, (base + (s >> 1)) * 2 + oy, sz * 2 + oz);
					int nonAir = 0;
					for (int y = 0; y < 16; y++)
						for (int z = 0; z < 16; z++)
							for (int x = 0; x < 16; x++) {
								long v = data[me.cortex.voxy.common.world.WorldSection.getIndex(ox * 16 + x, oy * 16 + y, oz * 16 + z)];
								long out = remap(v, real, g);
								if (!me.cortex.voxy.common.world.other.Mapper.isAir(out)) nonAir++;
								vs.section[(y << 8) | (z << 4) | x] = out;
							}
					vs.lvl0NonAirCount = nonAir;
					me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper.mipSection(vs, g.getMapper());
					me.cortex.voxy.common.world.WorldUpdater.insertUpdate(g, vs);
				}
			}
			return found;
		} finally {
			g.releaseRef();
			real.releaseRef();
		}
	}

	/** Whether the 16-block part (ox, oy, oz) of a 32-block LOD section has a block anywhere. */
	private static boolean partHasBlocks(long[] data, int ox, int oy, int oz) {
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					long v = data[me.cortex.voxy.common.world.WorldSection.getIndex(ox * 16 + x, oy * 16 + y, oz * 16 + z)];
					if (!me.cortex.voxy.common.world.other.Mapper.isAir(v)) return true;
				}
		return false;
	}

	/** Per 32-block section of the normal LOD world's column at chunk (cx, cz): non-air and lit voxel counts (debugging). */
	static String describeReal(int cx, int cz) {
		var instance = VoxyCommon.getInstance();
		var level = Minecraft.getInstance().level;
		WorldEngine real = instance.getOrCreate(WorldIdentifier.of(level));
		StringBuilder sb = new StringBuilder();
		for (int sy = level.getMinY() >> 5; sy <= (level.getMaxY() - 1) >> 5; sy++) {
			var src = real.acquireIfExists(0, cx >> 1, sy, cz >> 1);
			if (src == null) {
				sb.append(sy).append(":- ");
				continue;
			}
			long[] d;
			try {
				d = src.copyData();
			} finally {
				src.release();
			}
			int blocks = 0, lit = 0;
			for (long v : d) {
				if (!me.cortex.voxy.common.world.other.Mapper.isAir(v)) blocks++;
				else if (me.cortex.voxy.common.world.other.Mapper.getLightId(v) != 0) lit++;
			}
			sb.append(sy).append(':').append(blocks).append('/').append(lit).append(' ');
		}
		return sb.toString();
	}

	/**
	 * Chunks within r of (cx, cz) whose surface (highest block at the chunk centre) in the guessed world is
	 * more than 4 blocks below the normal LOD world's, as "count x,z:real/guess;..." (debugging).
	 */
	static String compareReal(int cx, int cz, int r) {
		var instance = VoxyCommon.getInstance();
		var level = Minecraft.getInstance().level;
		WorldEngine g = guessEngine;
		if (instance == null || level == null || g == null) return "none";
		WorldEngine real = instance.getOrCreate(WorldIdentifier.of(level));
		StringBuilder at = new StringBuilder();
		int bad = 0, compared = 0;
		for (int x = cx - r; x <= cx + r; x++)
			for (int z = cz - r; z <= cz + r; z++) {
				int rt = topBlock(real, level, x * 16 + 8, z * 16 + 8);
				if (rt == Integer.MIN_VALUE) continue;
				compared++;
				int gt = topBlock(g, level, x * 16 + 8, z * 16 + 8);
				if (gt < rt - 4) {
					bad++;
					if (bad <= 20) at.append(at.isEmpty() ? "" : ";").append(x).append(',').append(z).append(':').append(rt).append('/').append(gt == Integer.MIN_VALUE ? "-" : gt);
				}
			}
		return bad + " of " + compared + " " + at;
	}

	private static int topBlock(WorldEngine engine, net.minecraft.world.level.Level level, int x, int z) {
		for (int sy = (level.getMaxY() - 1) >> 5; sy >= level.getMinY() >> 5; sy--) {
			var section = engine.acquireIfExists(0, x >> 5, sy, z >> 5);
			if (section == null) continue;
			try {
				long[] d = section._unsafeGetRawDataArray();
				for (int y = 31; y >= 0; y--)
					if (!me.cortex.voxy.common.world.other.Mapper.isAir(d[me.cortex.voxy.common.world.WorldSection.getIndex(x & 31, y, z & 31)])) return sy * 32 + y;
			} finally {
				section.release();
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Every 32-block LOD column (packed like a ChunkPos) the normal LOD world has data in. Worker thread. */
	static void realColumns(java.util.function.LongConsumer out) {
		Minecraft mc = Minecraft.getInstance();
		var instance = VoxyCommon.getInstance();
		var level = mc.level;
		if (instance == null || level == null || guessEngine == null) return;
		WorldIdentifier id = WorldIdentifier.of(level);
		if (id == null) return;
		WorldEngine real = instance.getOrCreate(id);
		if (real == null || real == guessEngine || !real.isLive()) return;
		real.acquireRef();
		try {
			real.storage.iteratePositions(0, key -> out.accept(net.minecraft.world.level.ChunkPos.pack(WorldEngine.getX(key), WorldEngine.getZ(key))));
		} finally {
			real.releaseRef();
		}
	}

	/** Whether layer y (0..31) of a 32-block LOD section is all air within chunk quarter (ox, oz). */
	private static boolean layerAir(long[] data, int ox, int y, int oz) {
		for (int z = 0; z < 16; z++)
			for (int x = 0; x < 16; x++)
				if (!me.cortex.voxy.common.world.other.Mapper.isAir(data[me.cortex.voxy.common.world.WorldSection.getIndex(ox * 16 + x, y, oz * 16 + z)])) return false;
		return true;
	}

	private static long remap(long v, WorldEngine from, WorldEngine to) {
		byte light = (byte) me.cortex.voxy.common.world.other.Mapper.getLightId(v);
		if (me.cortex.voxy.common.world.other.Mapper.isAir(v)) return me.cortex.voxy.common.world.other.Mapper.airWithLight(light & 0xFF);
		int block = me.cortex.voxy.common.world.other.Mapper.getBlockId(v), biome = me.cortex.voxy.common.world.other.Mapper.getBiomeId(v);
		int nb = blockMap.computeIfAbsent(block, b -> to.getMapper().getIdForBlockState(from.getMapper().getBlockStateFromBlockId(b)));
		int nbi = biomeMap.computeIfAbsent(biome, b -> biomeId(from, to, b));
		return me.cortex.voxy.common.world.other.Mapper.composeMappingId(light, nb, nbi);
	}

	private static int biomeId(WorldEngine from, WorldEngine to, int id) {
		var entries = from.getMapper().getBiomeEntries();
		if (id < 0 || id >= entries.length || entries[id] == null) return 0;
		var level = Minecraft.getInstance().level;
		if (level == null) return 0;
		var key = ResourceKey.create(Registries.BIOME, Identifier.parse(entries[id].biome));
		return level.registryAccess().lookupOrThrow(Registries.BIOME).get(key).map(h -> to.getMapper().getIdForBiome(h)).orElse(0);
	}

	static boolean showing() {
		WorldEngine g = guessEngine;
		return g != null && renderedWorld() == g;
	}

	static void show(boolean guessed) {
		wantGuessed = guessed;
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.levelRenderer instanceof IVoxyRenderSystemHolder holder)) return;
		WorldEngine g = guessEngine;
		VoxyRenderSystem rs = holder.voxy$getRenderSystem();
		if (rs == null) return; // Voxy off or not set up yet
		WorldEngine rendered = renderedWorld();
		try {
			if (guessed && g != null && rendered != g) {
				holder.voxy$shutdownRenderer();
				createEngineDirect().invoke(mc.levelRenderer, g);
			} else if ((!guessed || g == null) && rendered != null && rendered != WorldIdentifier.ofEngineNullable(mc.level)) {
				holder.voxy$shutdownRenderer();
				holder.voxy$createRenderer();
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			InfiniTerraMod.LOG.error("Could not switch Voxy's renderer", e);
			guessEngine = null;
		}
	}

	/** Rebuilds Voxy's renderer on the guessed world, so it reads everything from storage again. */
	static void reload() {
		Minecraft mc = Minecraft.getInstance();
		WorldEngine g = guessEngine;
		if (g == null || !(mc.levelRenderer instanceof IVoxyRenderSystemHolder holder) || renderedWorld() != g) return;
		try {
			holder.voxy$shutdownRenderer();
			createEngineDirect().invoke(mc.levelRenderer, g);
		} catch (ReflectiveOperationException | RuntimeException e) {
			InfiniTerraMod.LOG.error("Could not reload Voxy's renderer", e);
		}
	}

	private static WorldEngine renderedWorld() {
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.levelRenderer instanceof IVoxyRenderSystemHolder holder)) return null;
		VoxyRenderSystem rs = holder.voxy$getRenderSystem();
		if (rs == null) return null;
		try {
			if (rendererWorld == null) {
				rendererWorld = VoxyRenderSystem.class.getDeclaredField("worldIn");
				rendererWorld.setAccessible(true);
			}
			return (WorldEngine) rendererWorld.get(rs);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Method createEngineDirect() throws NoSuchMethodException {
		if (createEngineDirect == null) {
			for (Method m : Minecraft.getInstance().levelRenderer.getClass().getDeclaredMethods()) {
				if (m.getName().endsWith("createEngineDirect") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == WorldEngine.class) {
					m.setAccessible(true);
					createEngineDirect = m;
				}
			}
			if (createEngineDirect == null) throw new NoSuchMethodException("voxy$createEngineDirect");
		}
		return createEngineDirect;
	}
}
