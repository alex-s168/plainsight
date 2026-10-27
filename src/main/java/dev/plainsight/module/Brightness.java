package dev.plainsight.module;

import dev.plainsight.editor.Editor;
import imgui.ImDrawList;
import imgui.ImGui;

/**
 * Brighter lighting up to fullbright, separately for the editor and the normal game. Applied to the
 * lightmap (like night vision and gamma), so it is purely visual and client-side.
 */
public final class Brightness implements Module {
	private static Brightness editor, ingame;

	private final boolean forEditor;
	private boolean enabled;
	/** 0 = normal, 1 = fullbright. */
	public float level = 1f;

	public Brightness(boolean forEditor) {
		this.forEditor = forEditor;
		if (forEditor) editor = this;
		else ingame = this;
	}

	/** Brightness to apply this frame (0 = none). */
	public static float current() {
		Brightness b = Editor.isActive() ? editor : ingame;
		return b != null && b.enabled ? Math.max(0, Math.min(1, b.level)) : 0;
	}

	@Override
	public String id() {
		return forEditor ? "editor_brightness" : "ingame_brightness";
	}

	@Override
	public String name() {
		return forEditor ? "Editor Brightness" : "In-game Brightness";
	}

	@Override
	public String description() {
		return (forEditor ? "Brighter lighting while the editor is open" : "Brighter lighting in the normal game (editor closed)")
				+ ", up to fullbright. Right-click for the level.";
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean e) {
		enabled = e;
	}

	@Override
	public void options() {
		float[] v = {level};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Brightness", v, 0.05f, 1f, v[0] >= 0.999f ? "fullbright" : "%.2f")) level = v[0];
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2, cy = y + size / 2;
		dl.addCircleFilled(cx, cy, size * 0.16f, color, 16);
		for (int i = 0; i < 8; i++) {
			double a = Math.PI * 2 * i / 8;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			dl.addLine(cx + c * size * 0.24f, cy + s * size * 0.24f, cx + c * size * 0.36f, cy + s * size * 0.36f, color, 2f);
		}
		if (!forEditor) {
			// small "game" marker: a ground line under the sun
			dl.addLine(x + size * 0.15f, y + size * 0.88f, x + size * 0.85f, y + size * 0.88f, color, 2f);
		}
	}
}
