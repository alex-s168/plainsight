package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.plainsight.render.AlphaOverride;
import dev.plainsight.render.BlockOverlayRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Translucent block overlays: submitted as translucent, tesselated with an alpha override. */
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class MovingBlockMixin {
	@WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void plainsight$ghost(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
			BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original,
			@Local MovingBlockFeatureRenderer.Submit submit) {
		int alpha = BlockOverlayRenderer.alphaOf(submit.movingBlockRenderState());
		if (alpha < 0) {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
			return;
		}
		AlphaOverride.begin(alpha);
		try {
			original.call(renderer, output, x, y, z, level, pos, state, model, seed);
		} finally {
			AlphaOverride.end();
		}
	}
}
