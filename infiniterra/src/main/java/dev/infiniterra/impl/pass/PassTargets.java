package dev.infiniterra.impl.pass;

/**
 * Voxy's render pipeline keeps one set of offscreen targets, resized to whatever viewport it renders; the
 * camera pass has its own set (implemented by mixins on the pipeline), swapped in for its pass so the main
 * view's targets are not reallocated twice a frame.
 */
public interface PassTargets {
	void infiniterra$swapPassTargets();
}
