package dev.plainsight.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.module.FluidTransparency;
import dev.plainsight.render.AlphaOverride;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Transparent fluids with Sodium: fluids are meshed with an alpha override (water is translucent and
 * blends; lava stays in Sodium's opaque pass) and skipped entirely at opacity 0.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MeshingTaskMixin {
	@WrapOperation(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"))
	private void plainsight$fluids(FluidRenderer renderer, LevelSlice slice, BlockState block, FluidState fluid, BlockPos pos, BlockPos offset,
			TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, Operation<Void> original) {
		if (!FluidTransparency.isActive() || FluidTransparency.opacity() >= 0.999f) {
			original.call(renderer, slice, block, fluid, pos, offset, collector, buffers);
			return;
		}
		if (FluidTransparency.opacity() <= 0.01f) return;
		AlphaOverride.begin(Math.round(FluidTransparency.opacity() * 255));
		try {
			original.call(renderer, slice, block, fluid, pos, offset, collector, buffers);
		} finally {
			AlphaOverride.end();
		}
	}
}
