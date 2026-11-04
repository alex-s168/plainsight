package dev.plainsight.module;

import dev.plainsight.editor.ui.EditorUI;
import dev.plainsight.minimap.Minimap;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImInt;

/** The HUD minimap ({@link Minimap}) as a tool bar toggle, with what it shows as options. */
public final class MinimapModule implements Module {
	private static final String[] LAYERS = {"Top (everything)", "Player's layer", "Fixed Y"};

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
		return "A map of the world around the player in the top right corner of the game (needs Voxy); N switches it, numpad + and - zoom, "
				+ "with Ctrl they move the shown layer up and down (Ctrl + * resets it). "
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
		ImInt layer = new ImInt(Minimap.layer.ordinal());
		ImGui.setNextItemWidth(160);
		if (ImGui.combo("Layer", layer, LAYERS)) {
			if (layer.get() == Minimap.Layer.FIXED.ordinal() && Minimap.layer != Minimap.Layer.FIXED) Minimap.fixedY = Minimap.layerY() == Integer.MAX_VALUE ? Minimap.fixedY : Minimap.layerY();
			Minimap.layer = Minimap.Layer.values()[layer.get()];
		}
		EditorUI.track("minimap_layer");
		if (ImGui.isItemHovered()) ImGui.setTooltip("Player's layer: the terrain above the player is left out (caves, rooms).\n"
				+ "Ctrl + the zoom keys move the layer up and down, Ctrl + * goes back to the player's level or the top.");
		if (Minimap.layer == Minimap.Layer.FIXED) {
			ImInt y = new ImInt(Minimap.fixedY);
			ImGui.setNextItemWidth(160);
			if (ImGui.inputInt("Y", y)) Minimap.fixedY = y.get();
			EditorUI.track("minimap_fixed_y");
		}
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
		o.addProperty("layer", Minimap.layer.name().toLowerCase(java.util.Locale.ROOT));
		o.addProperty("fixedY", Minimap.fixedY);
		o.addProperty("selections", Minimap.showSelections);
		o.addProperty("otherPlayers", Minimap.showPlayers);
		o.addProperty("mobs", Minimap.showMobs);
		o.addProperty("xaeroWaypoints", Minimap.showWaypoints);
		o.addProperty("zoomMetres", Math.round(Minimap.targetDistance));
		o.addProperty("tiltDegrees", Math.round(Minimap.pitch * 10) / 10.0);
		o.addProperty("zoomKeysNumpad", true);
		o.addProperty("editorMapYaw", Math.round(dev.plainsight.editor.MapMode.yaw * 10) / 10.0);
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		String layer = ModuleConfig.getString(o, "layer", Minimap.layer.name().toLowerCase(java.util.Locale.ROOT));
		for (Minimap.Layer l : Minimap.Layer.values()) if (l.name().equalsIgnoreCase(layer)) Minimap.layer = l;
		Minimap.fixedY = ModuleConfig.getInt(o, "fixedY", Minimap.fixedY);
		Minimap.showSelections = ModuleConfig.getBool(o, "selections", Minimap.showSelections);
		Minimap.showPlayers = ModuleConfig.getBool(o, "otherPlayers", Minimap.showPlayers);
		Minimap.showMobs = ModuleConfig.getBool(o, "mobs", Minimap.showMobs);
		Minimap.showWaypoints = ModuleConfig.getBool(o, "xaeroWaypoints", Minimap.showWaypoints);
		Minimap.pitch = Math.clamp(ModuleConfig.getFloat(o, "tiltDegrees", Minimap.pitch), dev.plainsight.editor.MapMode.MIN_PITCH, dev.plainsight.editor.MapMode.MAX_PITCH);
		dev.plainsight.editor.MapMode.yaw = ModuleConfig.getFloat(o, "editorMapYaw", dev.plainsight.editor.MapMode.yaw);
		if (!ModuleConfig.getBool(o, "zoomKeysNumpad", false)) {
			dev.plainsight.Plainsight.moveZoomKeysToNumpad();
			ModuleConfig.markChanged(id());
		}
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
