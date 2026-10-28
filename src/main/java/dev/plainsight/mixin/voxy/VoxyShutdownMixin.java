package dev.plainsight.mixin.voxy;

import me.cortex.voxy.commonImpl.VoxyInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy shuts its worlds down when leaving a world, and aborts the game if one is still in use; the
 * guessed LOD world (and the copier reading the normal one) are let go first.
 */
@Mixin(value = VoxyInstance.class, remap = false)
public abstract class VoxyShutdownMixin {
	@Inject(method = "shutdown", at = @At("HEAD"))
	private void plainsight$releaseGuessWorld(CallbackInfo ci) {
		dev.plainsight.guess.GuessWorld.shutdown();
	}
}
