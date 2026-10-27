package dev.plainsight.mixin;

import dev.plainsight.module.Brightness;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Editor / in-game brightness: raises gamma and night-vision ambient light, up to fullbright. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapMixin {
	@Inject(method = "extract", at = @At("TAIL"))
	private void plainsight$brightness(LightmapRenderState state, float partialTicks, CallbackInfo ci) {
		float level = Brightness.current();
		if (level <= 0 || !state.needsUpdate) return;
		state.brightness = Math.max(state.brightness, level);
		state.nightVisionEffectIntensity = Math.max(state.nightVisionEffectIntensity, level);
		state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
	}
}
