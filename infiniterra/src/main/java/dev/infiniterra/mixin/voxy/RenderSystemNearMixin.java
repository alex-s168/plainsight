package dev.infiniterra.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Voxy cuts its LOD off 8 or 16 blocks in front of the camera (normal terrain is drawn there); the
 * editor camera can be right at distant or guessed terrain, so there the near plane is vanilla's 0.05
 * (Voxy's reverse-Z depth keeps the precision far away).
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class RenderSystemNearMixin {
	@WrapOperation(method = "setupViewport", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/VoxyRenderSystem;computeProjectionMat(Lme/cortex/voxy/client/core/RenderProperties;Lorg/joml/Matrix4fc;FF)Lorg/joml/Matrix4f;"))
	private Matrix4f infiniterra$nearInEditor(RenderProperties properties, Matrix4fc base, float near, float far, Operation<Matrix4f> original) {
		// the camera pass brings its own camera projection (already in Voxy's depth convention); the editor's
		// map pass has the game's, like the main view
		if (dev.infiniterra.impl.pass.PassVoxy.inPass && !dev.infiniterra.impl.pass.Passes.frameProjection) return new Matrix4f(base);
		return original.call(properties, base, dev.infiniterra.impl.CameraState.active ? 0.05f : near, far);
	}
}
