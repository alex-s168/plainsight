package dev.infiniterra.mixin.voxy;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copies every real chunk Voxy ingests into the guessed LOD world (see dev.infiniterra.impl.guess.VoxyGuessImpl). */
@Mixin(value = VoxelIngestService.class, remap = false)
public abstract class IngestMirrorMixin {
	@Inject(method = "enqueueIngest", at = @At("RETURN"))
	private void infiniterra$mirror(WorldEngine engine, LevelChunk chunk, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) dev.infiniterra.impl.guess.VoxyMirror.chunk((VoxelIngestService) (Object) this, engine, chunk);
	}

	@Inject(method = "rawIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
			at = @At("RETURN"))
	private static void infiniterra$mirrorSection(WorldEngine engine, LevelChunkSection section, int x, int y, int z, DataLayer blockLight,
			DataLayer skyLight, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) dev.infiniterra.impl.guess.VoxyMirror.section(engine, section, x, y, z, blockLight, skyLight);
	}
}
