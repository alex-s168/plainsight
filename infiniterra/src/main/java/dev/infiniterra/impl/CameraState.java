package dev.infiniterra.impl;

/** The detached camera's state this frame (see {@link dev.infiniterra.api.DetachedCamera}); set by the Camera mixin. */
public final class CameraState {
	public static volatile boolean active, spectatorCulling = true;
	/** Main-view frames in which Voxy drew its LOD without the chunk bounds, the camera being outside the loaded chunks (tests). */
	public static volatile long lodWithoutBounds;
	public static volatile float toPlayer;
	public static volatile boolean outsideLoaded, caveCullingOff;
	/**
	 * The camera is outside the loaded chunks or near their edge (within {@link #EDGE_MARGIN} chunks, plus a
	 * chunk per 16 blocks above sea level up to {@link #MAX_EDGE_MARGIN} more): from there it can see the
	 * outermost chunks' cut sides from outside (at a corner of the rounded loaded area, or from above), so
	 * the LOD covers them (#12).
	 */
	public static volatile boolean atLoadedEdge;
	public static final int EDGE_MARGIN = 2, MAX_EDGE_MARGIN = 14;

	private CameraState() {
	}
}
