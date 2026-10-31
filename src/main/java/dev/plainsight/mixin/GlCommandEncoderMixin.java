package dev.plainsight.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copying a depth texture to a buffer sets the encoder's shared read framebuffer to read from GL_NONE and
 * leaves it so. Every later colour readback through it (screenshots, F2 and the dev bridge) then reads
 * nothing and comes out black. DepthPick does such a copy each frame the editor is open, so restore the
 * read buffer while that framebuffer is still bound.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glBindFramebuffer(II)V"))
	private void plainsight$restoreReadBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y,
			int width, int height, CallbackInfo ci) {
		if (source.getFormat().hasDepthAspect()) GlStateManager._glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
	}
}
