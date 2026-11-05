package dev.infiniterra.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.infiniterra.impl.pass.PassVoxy;
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

/** Runs the camera pass after Voxy's frame, and keeps it from changing what the main view relies on. */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class PassRenderSystemMixin {
	@Shadow
	@Final
	private RenderProperties properties;

	@Inject(method = "renderOpaque", at = @At("TAIL"))
	private void infiniterra$pass(Viewport<?> viewport, int depth, int colour, CallbackInfo ci) {
		PassVoxy.render((VoxyRenderSystem) (Object) this, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	/** The camera pass's own pipelines (with a shader pack) go with the render system they were made for. */
	@Inject(method = "shutdown", at = @At("HEAD"))
	private void infiniterra$releasePass(CallbackInfo ci) {
		PassVoxy.release();
	}

	/** Voxy loads LOD around the rendered camera: that stays the player's view, not the pass camera. */
	@WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"))
	private boolean infiniterra$keepCentre(RenderDistanceTracker tracker, double x, double z, Operation<Boolean> original) {
		return !PassVoxy.inPass && original.call(tracker, x, z);
	}

	/**
	 * Voxy leaves out the LOD where the game draws its loaded chunks (their bounds go into the viewport's
	 * depth bounds). The camera pass has no game terrain, so it draws the LOD there too (as when Voxy alone
	 * renders everything). So does an editor camera outside the loaded chunks or near their edge ({@code atLoadedEdge}): looking
	 * at the edge from outside, which in a corner of the rounded loaded area happens from inside too, the
	 * outermost chunks the game draws show their cut side (its faces are culled against the next ring, loaded
	 * but not drawn), and the LOD of the columns along the edge is what covers it; the LOD is then depth-tested
	 * against the terrain instead ({@link RenderPipelineMixin}) (#12).
	 */
	@WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/bounding/BoundRenderer;render(Lme/cortex/voxy/client/core/rendering/Viewport;Lme/cortex/voxy/client/core/rendering/bounding/IBoundStore;)V"))
	private void infiniterra$noChunkBounds(BoundRenderer renderer, Viewport<?> viewport, IBoundStore store, Operation<Void> original) {
		if (PassVoxy.inPass || dev.infiniterra.impl.CameraState.atLoadedEdge) {
			viewport.depthBoundingBuffer.clear(properties.inverseClearDepth());
			if (!PassVoxy.inPass) dev.infiniterra.impl.CameraState.lodWithoutBounds++;
		} else {
			original.call(renderer, viewport, store);
		}
	}
}
