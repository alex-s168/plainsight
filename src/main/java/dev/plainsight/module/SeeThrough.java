package dev.plainsight.module;

import dev.plainsight.editor.Editor;
import dev.plainsight.render.AlphaOverride;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RTS-style cutaway: blocks between the editor camera and players are drawn faintly (or hidden), so
 * players stay visible behind walls and roofs. Cut blocks are re-meshed into the translucent layer when
 * the camera or a player moves.
 */
public final class SeeThrough implements Module {
	private static volatile CutawayRegion region = CutawayRegion.EMPTY;
	private static volatile float opacity = 0.15f;

	private final Editor editor;
	private boolean enabled;
	/** Radius of the cut around players and at the camera. */
	public float radius = 4.5f, cameraRadius = 1.5f;
	public boolean allPlayers = true;
	private long lastUpdate;
	private int rebuilds;

	public SeeThrough(Editor editor) {
		this.editor = editor;
	}

	/** Read by the chunk compiler threads. */
	public static CutawayRegion region() {
		return region;
	}

	public static float opacity() {
		return opacity;
	}

	public void setOpacity(float o) {
		opacity = Math.max(0, Math.min(1, o));
		apply(compute());
		// force a re-mesh even if the region did not move
		CutawayRegion r = region;
		region = CutawayRegion.EMPTY;
		apply(r);
	}

	public int rebuilds() {
		return rebuilds;
	}

	@Override
	public String id() {
		return "see_through";
	}

	@Override
	public String name() {
		return "See-Through to Players";
	}

	@Override
	public String description() {
		return "Make blocks between the camera and players transparent (RTS-style). Right-click for settings.";
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean e) {
		enabled = e;
		if (!e) apply(CutawayRegion.EMPTY);
	}

	/** Recomputes the region every frame; re-meshes only when something moved noticeably. */
	public void frame() {
		if (!enabled || !Editor.isActive()) {
			if (!region.isEmpty()) apply(CutawayRegion.EMPTY);
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastUpdate < 80) return;
		CutawayRegion next = compute();
		if (next.differs(region, 0.45)) {
			lastUpdate = now;
			apply(next);
		}
	}

	private CutawayRegion compute() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return CutawayRegion.EMPTY;
		List<CutawayRegion.Segment> segs = new ArrayList<>();
		double cx = editor.camera.pos.x, cy = editor.camera.pos.y, cz = editor.camera.pos.z;
		List<Player> targets = new ArrayList<>();
		targets.add(mc.player);
		if (allPlayers) {
			for (AbstractClientPlayer p : mc.level.players()) {
				if (p != mc.player && p.distanceToSqr(cx, cy, cz) < 128 * 128) targets.add(p);
			}
		}
		for (Player p : targets) {
			double tx = p.getX(), ty = p.getY() + p.getBbHeight() * 0.55, tz = p.getZ();
			double d = Math.sqrt((tx - cx) * (tx - cx) + (ty - cy) * (ty - cy) + (tz - cz) * (tz - cz));
			if (d < 1.5) continue;
			segs.add(new CutawayRegion.Segment(cx, cy, cz, tx, ty, tz, p.getY()));
		}
		return new CutawayRegion(segs, cameraRadius, radius, openingLight(mc.level, cx, cy, cz, targets));
	}

	/** Minimum sky light of the opened space, so it never renders pitch black (e.g. seen from a cave). */
	static final int MIN_OPENING_LIGHT = 10;

	/**
	 * Sky light for the opened space: the brightest of what the camera sees around itself and the open
	 * sky above the players (the cut opens their room towards it), but at least {@link #MIN_OPENING_LIGHT}.
	 */
	private static int openingLight(net.minecraft.client.multiplayer.ClientLevel level, double cx, double cy, double cz, List<Player> targets) {
		var sky = net.minecraft.world.level.LightLayer.SKY;
		int light = level.getBrightness(sky, net.minecraft.core.BlockPos.containing(cx, cy, cz));
		for (Player p : targets) {
			var above = p.blockPosition().above(2);
			for (int i = 0; i < 24 && light < 15; i++, above = above.above()) {
				if (level.getBlockState(above).isAir()) light = Math.max(light, level.getBrightness(sky, above));
			}
		}
		return Math.max(MIN_OPENING_LIGHT, light);
	}

	private void apply(CutawayRegion next) {
		CutawayRegion old = region;
		if (old.isEmpty() && next.isEmpty()) return;
		Set<List<Integer>> dirty = new HashSet<>(old.sections());
		dirty.addAll(next.sections());
		region = next;
		AlphaOverride.cutActive = !next.isEmpty();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (List<Integer> s : dirty) mc.levelExtractor.setSectionDirty(s.get(0), s.get(1), s.get(2));
		rebuilds++;
	}

	@Override
	public void options() {
		float[] r = {radius};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Radius around players", r, 1f, 12f, "%.1f")) radius = r[0];
		float[] rc = {cameraRadius};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Radius at camera", rc, 0.5f, 6f, "%.1f")) cameraRadius = rc[0];
		float[] o = {opacity};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Opacity", o, 0f, 0.8f, "%.2f")) opacity = o[0];
		if (ImGui.isItemDeactivatedAfterEdit()) apply(compute());
		if (ImGui.checkbox("Other players too", allPlayers)) allPlayers = !allPlayers;
	}

	@Override
	public void saveSettings(com.google.gson.JsonObject o) {
		o.addProperty("radius", radius);
		o.addProperty("cameraRadius", cameraRadius);
		o.addProperty("opacity", opacity);
		o.addProperty("otherPlayers", allPlayers);
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		radius = ModuleConfig.getFloat(o, "radius", radius);
		cameraRadius = ModuleConfig.getFloat(o, "cameraRadius", cameraRadius);
		opacity = Math.max(0, Math.min(1, ModuleConfig.getFloat(o, "opacity", opacity)));
		allPlayers = ModuleConfig.getBool(o, "otherPlayers", allPlayers);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2, cy = y + size / 2;
		// an eye
		dl.addBezierCubic(x + size * 0.12f, cy, x + size * 0.35f, y + size * 0.18f, x + size * 0.65f, y + size * 0.18f, x + size * 0.88f, cy, color, 2f, 16);
		dl.addBezierCubic(x + size * 0.12f, cy, x + size * 0.35f, y + size * 0.82f, x + size * 0.65f, y + size * 0.82f, x + size * 0.88f, cy, color, 2f, 16);
		dl.addCircleFilled(cx, cy, size * 0.12f, color, 16);
	}
}
