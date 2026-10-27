package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.plainsight.render.BlockOverlayRenderer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Overlay blocks drawn see-through must go through the translucent (sorted) submit path. */
@Mixin(SubmitNodeCollection.class)
public abstract class SubmitNodeCollectionMixin {
	@ModifyExpressionValue(method = "submitMovingBlock", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;hasMaterialFlag(I)Z"))
	private boolean plainsight$translucent(boolean translucent, @Local(argsOnly = true) MovingBlockRenderState state) {
		return translucent || BlockOverlayRenderer.alphaOf(state) >= 0;
	}
}
