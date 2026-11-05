package dev.infiniterra.mixin;

import dev.infiniterra.impl.CameraState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A detached camera ({@link dev.infiniterra.api.DetachedCamera}): its far plane, and its state every frame. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private float depthFar;

	/**
	 * A far plane that reaches the distant terrain (the LOD, guessed terrain): depth is what a host aims
	 * with, and terrain beyond the normal far plane would sit there at the wrong depth.
	 */
	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void infiniterra$farWhenDetached(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (CameraState.active) depthFar = Math.max(depthFar, 65536f);
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void infiniterra$detachedState(CameraRenderState cameraState, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!CameraState.active) {
			CameraState.toPlayer = 0;
			CameraState.outsideLoaded = CameraState.caveCullingOff = CameraState.atLoadedEdge = false;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) CameraState.toPlayer = (float) Math.hypot(cameraState.pos.x - mc.player.getX(), cameraState.pos.z - mc.player.getZ());
		// like spectator mode: inside solid blocks, render every section (no cave culling), so cave systems
		// are visible from within the rock; the same when the camera is outside the loaded chunks, or the
		// loaded terrain may not be found (the client level's hasChunk is always true; its chunk cache knows)
		var level = mc.level;
		boolean outside = level != null && !level.getChunkSource().hasChunk(cameraState.blockPos.getX() >> 4, cameraState.blockPos.getZ() >> 4);
		CameraState.outsideLoaded = outside;
		boolean edge = outside;
		int cx = cameraState.blockPos.getX() >> 4, cz = cameraState.blockPos.getZ() >> 4;
		// the higher the camera, the further in it sees the edge from outside: a chunk more per 16 blocks above sea level
		int m = CameraState.EDGE_MARGIN + Math.min(CameraState.MAX_EDGE_MARGIN, Math.max(0, (cameraState.blockPos.getY() - 63) >> 4));
		for (int dx = -m; dx <= m && !edge && level != null; dx++)
			for (int dz = -m; dz <= m && !edge; dz++)
				edge = !level.getChunkSource().hasChunk(cx + dx, cz + dz);
		CameraState.atLoadedEdge = edge;
		boolean noCulling = CameraState.spectatorCulling && level != null && (outside || level.getBlockState(cameraState.blockPos).isSolidRender());
		CameraState.caveCullingOff = noCulling;
		if (noCulling) cameraState.smartCull = false;
	}
}
