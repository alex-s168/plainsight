package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.module.CutNeighborView;
import dev.plainsight.render.AlphaOverride;
import dev.plainsight.module.CutawayRegion;
import dev.plainsight.module.FluidTransparency;
import dev.plainsight.module.SeeThrough;
import dev.plainsight.render.AlphaVertexConsumer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;


/**
 * Chunk meshing hooks for the editor's view options: translucent fluids, and blocks between the
 * camera and players moved into the translucent layer with reduced alpha (or skipped).
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void plainsight$fluids(FluidRenderer renderer, BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output,
			BlockState blockState, FluidState fluidState, Operation<Void> original) {
		CutawayRegion cut = SeeThrough.region();
		boolean isCut = !cut.isEmpty() && cut.contains(pos.getX(), pos.getY(), pos.getZ());
		float alpha = 1f;
		if (FluidTransparency.isActive()) alpha = FluidTransparency.opacity();
		if (isCut) alpha = Math.min(alpha, SeeThrough.opacity());
		if (alpha >= 0.999f) {
			original.call(renderer, level, pos, output, blockState, fluidState);
			return;
		}
		if (alpha <= 0.01f) return;
		float a = alpha;
		FluidRenderer.Output translucent = layer -> new AlphaVertexConsumer(output.getBuilder(ChunkSectionLayer.TRANSLUCENT), a);
		original.call(renderer, level, pos, translucent, blockState, fluidState);
	}

	/**
	 * Cut blocks are tesselated with {@link AlphaOverride} set, which moves their quads to the translucent
	 * layer with reduced alpha; blocks next to the cut see cut blocks as air so they keep their faces.
	 * The original call may be replaced by another renderer (Fabric's renderer API does), which is why
	 * this works on the buffers rather than on the quad output.
	 */
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void plainsight$cutaway(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
			BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
		CutawayRegion cut = SeeThrough.region();
		if (cut.isEmpty()) {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} else if (cut.contains(pos.getX(), pos.getY(), pos.getZ())) {
			int alpha = Math.round(SeeThrough.opacity() * 255);
			if (alpha < 3) return;
			AlphaOverride.begin(alpha);
			try {
				// the real world: faces between cut blocks stay culled, so only their outer shell is drawn faintly
				original.call(renderer, output, x, y, z, level, pos, state, model, seed);
			} finally {
				AlphaOverride.end();
			}
		} else if (CutNeighborView.affected(cut, pos)) {
			original.call(renderer, output, x, y, z, new CutNeighborView(level, cut, pos), pos, state, model, seed);
		} else {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		}
	}

	@ModifyVariable(method = "getOrBeginLayer", at = @At("HEAD"), argsOnly = true)
	private ChunkSectionLayer plainsight$cutLayer(ChunkSectionLayer layer) {
		return AlphaOverride.alpha() >= 0 ? ChunkSectionLayer.TRANSLUCENT : layer;
	}

	/** Cut blocks must not occlude: otherwise the sections behind them (with the player) get culled. */
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/VisGraph;setOpaque(Lnet/minecraft/core/BlockPos;)V"))
	private void plainsight$visibility(VisGraph graph, BlockPos pos, Operation<Void> original) {
		CutawayRegion cut = SeeThrough.region();
		if (!cut.isEmpty() && cut.contains(pos.getX(), pos.getY(), pos.getZ())) return;
		original.call(graph, pos);
	}
}
