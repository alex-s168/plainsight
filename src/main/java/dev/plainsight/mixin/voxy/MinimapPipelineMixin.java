package dev.plainsight.mixin.voxy;

import dev.plainsight.minimap.MinimapTargets;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The minimap's own depth/stencil target, swapped with the pipeline's for the minimap pass (see {@link MinimapTargets}). */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class MinimapPipelineMixin implements MinimapTargets {
	@Shadow
	@Final
	@Mutable
	public DepthFramebuffer fb;
	@Unique
	private DepthFramebuffer plainsight$minimapFb;

	@Override
	public void plainsight$swapMinimapTargets() {
		if (plainsight$minimapFb == null) plainsight$minimapFb = new DepthFramebuffer(GL30.GL_DEPTH24_STENCIL8);
		DepthFramebuffer t = fb;
		fb = plainsight$minimapFb;
		plainsight$minimapFb = t;
	}

	@Inject(method = "free0", at = @At("HEAD"))
	private void plainsight$freeMinimap(CallbackInfo ci) {
		if (plainsight$minimapFb != null) plainsight$minimapFb.free();
		plainsight$minimapFb = null;
	}
}
