package dev.infiniterra.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.infiniterra.impl.pass.PassVoxy;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The camera pass shows the layer the player is on: in its pass, LOD terrain above the player's layer is
 * clipped off (the terrain shader writes the clip distance, see {@link ShaderPatchMixin#patchTerrain}), and
 * everything is lit as by daylight ({@link ShaderPatchMixin#patchLighting}).
 */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class PassTerrainCutMixin {
	@Shadow
	@Final
	private Shader terrainShader;

	@Shadow
	@Final
	private Shader translucentTerrainShader;

	@WrapOperation(method = "renderTerrain", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"))
	private void infiniterra$cutAbovePlayer(int mode, int type, long indirect, long drawCount, int maxDrawCount, int stride, Operation<Void> original) {
		infiniterra$cut(terrainShader, () -> original.call(mode, type, indirect, drawCount, maxDrawCount, stride));
	}

	/** Translucent LOD (water, ice, glass) has its own program and draw: cut it as well. */
	@WrapOperation(method = "renderTranslucent", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"))
	private void infiniterra$cutTranslucent(int mode, int type, long indirect, long drawCount, int maxDrawCount, int stride, Operation<Void> original) {
		infiniterra$cut(translucentTerrainShader, () -> original.call(mode, type, indirect, drawCount, maxDrawCount, stride));
	}

	private static void infiniterra$cut(Shader shader, Runnable draw) {
		boolean cut = PassVoxy.inPass && dev.infiniterra.impl.pass.Passes.cutY != Float.MAX_VALUE; // (the "top" layer option: no cut)
		int loc = cut ? GL20.glGetUniformLocation(shader.id(), "infiniterraCutY") : -1;
		if (loc < 0) {
			draw.run();
			return;
		}
		GL41.glProgramUniform1f(shader.id(), loc, dev.infiniterra.impl.pass.Passes.cutY);
		GL30.glEnable(GL30.GL_CLIP_DISTANCE0);
		try {
			draw.run();
		} finally {
			GL30.glDisable(GL30.GL_CLIP_DISTANCE0);
			// the main view draws with the same program (the patched lighting checks the cut too)
			GL41.glProgramUniform1f(shader.id(), loc, 1e30f);
		}
	}
}
