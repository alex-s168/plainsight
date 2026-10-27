package dev.plainsight.mixin;

import dev.plainsight.editor.Editor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the editor is open the camera is the editor's free camera (detached, so the player is drawn). */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void plainsight$editorCamera(float partialTicks, CallbackInfo ci) {
		Editor editor = Editor.get();
		editor.frame();
		if (!Editor.isActive()) return;
		var c = editor.camera;
		setPosition(c.pos.x, c.pos.y, c.pos.z);
		setRotation(c.yaw, c.pitch);
		detached = true;
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void plainsight$editorRenderState(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Editor.isActive()) return;
		Editor.get().viewport.adjust(cameraState.projectionMatrix);
		// like spectator mode: inside solid blocks, render every section (no cave culling), so cave systems
		// are visible from within the rock
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (Editor.spectatorCulling && level != null && level.getBlockState(cameraState.blockPos).isSolidRender()) cameraState.smartCull = false;
	}
}
