package dev.plainsight.minimap;

/**
 * Voxy's render pipeline keeps one set of offscreen targets, resized to whatever viewport it renders; the
 * minimap has its own set (implemented by mixins on the pipeline), swapped in for its pass so the main
 * view's targets are not reallocated twice a frame.
 */
public interface MinimapTargets {
	void plainsight$swapMinimapTargets();
}
