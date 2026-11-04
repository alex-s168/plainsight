package dev.plainsight.module;

import dev.plainsight.editor.ui.EditorUI;
import dev.plainsight.render.WaypointMarkers;
import imgui.ImDrawList;
import imgui.ImGui;

/** The diamonds of selections marked as waypoints ({@link WaypointMarkers}), in game and in the editor. */
public final class WaypointsModule implements Module {
	private final float[] size = {WaypointMarkers.scale};

	@Override
	public String id() {
		return "waypoints";
	}

	@Override
	public String name() {
		return "Waypoints";
	}

	@Override
	public String description() {
		return "Diamonds at the selections marked as waypoints (the diamond button in the tree), at any distance; "
				+ "their name fades in near the crosshair. Right-click for their size.";
	}

	@Override
	public boolean enabled() {
		return WaypointMarkers.enabled;
	}

	@Override
	public void setEnabled(boolean enabled) {
		WaypointMarkers.enabled = enabled;
	}

	@Override
	public void options() {
		size[0] = WaypointMarkers.scale;
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Diamond size", size, 1f, WaypointMarkers.MAX_SCALE, "%.1fx")) WaypointMarkers.scale = size[0];
		EditorUI.track("waypoints_size");
	}

	@Override
	public void saveSettings(com.google.gson.JsonObject o) {
		o.addProperty("diamondSize", WaypointMarkers.scale);
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		WaypointMarkers.scale = Math.clamp(ModuleConfig.getFloat(o, "diamondSize", WaypointMarkers.scale), 1f, WaypointMarkers.MAX_SCALE);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		// a diamond on a post
		float cx = x + size * 0.5f, cy = y + size * 0.36f, r = size * 0.22f;
		dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, color);
		dl.addLine(cx, cy + r + size * 0.06f, cx, y + size * 0.86f, color, 2f);
	}
}
