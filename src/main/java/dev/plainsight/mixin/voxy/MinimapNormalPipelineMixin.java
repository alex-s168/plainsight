package dev.plainsight.mixin.voxy;

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
 * The minimap's own colour targets, swapped with the pipeline's for the minimap pass along with the depth
 * target ({@link MinimapPipelineMixin}); the pipeline allocates them at the minimap's size the first time.
 */
@Mixin(value = NormalRenderPipeline.class, remap = false)
public abstract class MinimapNormalPipelineMixin extends MinimapPipelineMixin {
	@Shadow
	private GlTexture colourTex;
	@Shadow
	private GlTexture colourSSAOTex;
	@Shadow
	@Final
	@Mutable
	private GlFramebuffer fbSSAO;
	@Unique
	private GlTexture plainsight$colourTex, plainsight$colourSSAOTex;
	@Unique
	private GlFramebuffer plainsight$fbSSAO;

	@Override
	public void plainsight$swapMinimapTargets() {
		super.plainsight$swapMinimapTargets();
		if (plainsight$fbSSAO == null) plainsight$fbSSAO = new GlFramebuffer();
		GlTexture c = colourTex, s = colourSSAOTex;
		colourTex = plainsight$colourTex;
		colourSSAOTex = plainsight$colourSSAOTex;
		plainsight$colourTex = c;
		plainsight$colourSSAOTex = s;
		GlFramebuffer f = fbSSAO;
		fbSSAO = plainsight$fbSSAO;
		plainsight$fbSSAO = f;
	}

	@Inject(method = "free", at = @At("HEAD"))
	private void plainsight$freeMinimapColour(CallbackInfo ci) {
		if (plainsight$colourTex != null) plainsight$colourTex.free();
		if (plainsight$colourSSAOTex != null) plainsight$colourSSAOTex.free();
		if (plainsight$fbSSAO != null) plainsight$fbSSAO.free();
		plainsight$colourTex = plainsight$colourSSAOTex = null;
		plainsight$fbSSAO = null;
	}
}
