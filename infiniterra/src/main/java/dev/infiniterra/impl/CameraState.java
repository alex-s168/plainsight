package dev.infiniterra.impl;

/** The detached camera's state this frame (see {@link dev.infiniterra.api.DetachedCamera}); set by the Camera mixin. */
public final class CameraState {
	public static volatile boolean active, spectatorCulling = true;
	/** Main-view frames in which Voxy drew its LOD without the chunk bounds, the camera being outside the loaded chunks (tests). */
	public static volatile long lodWithoutBounds;
	public static volatile float toPlayer;
	public static volatile boolean outsideLoaded, caveCullingOff;

	private CameraState() {
	}
}
