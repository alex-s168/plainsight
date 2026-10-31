package dev.plainsight.mixin;

import dev.plainsight.editor.Editor;
import dev.plainsight.render.DepthPick;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Always-on-top gizmos (e.g. the chunk borders, F3+G) clear the depth before they are drawn: the editor
 * reads the depth under the cursor before that.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "executeAlwaysOnTop", at = @At("HEAD"))
	private void plainsight$depthBeforeOnTop(CallbackInfo ci) {
		if (Editor.isActive()) DepthPick.sampleOnce(levelRenderState.cameraRenderState);
	}
}
