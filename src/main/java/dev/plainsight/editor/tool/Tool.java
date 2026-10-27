package dev.plainsight.editor.tool;

import dev.plainsight.render.Overlay;
import imgui.ImDrawList;

/** A tool of the editor's tool bar: owns RMB ("use tool") and may claim LMB presses (gizmos, handles). */
public interface Tool {
	String id();

	String name();

	String description();

	/** Draws the tool bar icon into the square (x, y)-(x + size, y + size). */
	void drawIcon(ImDrawList dl, float x, float y, float size, int color);

	/** The "Tool Options" panel. */
	default void options() {
	}

	/** Mouse hints for the status bar, e.g. "RMB - Select box". */
	default String hints() {
		return "RMB - Use Tool";
	}

	/** LMB pressed in the viewport; return true to keep the press from rotating the camera. */
	default boolean leftPressed() {
		return false;
	}

	default void rightPressed() {
	}

	/** Whether a gizmo or handle drag is in progress (modifier keys then belong to the tool). */
	default boolean isDragging() {
		return false;
	}

	/** LMB clicked (pressed and released without dragging) where the tool had nothing to grab. */
	default void clickedNothing() {
	}

	default void mouseReleased(int button) {
	}

	default void mouseMoved(double x, double y) {
	}

	/** A key press while the viewport has focus; return true when consumed. */
	default boolean keyPressed(int scancode) {
		return false;
	}

	/** Escape: abort what is in progress; return true when there was something to abort. */
	default boolean cancel() {
		return false;
	}

	default void render(Overlay overlay) {
	}

	default void deactivate() {
	}
}
