package dev.infiniterra.api;

import dev.infiniterra.impl.CameraState;

/**
 * The view's camera is away from the player, e.g. an editor's free camera. While it is, InfiniTerra
 * makes the terrain work from there: the render distance and fog reach the player's chunks from the
 * camera, cave culling is off inside rock or outside the loaded chunks (like spectator mode), the LOD is
 * depth-tested against the loaded terrain when the camera is outside it, and the near and far planes fit a
 * camera that may be close to blocks or far above them.
 * <p>
 * The host only says whether the camera is detached; InfiniTerra works out the rest every frame from the
 * camera and the player (the getters).
 */
public final class DetachedCamera {
	private DetachedCamera() {
	}

	/** Whether the view's camera is detached from the player (e.g. when an editor opens and closes). */
	public static void setActive(boolean detached) {
		CameraState.active = detached;
		if (!detached) {
			CameraState.toPlayer = 0;
			CameraState.outsideLoaded = CameraState.caveCullingOff = CameraState.atLoadedEdge = false;
		}
	}

	public static boolean active() {
		return CameraState.active;
	}

	/** Whether a camera inside solid blocks renders without cave culling (on by default; tests switch it). */
	public static void setSpectatorCulling(boolean on) {
		CameraState.spectatorCulling = on;
	}

	/** Horizontal distance from the camera to the player this frame (0 when not detached). */
	public static float toPlayer() {
		return CameraState.toPlayer;
	}

	/** Whether the camera is outside the loaded chunks this frame. */
	public static boolean outsideLoaded() {
		return CameraState.outsideLoaded;
	}

	/** Whether the camera is outside the loaded chunks or near their edge, so the LOD covers the loaded chunks too. */
	public static boolean atLoadedEdge() {
		return CameraState.atLoadedEdge;
	}

	/** Whether cave (occlusion) culling is off this frame (camera in rock or outside the loaded chunks). */
	public static boolean caveCullingOff() {
		return CameraState.caveCullingOff;
	}

	/** Main-view frames in which the LOD covered the loaded chunks too, the camera being outside them (tests). */
	public static long lodWithoutBoundsFrames() {
		return CameraState.lodWithoutBounds;
	}
}
