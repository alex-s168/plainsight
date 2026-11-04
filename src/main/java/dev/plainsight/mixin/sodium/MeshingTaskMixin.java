package dev.plainsight.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.module.FluidTransparency;
import dev.plainsight.module.CutawayRegion;
import dev.plainsight.module.SeeThrough;
import dev.plainsight.render.AlphaOverride;
import dev.plainsight.render.CutMeshing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
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
 * <p>
 * See-through: the meshing loop reads the real block at cut positions (the level slice reports air
 * there to everyone else), meshes it with the see-through opacity in the translucent pass (see
 * BlockRendererMixin) against the real world, and does not count it as an occluder. Cut blocks with a
 * block entity, and all cut blocks at an opacity near 0, stay hidden.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MeshingTaskMixin {
	@WrapOperation(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"))
	private void plainsight$fluids(FluidRenderer renderer, LevelSlice slice, BlockState block, FluidState fluid, BlockPos pos, BlockPos offset,
			TranslucentGeometryCollector collector, ChunkBuildBuffers buffers, Operation<Void> original) {
		float alpha = FluidTransparency.isActive() ? FluidTransparency.opacity() : 1f;
		if (CutMeshing.current()) alpha = Math.min(alpha, SeeThrough.opacity());
		if (alpha >= 0.999f) {
			original.call(renderer, slice, block, fluid, pos, offset, collector, buffers);
			return;
		}
		if (alpha <= 0.01f) return;
		AlphaOverride.begin(Math.round(alpha * 255));
		try {
			original.call(renderer, slice, block, fluid, pos, offset, collector, buffers);
		} finally {
			AlphaOverride.end();
		}
	}

	@WrapOperation(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState plainsight$cutBlock(LevelSlice slice, int x, int y, int z, Operation<BlockState> original) {
		CutawayRegion cut = SeeThrough.region();
		CutMeshing.setCurrent(false);
		if (cut.isEmpty() || !cut.contains(x, y, z) || SeeThrough.opacity() * 255 < 3) return original.call(slice, x, y, z);
		BlockState real;
		CutMeshing.setRaw(true);
		try {
			real = original.call(slice, x, y, z);
		} finally {
			CutMeshing.setRaw(false);
		}
		if (real.hasBlockEntity()) return original.call(slice, x, y, z);
		CutMeshing.setCurrent(true);
		return real;
	}

	@WrapOperation(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"))
	private void plainsight$cutModel(BlockRenderer renderer, BlockStateModel model, BlockState state, BlockPos pos, BlockPos offset, Operation<Void> original) {
		if (!CutMeshing.current()) {
			original.call(renderer, model, state, pos, offset);
			return;
		}
		// the real world around the cut block: faces between cut blocks stay culled (only the shell shows)
		CutMeshing.setRaw(true);
		AlphaOverride.begin(Math.round(SeeThrough.opacity() * 255));
		try {
			original.call(renderer, model, state, pos, offset);
		} finally {
			AlphaOverride.end();
			CutMeshing.setRaw(false);
		}
	}

	/** Cut blocks must not occlude: otherwise the sections behind them (with the player) get culled. */
	@WrapOperation(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
	private boolean plainsight$cutOccluder(BlockState state, Operation<Boolean> original) {
		return !CutMeshing.current() && original.call(state);
	}
}
