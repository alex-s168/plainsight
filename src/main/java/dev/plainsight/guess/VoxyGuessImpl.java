package dev.plainsight.guess;

import dev.plainsight.Plainsight;
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

	static void attach(GuessWorld w) {
		Minecraft mc = Minecraft.getInstance();
		var instance = VoxyCommon.getInstance();
		if (instance == null || mc.level == null) return;
		WorldIdentifier real = WorldIdentifier.of(mc.level);
		if (real == null) return;
		Identifier id = Identifier.fromNamespaceAndPath(Plainsight.ID, "guess/" + w.dimension.identifier().getNamespace() + "/"
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

	static boolean ingestGuessed(LevelChunk chunk) {
		WorldEngine e = guessEngine;
		var instance = VoxyCommon.getInstance();
		if (e == null || instance == null || !e.isLive()) return false;
		MIRRORING.set(true);
		try {
			return instance.getIngestService().enqueueIngest(e, chunk);
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
			if (VoxelIngestService.rawIngest(g, section, x, y, z, blockLight, skyLight)) w.markReal(x, z);
		} finally {
			MIRRORING.set(false);
		}
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
			Plainsight.LOG.error("Could not switch Voxy's renderer", e);
			guessEngine = null;
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
