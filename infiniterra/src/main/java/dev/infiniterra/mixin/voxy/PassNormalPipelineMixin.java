package dev.infiniterra.mixin.voxy;

import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The camera pass's own colour targets, swapped with the pipeline's for the camera pass along with the depth
 * target ({@link PassPipelineMixin}); the pipeline allocates them at the camera pass's size the first time.
 */
@Mixin(value = NormalRenderPipeline.class, remap = false)
public abstract class PassNormalPipelineMixin extends PassPipelineMixin {
	@Shadow
	private GlTexture colourTex;
	@Shadow
	private GlTexture colourSSAOTex;
	@Shadow
	@Final
	@Mutable
	private GlFramebuffer fbSSAO;
	@Unique
	private GlTexture infiniterra$colourTex, infiniterra$colourSSAOTex;
	@Unique
	private GlFramebuffer infiniterra$fbSSAO;

	@Override
	public void infiniterra$swapPassTargets() {
		super.infiniterra$swapPassTargets();
		if (infiniterra$fbSSAO == null) infiniterra$fbSSAO = new GlFramebuffer();
		GlTexture c = colourTex, s = colourSSAOTex;
		colourTex = infiniterra$colourTex;
		colourSSAOTex = infiniterra$colourSSAOTex;
		infiniterra$colourTex = c;
		infiniterra$colourSSAOTex = s;
		GlFramebuffer f = fbSSAO;
		fbSSAO = infiniterra$fbSSAO;
		infiniterra$fbSSAO = f;
	}

	@Inject(method = "free", at = @At("HEAD"))
	private void infiniterra$freePassColour(CallbackInfo ci) {
		if (infiniterra$colourTex != null) infiniterra$colourTex.free();
		if (infiniterra$colourSSAOTex != null) infiniterra$colourSSAOTex.free();
		if (infiniterra$fbSSAO != null) infiniterra$fbSSAO.free();
		infiniterra$colourTex = infiniterra$colourSSAOTex = null;
		infiniterra$fbSSAO = null;
	}
}
