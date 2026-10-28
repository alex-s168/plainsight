package dev.plainsight.guess;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Entry points for the Voxy ingest mixin (public, the implementation stays package-private). */
public final class VoxyMirror {
	private VoxyMirror() {
	}

	public static void chunk(VoxelIngestService service, WorldEngine engine, LevelChunk chunk) {
		VoxyGuessImpl.mirror(service, engine, chunk);
	}

	public static void section(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer blockLight, DataLayer skyLight) {
		VoxyGuessImpl.mirrorSection(engine, section, x, y, z, blockLight, skyLight);
	}
}
