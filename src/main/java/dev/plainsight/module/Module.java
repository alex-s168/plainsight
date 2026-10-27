package dev.plainsight.module;

import imgui.ImDrawList;

/**
 * A toggleable feature with optional settings (shown in a popup). Modules that act on the game by
 * themselves are meant to be switchable off in one place; editor-only modules just change what is drawn.
 */
public interface Module {
	String id();

	String name();

	String description();

	boolean enabled();

	void setEnabled(boolean enabled);

	default void toggle() {
		setEnabled(!enabled());
	}

	/** Settings popup contents. */
	default void options() {
	}

	void drawIcon(ImDrawList dl, float x, float y, float size, int color);
}
