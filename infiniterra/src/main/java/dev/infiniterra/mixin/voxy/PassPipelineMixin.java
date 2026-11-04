package dev.infiniterra.mixin.voxy;

import dev.infiniterra.impl.pass.PassTargets;
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

/** The camera pass's own depth/stencil target, swapped with the pipeline's for the camera pass (see {@link PassTargets}). */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class PassPipelineMixin implements PassTargets {
	@Shadow
	@Final
	@Mutable
	public DepthFramebuffer fb;
	@Unique
	private DepthFramebuffer infiniterra$passFb;

	@Override
	public void infiniterra$swapPassTargets() {
		if (infiniterra$passFb == null) infiniterra$passFb = new DepthFramebuffer(GL30.GL_DEPTH24_STENCIL8);
		DepthFramebuffer t = fb;
		fb = infiniterra$passFb;
		infiniterra$passFb = t;
	}

	@Inject(method = "free0", at = @At("HEAD"))
	private void infiniterra$freePass(CallbackInfo ci) {
		if (infiniterra$passFb != null) infiniterra$passFb.free();
		infiniterra$passFb = null;
	}
}
