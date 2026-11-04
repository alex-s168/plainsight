package dev.infiniterra.impl;

/** The detached camera's state this frame (see {@link dev.infiniterra.api.DetachedCamera}); set by the Camera mixin. */
public final class CameraState {
	public static volatile boolean active, spectatorCulling = true;
	public static volatile float toPlayer;
	public static volatile boolean outsideLoaded, caveCullingOff;

	private CameraState() {
	}
}
