package dev.plainsight.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.editor.Editor;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Voxy draws its LOD only on pixels where Minecraft drew nothing (a stencil made from the terrain's
 * depth), assuming the camera is inside the loaded chunks so the terrain is always in front of the LOD.
 * From an editor camera outside them that is wrong: where the camera looks over LOD terrain at the side
 * of the loaded area, the side is behind the LOD but still claims the pixels, leaving a hole. Then the
 * stencil is not made, and Voxy's final blit depth-tests the LOD against the terrain instead.
 */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class RenderPipelineMixin {
	@WrapOperation(method = "initDepthStencil", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;blit()V"))
	private void plainsight$noStencilOutside(FullscreenBlit blit, Operation<Void> original) {
		if (!Editor.cameraOutsideLoaded) original.call(blit);
	}
}
