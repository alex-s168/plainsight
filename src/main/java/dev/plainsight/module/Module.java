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

	/** Whether being on is restored at game start (not for demos placed around the player when switched on). */
	default boolean persistEnabled() {
		return true;
	}

	/** Writes this module's settings (besides being on, see {@link ModuleConfig}) into {@code o}. */
	default void saveSettings(com.google.gson.JsonObject o) {
	}

	/** Reads the settings {@link #saveSettings} wrote; missing or bad values keep their defaults. */
	default void loadSettings(com.google.gson.JsonObject o) {
	}

	void drawIcon(ImDrawList dl, float x, float y, float size, int color);
}
