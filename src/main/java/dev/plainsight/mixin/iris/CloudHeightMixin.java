package dev.plainsight.mixin.iris;

import dev.plainsight.minimap.MinimapIris;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoomed out, the minimap camera is above the clouds and the pack's clouds would cover the map: during its
 * pass they are far above it (packs place their clouds by cloudHeight). Optional: the supplier is a lambda.
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms$WorldInfoUniforms", remap = false)
public abstract class CloudHeightMixin {
	@Inject(method = "lambda$addWorldInfoUniforms$1", at = @At("HEAD"), cancellable = true, require = 0)
	private static void plainsight$noCloudsOnMinimap(CallbackInfoReturnable<Double> cir) {
		if (MinimapIris.active) cir.setReturnValue(1e5);
	}
}
