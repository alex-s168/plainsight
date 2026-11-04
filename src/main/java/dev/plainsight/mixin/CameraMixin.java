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
	private float depthFar;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	/**
	 * A far plane that reaches the distant terrain (Voxy's LOD, guessed terrain): the depth buffer is what
	 * the editor aims with, and terrain beyond the normal far plane would sit there at the wrong depth.
	 */
	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void plainsight$farInEditor(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Editor.isActive()) depthFar = Math.max(depthFar, 65536f);
	}

	/** The editor's map looks through a narrow field of view (almost a flat map). */
	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void plainsight$mapFov(float partialTicks, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		if (Editor.isActive() && Editor.get().map.active()) cir.setReturnValue(dev.plainsight.editor.MapMode.FOV);
	}

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void plainsight$editorCamera(float partialTicks, CallbackInfo ci) {
		Editor editor = Editor.get();
		editor.frame(partialTicks);
		if (!Editor.isActive()) return;
		var c = editor.camera;
		setPosition(c.pos.x, c.pos.y, c.pos.z);
		setRotation(c.yaw, c.pitch);
		detached = true;
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void plainsight$editorRenderState(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!Editor.isActive()) {
			Editor.disableCaveCulling = false;
			Editor.cameraToPlayer = 0;
			Editor.cameraOutsideLoaded = false;
			return;
		}
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			Editor.cameraToPlayer = (float) Math.hypot(cameraState.pos.x - player.getX(), cameraState.pos.z - player.getZ());
		}
		Editor.get().viewport.adjust(cameraState.projectionMatrix);
		// like spectator mode: inside solid blocks, render every section (no cave culling), so cave systems
		// are visible from within the rock
		// the same when the camera is outside the loaded chunks, or the loaded terrain may not be found
		var level = net.minecraft.client.Minecraft.getInstance().level;
		// (the client level's hasChunk is always true; its chunk cache knows)
		boolean outside = level != null && !level.getChunkSource().hasChunk(cameraState.blockPos.getX() >> 4, cameraState.blockPos.getZ() >> 4);
		Editor.cameraOutsideLoaded = outside;
		boolean noCulling = Editor.spectatorCulling && level != null && (outside || level.getBlockState(cameraState.blockPos).isSolidRender());
		Editor.disableCaveCulling = noCulling;
		if (noCulling) cameraState.smartCull = false;
	}
}
