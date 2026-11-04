package dev.plainsight.module;

import dev.plainsight.editor.ui.EditorUI;
import dev.plainsight.minimap.Minimap;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImInt;

/** The HUD minimap ({@link Minimap}) as a tool bar toggle, with what it shows as options. */
public final class MinimapModule implements Module {
	private static final String[] LAYERS = {"Top (everything)", "Player's layer"};

	@Override
	public String id() {
		return "minimap";
	}

	@Override
	public String name() {
		return "Minimap";
	}

	@Override
	public String description() {
		return "A map of the world around the player in the top right corner of the game (needs Voxy); N switches it, = and - zoom. "
				+ "Right-click for what it shows.";
	}

	@Override
	public boolean enabled() {
		return Minimap.enabled;
	}

	@Override
	public void setEnabled(boolean enabled) {
		Minimap.enabled = enabled;
	}

	@Override
	public void options() {
		ImInt layer = new ImInt(Minimap.playerLayer ? 1 : 0);
		ImGui.setNextItemWidth(160);
		if (ImGui.combo("Layer", layer, LAYERS)) Minimap.playerLayer = layer.get() == 1;
		EditorUI.track("minimap_layer");
		if (ImGui.isItemHovered()) ImGui.setTooltip("Player's layer: the terrain above the player is left out (caves, rooms)");
		if (ImGui.checkbox("Selections", Minimap.showSelections)) Minimap.showSelections = !Minimap.showSelections;
		EditorUI.track("minimap_selections");
		if (ImGui.isItemHovered()) ImGui.setTooltip("The boxes and entities of the selection tree (not the hidden ones)");
		if (ImGui.checkbox("Other players", Minimap.showPlayers)) Minimap.showPlayers = !Minimap.showPlayers;
		EditorUI.track("minimap_players");
		if (ImGui.checkbox("Mobs", Minimap.showMobs)) Minimap.showMobs = !Minimap.showMobs;
		EditorUI.track("minimap_mobs");
		if (ImGui.isItemHovered()) ImGui.setTooltip("Hostile mobs red, others green");
		if (dev.plainsight.compat.XaeroWaypoints.PRESENT) {
			if (ImGui.checkbox("Xaero waypoints", Minimap.showWaypoints)) Minimap.showWaypoints = !Minimap.showWaypoints;
			EditorUI.track("minimap_waypoints");
			if (ImGui.isItemHovered()) ImGui.setTooltip("Xaero's Minimap's waypoints (on the edge when outside the map)");
		}
	}

	@Override
	public void saveSettings(com.google.gson.JsonObject o) {
		o.addProperty("layer", Minimap.playerLayer ? "player" : "top");
		o.addProperty("selections", Minimap.showSelections);
		o.addProperty("otherPlayers", Minimap.showPlayers);
		o.addProperty("mobs", Minimap.showMobs);
		o.addProperty("xaeroWaypoints", Minimap.showWaypoints);
		o.addProperty("zoomMetres", Math.round(Minimap.targetDistance));
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		Minimap.playerLayer = !"top".equals(ModuleConfig.getString(o, "layer", Minimap.playerLayer ? "player" : "top"));
		Minimap.showSelections = ModuleConfig.getBool(o, "selections", Minimap.showSelections);
		Minimap.showPlayers = ModuleConfig.getBool(o, "otherPlayers", Minimap.showPlayers);
		Minimap.showMobs = ModuleConfig.getBool(o, "mobs", Minimap.showMobs);
		Minimap.showWaypoints = ModuleConfig.getBool(o, "xaeroWaypoints", Minimap.showWaypoints);
		Minimap.targetDistance = Minimap.distance = Math.clamp(ModuleConfig.getFloat(o, "zoomMetres", (float) Minimap.targetDistance),
				Minimap.MIN_DISTANCE, Minimap.MAX_DISTANCE);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		// a map: a frame with the player's arrow in it
		dl.addRect(x + size * 0.16f, y + size * 0.16f, x + size * 0.84f, y + size * 0.84f, color, 3f, 0, 2f);
		float cx = x + size / 2, cy = y + size / 2;
		dl.addTriangleFilled(cx, cy - size * 0.18f, cx - size * 0.12f, cy + size * 0.14f, cx + size * 0.12f, cy + size * 0.14f, color);
	}
}
