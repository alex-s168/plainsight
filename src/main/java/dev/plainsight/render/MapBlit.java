package dev.plainsight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.plainsight.editor.Viewport;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * Shows the editor's map in the viewport: the map pass (same camera and size as the level frame) is
 * copied over the level there, its depth too when it has one, so the editor's overlays and its aim at
 * the surface under the cursor (DepthPick) see the map's terrain (the player's layer) rather than the
 * level's.
 */
public final class MapBlit {
	private static int from, to;

	private MapBlit() {
	}

	public static void blit(GpuTexture colour, @Nullable GpuTexture depth, RenderTarget main, Viewport vp) {
		if (from == 0) {
			from = GL45.glCreateFramebuffers();
			to = GL45.glCreateFramebuffers();
		}
		GL45.glNamedFramebufferTexture(from, GL30.GL_COLOR_ATTACHMENT0, ((GlTexture) colour).glId(), 0);
		GL45.glNamedFramebufferTexture(from, GL30.GL_DEPTH_ATTACHMENT, depth == null ? 0 : ((GlTexture) depth).glId(), 0);
		GL45.glNamedFramebufferTexture(to, GL30.GL_COLOR_ATTACHMENT0, ((GlTexture) main.getColorTexture()).glId(), 0);
		GL45.glNamedFramebufferTexture(to, GL30.GL_DEPTH_ATTACHMENT, depth == null ? 0 : ((GlTexture) main.getDepthTexture()).glId(), 0);
		// the viewport in framebuffer pixels (GL rows start at the bottom)
		float scale = main.width / Math.max(1f, vp.windowWidth);
		int x0 = Math.round(vp.x * scale), x1 = Math.round((vp.x + vp.width) * scale);
		int y0 = main.height - Math.round((vp.y + vp.height) * scale), y1 = main.height - Math.round(vp.y * scale);
		GlStateManager._disableScissorTest();
		int mask = GL11.GL_COLOR_BUFFER_BIT | (depth != null ? GL11.GL_DEPTH_BUFFER_BIT : 0);
		GL45.glBlitNamedFramebuffer(from, to, x0, y0, x1, y1, x0, y0, x1, y1, mask, GL11.GL_NEAREST);
	}
}
