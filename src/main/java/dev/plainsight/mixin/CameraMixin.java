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

	/** The editor's map looks through a narrow field of view (almost a flat map). */
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void plainsight$mapFov(float partialTicks, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		if (Editor.isActive() && Editor.get().map.active()) cir.setReturnValue(dev.plainsight.editor.MapMode.FOV);
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void plainsight$editorCamera(float partialTicks, CallbackInfo ci) {
		Editor editor = Editor.get();
		editor.frame(partialTicks);
		dev.infiniterra.api.DetachedCamera.setActive(Editor.isActive());
		if (!Editor.isActive()) return;
		var c = editor.camera;
		setPosition(c.pos.x, c.pos.y, c.pos.z);
		setRotation(c.yaw, c.pitch);
		detached = true;
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void plainsight$editorRenderState(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Editor.isActive()) Editor.get().viewport.adjust(cameraState.projectionMatrix);
	}
}
