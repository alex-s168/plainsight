package dev.plainsight.module;

import dev.plainsight.editor.Editor;
import dev.plainsight.render.Overlay;
import dev.plainsight.render.PathOverlay;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Demo of path overlays: plans a straight walk from the player along the look direction over the
 * terrain, marking blocks it would break (obstacles) and place (gaps), with a goal at the end.
 */
public final class PathOverlayDemo implements Module {
	private final Editor editor;
	public final PathOverlay path = new PathOverlay();
	private boolean enabled;
	public int length = 24;
	public boolean animate = true;

	public PathOverlayDemo(Editor editor) {
		this.editor = editor;
	}

	@Override
	public String id() {
		return "path_overlay_demo";
	}

	@Override
	public String name() {
		return "Path Overlay Demo";
	}

	@Override
	public String description() {
		return "Shows a planned path with goal, blocks to break (red) and to place (blue), as automation will.";
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean e) {
		if (e) plan();
		enabled = e;
		if (e) path.show();
		else path.hide();
	}

	/** Walks the terrain block by block from the player's feet. */
	public void plan() {
		path.clear();
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (mc.player == null || level == null) return;
		Direction dir = mc.player.getDirection();
		BlockPos cur = mc.player.blockPosition();
		path.nodes.add(cur);
		for (int i = 0; i < length; i++) {
			BlockPos next = cur.relative(dir);
			// every few steps drift sideways so the path is not a plain line
			if (i % 7 == 3) next = next.relative(dir.getClockWise());
			BlockPos stand = findStanding(level, next, cur.getY());
			if (stand == null) {
				stand = next.atY(cur.getY());
				if (solid(level, stand) || solid(level, stand.above())) {
					// wall: tunnel through at the current height
					if (solid(level, stand)) path.toBreak.add(stand);
					if (solid(level, stand.above())) path.toBreak.add(stand.above());
				} else {
					// gap: bridge it at the current height
					path.toPlace.add(stand.below());
				}
			}
			path.nodes.add(stand);
			cur = stand;
		}
		path.goal = cur;
	}

	private static boolean solid(ClientLevel level, BlockPos p) {
		return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
	}

	/** A free two-block space with ground below, at most one block up or three down from {@code y}. */
	private static BlockPos findStanding(ClientLevel level, BlockPos p, int y) {
		for (int dy = 1; dy >= -3; dy--) {
			BlockPos q = p.atY(y + dy);
			if (!solid(level, q) && !solid(level, q.above()) && solid(level, q.below())) return q;
		}
		return null;
	}

	public void render(Overlay o) {
		if (!enabled) return;
		if (animate && !path.nodes.isEmpty()) path.progress = (int) ((System.currentTimeMillis() / 250) % path.nodes.size());
	}

	@Override
	public void options() {
		ImGui.text(path.nodes.size() + " nodes, " + path.toBreak.size() + " to break, " + path.toPlace.size() + " to place");
		int[] len = {length};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderInt("Length", len, 4, 64)) length = len[0];
		if (ImGui.button("Re-plan from player")) plan();
		if (ImGui.checkbox("Animate walker", animate)) animate = !animate;
		if (ImGui.checkbox("Show through terrain", path.seeThrough)) path.seeThrough = !path.seeThrough;
	}

	/** A demo walked from the player's feet when switched on: not switched on at game start. */
	@Override
	public boolean persistEnabled() {
		return false;
	}

	@Override
	public void saveSettings(com.google.gson.JsonObject o) {
		o.addProperty("length", length);
		o.addProperty("animate", animate);
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		length = ModuleConfig.getInt(o, "length", length);
		animate = ModuleConfig.getBool(o, "animate", animate);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float[][] pts = {{0.15f, 0.8f}, {0.35f, 0.6f}, {0.55f, 0.65f}, {0.75f, 0.35f}};
		for (int i = 0; i + 1 < pts.length; i++) {
			dl.addLine(x + size * pts[i][0], y + size * pts[i][1], x + size * pts[i + 1][0], y + size * pts[i + 1][1], color, 2.5f);
		}
		for (float[] p : pts) dl.addCircleFilled(x + size * p[0], y + size * p[1], size * 0.06f, color);
		// flag at the goal
		dl.addLine(x + size * 0.75f, y + size * 0.35f, x + size * 0.75f, y + size * 0.1f, color, 2f);
		dl.addTriangleFilled(x + size * 0.75f, y + size * 0.1f, x + size * 0.92f, y + size * 0.17f, x + size * 0.75f, y + size * 0.24f, color);
	}
}
