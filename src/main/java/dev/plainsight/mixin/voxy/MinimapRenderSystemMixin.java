package dev.plainsight.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.minimap.MinimapVoxy;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.BoundRenderer;
import me.cortex.voxy.client.core.rendering.bounding.IBoundStore;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the minimap pass after Voxy's frame, and keeps it from changing what the main view relies on. */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class MinimapRenderSystemMixin {
	@Shadow
	@Final
	private RenderProperties properties;

	@Inject(method = "renderOpaque", at = @At("TAIL"))
	private void plainsight$minimap(Viewport<?> viewport, int depth, int colour, CallbackInfo ci) {
		MinimapVoxy.render((VoxyRenderSystem) (Object) this, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	/** Voxy loads LOD around the rendered camera: that stays the player's view, not the minimap camera. */
	@WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"))
	private boolean plainsight$keepCentre(RenderDistanceTracker tracker, double x, double z, Operation<Boolean> original) {
		return !MinimapVoxy.inPass && original.call(tracker, x, z);
	}

	/**
	 * Voxy leaves out the LOD where the game draws its loaded chunks (their bounds go into the viewport's
	 * depth bounds); the minimap has no game terrain, so it draws the LOD there too (as when Voxy alone
	 * renders everything).
	 */
	@WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/bounding/BoundRenderer;render(Lme/cortex/voxy/client/core/rendering/Viewport;Lme/cortex/voxy/client/core/rendering/bounding/IBoundStore;)V"))
	private void plainsight$noChunkBounds(BoundRenderer renderer, Viewport<?> viewport, IBoundStore store, Operation<Void> original) {
		if (MinimapVoxy.inPass) viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());
		else original.call(renderer, viewport, store);
	}
}
