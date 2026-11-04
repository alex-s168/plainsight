package dev.infiniterra.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * With a shader pack the camera pass renders through it as a frame of its own, before the level's (which
	 * then sets up all the state it needs again; see PassIris).
	 */
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void infiniterra$passThroughPack(CallbackInfo ci) {
		if (dev.infiniterra.impl.pass.Passes.wanted())
			dev.infiniterra.impl.pass.PassVoxy.throughPack(net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}
}
