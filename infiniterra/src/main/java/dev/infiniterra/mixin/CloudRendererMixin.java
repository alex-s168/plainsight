package dev.infiniterra.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No clouds while the editor shows guessed terrain: they would get the guessed-terrain tint (it marks by
 * depth) and haze the view of the generated land from above.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@Inject(method = {"render", "renderOit"}, at = @At("HEAD"), cancellable = true)
	private void infiniterra$noCloudsOverGuesses(CallbackInfo ci) {
		if (dev.infiniterra.impl.guess.VoxyGuess.showing() && dev.infiniterra.impl.CameraState.active) ci.cancel();
	}
}
