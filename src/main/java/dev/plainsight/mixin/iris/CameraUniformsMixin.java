package dev.plainsight.mixin.iris;

import dev.plainsight.minimap.MinimapIris;
import net.irisshaders.iris.uniforms.CameraUniforms;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The pack's camera position (cameraPosition and the rest) is the minimap camera's during its pass. */
@Mixin(value = CameraUniforms.class, remap = false)
public abstract class CameraUniformsMixin {
	@Inject(method = "getUnshiftedCameraPosition", at = @At("HEAD"), cancellable = true)
	private static void plainsight$minimapCamera(CallbackInfoReturnable<Vector3d> cir) {
		if (MinimapIris.active) cir.setReturnValue(MinimapIris.cameraPosition());
	}
}
