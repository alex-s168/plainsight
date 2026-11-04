package dev.plainsight.minimap;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.infiniterra.api.CameraPass;
import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * A minimap that is Voxy's LOD world seen from above the player, tilted and turned with the player's view
 * (InfiniTerra renders it as a {@link CameraPass}; this class is the pass's camera); drawn with ImGui in the
 * top right corner of the HUD. Only with Voxy, and not in the editor, where the same pass draws the
 * editor's map (M) with the editor camera.
 */
public final class Minimap {
	/** Pitch of the minimap camera (degrees down from the horizon): a tilted top view. */
	/** Degrees down from the horizon: the editor map's tilt (Ctrl + mouse there), 60 by default. */
	public static final float DEFAULT_PITCH = 60;
	public static volatile float pitch = DEFAULT_PITCH;
	/** Vertical field of view of the minimap camera (degrees); narrow, so it looks almost like a map. */
	public static final float FOV = 40;
	public static final double MIN_DISTANCE = 24, MAX_DISTANCE = 6000;

	public static boolean enabled = true;
	/**
	 * The layer shown (the Minimap module; also the editor map): everything from the top, the player's
	 * layer (terrain above the block the player stands in left out), or up to a fixed Y.
	 */
	public enum Layer {TOP, PLAYER, FIXED}

	public static Layer layer = Layer.PLAYER;
	/** Highest Y shown with {@link Layer#FIXED}. */
	public static int fixedY = 64;
	/** Options: the selection tree's shown boxes and entities, other players, mobs. */
	public static boolean showSelections = true, showPlayers = true, showMobs = false;
	/** Options: Xaero's Minimap's waypoints (when it is installed). */
	public static boolean showWaypoints = true;
	/** Waypoints drawn in the last frame (tests). */
	public static volatile int waypointsDrawn;
	/** Camera distance from the player that zooming aims at, and the current (smoothed) one. */
	public static double targetDistance = 160, distance = 160;
	private static float yaw = Float.NaN;
	private static long lastFrame;

	/** The pass's colour as an ImGui texture (registered again when the pass's target changes). */
	private static GpuTextureView registeredView;
	private static long imguiTexture = -1;
	/** This frame's pass is the editor's map (M; full window, the editor camera) rather than the HUD minimap. */
	public static boolean editorMap;
	/** This frame's minimap camera (for drawing the overlays on it). */
	static final Vector3d cameraPos = new Vector3d();
	static final Matrix4f modelView = new Matrix4f();

	/** The camera of InfiniTerra's pass: the HUD minimap's, or the editor map's. */
	private static final CameraPass.Source SOURCE = new CameraPass.Source() {
		@Override
		public boolean wanted() {
			return visible() || editorMapWanted();
		}

		@Override
		public boolean prepare(float partialTick, CameraPass.View view) {
			return Minimap.prepare(partialTick, view);
		}
	};

	/** Gives InfiniTerra the pass's camera (at startup). */
	public static void init() {
		CameraPass.setSource(SOURCE);
	}

	private Minimap() {
	}

	/** The highest Y shown, or Integer.MAX_VALUE for the top (Ctrl + zoom keys change it, Ctrl + * resets it). */
	public static int layerY() {
		var player = Minecraft.getInstance().player;
		return switch (layer) {
			case TOP -> Integer.MAX_VALUE;
			case PLAYER -> player == null ? fixedY : (int) Math.floor(player.getY() + 1e-3);
			case FIXED -> fixedY;
		};
	}

	/** "Y: 64" or "Y: top". */
	public static String layerText() {
		int y = layerY();
		return y == Integer.MAX_VALUE ? "Y: top" : "Y: " + y;
	}

	/** Moves the layer up or down by {@code dy} blocks (from the top: starting at the player's level); above the world: the top. */
	public static void moveLayer(int dy) {
		var level = Minecraft.getInstance().level;
		int from = layer == Layer.TOP ? layerYOfPlayer() : layerY();
		int y = from + dy;
		if (level != null && y > level.getMaxY()) {
			layer = Layer.TOP;
			return;
		}
		fixedY = level != null ? Math.max(level.getMinY(), y) : y;
		layer = Layer.FIXED;
	}

	private static int layerYOfPlayer() {
		var player = Minecraft.getInstance().player;
		return player == null ? fixedY : (int) Math.floor(player.getY() + 1e-3);
	}

	/** Ctrl + *: back to the player's level, or (at the player's level) the top. */
	public static void toggleLayer() {
		layer = layer == Layer.PLAYER ? Layer.TOP : Layer.PLAYER;
	}

	/** Height above which terrain is left out for the current layer (none: MAX_VALUE). */
	private static float cutFor(double playerY) {
		return switch (layer) {
			case TOP -> Float.MAX_VALUE;
			case PLAYER -> (float) Math.floor(playerY + 1e-3) + 1;
			case FIXED -> fixedY + 1;
		};
	}

	public static void zoom(int steps) {
		targetDistance = Math.clamp(targetDistance * Math.pow(1.5, -steps), MIN_DISTANCE, MAX_DISTANCE);
	}

	/** Whether the minimap is shown (and rendered) this frame. */
	public static boolean visible() {
		Minecraft mc = Minecraft.getInstance();
		return enabled && CameraPass.supported() && CameraPass.error().isEmpty() && mc.player != null && mc.level != null
				&& !mc.gui.hud.isHidden() && !Editor.isActive();
	}

	/** Whether the editor shows its map (M) through a minimap pass (needs Voxy). */
	public static boolean editorMapWanted() {
		Minecraft mc = Minecraft.getInstance();
		return Editor.isActive() && Editor.get().map.active() && CameraPass.supported() && CameraPass.error().isEmpty()
				&& mc.player != null && mc.level != null;
	}

	/** Whether an editor map pass is ready to be shown in the viewport (once per pass). */
	public static boolean takeEditorMap() {
		return CameraPass.takeRendered() && editorMap;
	}

	/** Side of the minimap on screen, in GUI pixels (ImGui display units). */
	static float screenSide() {
		var w = Minecraft.getInstance().getWindow();
		return Math.round(Math.min(w.getScreenWidth(), w.getScreenHeight()) * 0.3f);
	}

	/** This frame's camera for the pass; false: no pass. Render thread, from inside Voxy's frame (before the HUD). */
	static boolean prepare(float partialTick, CameraPass.View view) {
		Minecraft mc = Minecraft.getInstance();
		var w = mc.getWindow();
		editorMap = editorMapWanted();
		var at = mc.player.getPosition(partialTick);
		view.maxY = cutFor(at.y);
		if (editorMap) {
			// the editor's map: the frame's own camera and projection (made for the viewport), the whole
			// window's size, so the image lines up with the editor's overlays
			var cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			view.position.set(cam.pos.x, cam.pos.y, cam.pos.z);
			view.viewRotation.set(cam.viewRotationMatrix);
			view.frameProjection = true;
			view.projection.set(cam.projectionMatrix);
			view.width = w.getWidth();
			view.height = w.getHeight();
			return true;
		}
		if (!visible()) return false;
		float scale = w.getWidth() / (float) Math.max(1, w.getScreenWidth());
		int px = Math.max(64, Math.round(screenSide() * scale));
		view.width = view.height = px;

		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
		lastFrame = now;
		// zoom glides; the map turns with the player's view, slightly smoothed
		distance += (targetDistance - distance) * (1 - Math.exp(-dt * 12));
		float playerYaw = mc.player.getYRot(partialTick);
		if (Float.isNaN(yaw)) yaw = playerYaw;
		yaw += (float) (net.minecraft.util.Mth.wrapDegrees(playerYaw - yaw) * (1 - Math.exp(-dt * 10)));

		double pitch = Math.toRadians(Minimap.pitch), yawR = Math.toRadians(yaw);
		// view direction (Minecraft: yaw 0 looks towards +Z); the camera sits behind and above the player
		double dx = -Math.sin(yawR) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yawR) * Math.cos(pitch);
		cameraPos.set(at.x - dx * distance, at.y + 1 - dy * distance, at.z - dz * distance);
		modelView.rotation(new Quaternionf().rotationYXZ((float) Math.PI - (float) yawR, -(float) pitch, 0).conjugate());
		view.position.set(cameraPos);
		view.viewRotation.set(modelView);
		view.fovDegrees = FOV;
		view.near = (float) Math.max(1, distance * 0.05);
		view.far = 48000;
		return true;
	}

	/** Draws the minimap into the current ImGui frame (HUD, top right). */
	public static void draw() {
		// not a stale image when no pass rendered it lately (e.g. a pipeline the minimap cannot render with)
		GpuTextureView colourView = CameraPass.colourView();
		if (!visible() || colourView == null || System.nanoTime() - CameraPass.lastRendered() > 500_000_000L) return;
		if (colourView != registeredView) {
			// a new target (the pass's size changed)
			if (imguiTexture >= 0) ImGuiLayer.get().renderer().releaseTexture(imguiTexture);
			imguiTexture = ImGuiLayer.get().renderer().registerTexture(colourView, true);
			registeredView = colourView;
		}
		Minecraft mc = Minecraft.getInstance();
		float side = screenSide(), margin = 8;
		float x0 = mc.getWindow().getScreenWidth() - side - margin, y0 = margin, x1 = x0 + side, y1 = y0 + side;
		ImDrawList dl = ImGui.getBackgroundDrawList();
		dl.addRectFilled(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xC0202020, 4);
		// GL textures are stored bottom row first
		dl.addImage(imguiTexture, x0, y0, x1, y1, 0, 1, 1, 0);
		dl.addRect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF505050, 4, 0, 2);

		dl.pushClipRect(x0, y0, x1, y1, true);
		try {
			if (showSelections) drawSelections(dl, x0, y0, side);
			if (showPlayers || showMobs) drawEntities(dl, mc, x0, y0, side);
			if (showWaypoints && dev.plainsight.compat.XaeroWaypoints.PRESENT) drawWaypoints(dl, x0, y0, side);
			else waypointsDrawn = 0;
		} finally {
			dl.popClipRect();
		}

		// the player: an arrow at its projected position, pointing where it looks (the map turns with it)
		var p = mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		float[] s = project(p.x, p.y, p.z, x0, y0, side);
		if (s != null) {
			double a = Math.toRadians(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yaw));
			float r = 7;
			float tx = s[0] + (float) Math.sin(a) * r, ty = s[1] - (float) Math.cos(a) * r;
			float lx = s[0] + (float) Math.sin(a + 2.5) * r, ly = s[1] - (float) Math.cos(a + 2.5) * r;
			float rx = s[0] + (float) Math.sin(a - 2.5) * r, ry = s[1] - (float) Math.cos(a - 2.5) * r;
			dl.addTriangleFilled(tx, ty, lx, ly, rx, ry, 0xFFFFFFFF);
			dl.addTriangle(tx, ty, lx, ly, rx, ry, 0xFF000000, 1.5f);
		}
		// north, on the edge of the map in its direction
		float[] n = project(p.x, p.y, p.z - distance, x0, y0, side);
		if (s != null && n != null) {
			float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
			double ang = Math.atan2(n[0] - s[0], -(n[1] - s[1]));
			float ex = cx + (float) Math.sin(ang) * (side / 2 - 10), ey = cy - (float) Math.cos(ang) * (side / 2 - 10);
			dl.addCircleFilled(ex, ey, 8, 0xC0202020);
			dl.addText(ex - 3.5f, ey - 7, 0xFF6060FF, "N");
		}
		String zoom = Math.round(distance) + " m";
		dl.addText(x1 - ImGui.calcTextSizeX(zoom) - 4, y1 - ImGui.getTextLineHeight() - 2, 0xC0FFFFFF, zoom);
		dl.addText(x0 + 4, y1 - ImGui.getTextLineHeight() - 2, 0xC0FFFFFF, layerText());
	}

	/** The selection tree's shown boxes (outlines) and entities (dots), as in the world. */
	private static void drawSelections(ImDrawList dl, float x0, float y0, float side) {
		var tree = Editor.get().tree;
		drawSelection(dl, tree.root(), false, tree.selected(), dev.plainsight.render.SelectionRenderer.entityIndex(), x0, y0, side);
	}

	private static void drawSelection(ImDrawList dl, dev.plainsight.editor.selection.SelectionNode n, boolean parentSelected,
			java.util.Set<dev.plainsight.editor.selection.SelectionNode> selected, java.util.Map<java.util.UUID, net.minecraft.world.entity.Entity> entities,
			float x0, float y0, float side) {
		if (!n.visible) return; // hidden (with everything in it)
		boolean sel = parentSelected || selected.contains(n);
		switch (n) {
			case dev.plainsight.editor.selection.GroupNode g -> {
				for (var c : g.children()) drawSelection(dl, c, sel && g != Editor.get().tree.root(), selected, entities, x0, y0, side);
			}
			case dev.plainsight.editor.selection.BoxNode b -> {
				int c = abgr(sel ? dev.plainsight.render.SelectionRenderer.BOX_SELECTED : dev.plainsight.render.SelectionRenderer.BOX);
				var corners = dev.plainsight.render.Overlay.corners(b.box());
				float[][] p = new float[8][];
				for (int i = 0; i < 8; i++) p[i] = project(corners[i].x, corners[i].y, corners[i].z, x0, y0, side);
				for (int[] e : dev.plainsight.render.Overlay.EDGES)
					if (p[e[0]] != null && p[e[1]] != null) dl.addLine(p[e[0]][0], p[e[0]][1], p[e[1]][0], p[e[1]][1], c, sel ? 2f : 1.5f);
			}
			case dev.plainsight.editor.selection.EntityNode en -> {
				var e = entities.get(en.entity());
				if (e != null) dot(dl, e.position(), abgr(dev.plainsight.render.SelectionRenderer.ENTITY), x0, y0, side, null);
			}
			case dev.plainsight.editor.selection.PointNode p ->
					dot(dl, new net.minecraft.world.phys.Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5),
							abgr(sel ? dev.plainsight.render.SelectionRenderer.BOX_SELECTED : dev.plainsight.render.SelectionRenderer.BOX), x0, y0, side, null);
		}
	}

	/** Other players (with their names) and mobs (hostile red, others green) as dots. */
	private static void drawEntities(ImDrawList dl, Minecraft mc, float x0, float y0, float side) {
		if (mc.level == null) return;
		for (var e : mc.level.entitiesForRendering()) {
			if (e == mc.player || e.isRemoved()) continue;
			if (e instanceof net.minecraft.world.entity.player.Player p) {
				if (showPlayers) dot(dl, e.position(), 0xFFFFC060, x0, y0, side, p.getName().getString());
			} else if (showMobs && e instanceof net.minecraft.world.entity.Mob) {
				dot(dl, e.position(), e instanceof net.minecraft.world.entity.monster.Enemy ? 0xFF3C3CF0 : 0xFF50D050, x0, y0, side, null);
			}
		}
	}

	private static void dot(ImDrawList dl, net.minecraft.world.phys.Vec3 at, int color, float x0, float y0, float side, String label) {
		float[] s = project(at.x, at.y, at.z, x0, y0, side);
		if (s == null) return;
		dl.addCircleFilled(s[0], s[1], 3.5f, color, 12);
		dl.addCircle(s[0], s[1], 3.5f, 0xC0000000, 12, 1f);
		if (label != null) dl.addText(s[0] + 5, s[1] - ImGui.getTextLineHeight() / 2, color, label);
	}

	/**
	 * Xaero's Minimap's waypoints: a diamond in the waypoint's colour with its initials; ones outside the
	 * map sit on its edge in their direction.
	 */
	private static void drawWaypoints(ImDrawList dl, float x0, float y0, float side) {
		float cx = x0 + side / 2, cy = y0 + side / 2, edge = side / 2 - 9;
		int drawn = 0;
		for (var w : dev.plainsight.compat.XaeroWaypoints.current()) {
			float[] s = project(w.x() + 0.5, w.y(), w.z() + 0.5, x0, y0, side);
			float dx, dy;
			if (s != null) {
				dx = s[0] - cx;
				dy = s[1] - cy;
			} else {
				// behind the camera (far behind the player): on the edge in its direction, as the map is turned
				double yr = Math.toRadians(yaw), ox = w.x() + 0.5 - cameraPos.x, oz = w.z() + 0.5 - cameraPos.z;
				double forward = -Math.sin(yr) * ox + Math.cos(yr) * oz, right = -Math.cos(yr) * ox - Math.sin(yr) * oz;
				dx = (float) right;
				dy = (float) -forward;
				if (dx == 0 && dy == 0) continue;
				float big = edge * 1000 / Math.max(Math.abs(dx), Math.abs(dy));
				dx *= big;
				dy *= big;
			}
			drawn++;
			float out = Math.max(Math.abs(dx), Math.abs(dy)) / edge;
			boolean inside = out <= 1;
			if (!inside) {
				dx /= out;
				dy /= out;
			}
			float px = cx + dx, py = cy + dy, r = inside ? 6 : 4.5f;
			int color = 0xFF000000 | (w.rgb() & 0xFF) << 16 | (w.rgb() & 0xFF00) | (w.rgb() >> 16) & 0xFF;
			dl.addQuadFilled(px, py - r, px + r, py, px, py + r, px - r, py, color);
			dl.addQuad(px, py - r, px + r, py, px, py + r, px - r, py, 0xD0000000, 1.2f);
			if (inside) {
				String t = w.initials();
				float tw = ImGui.calcTextSizeX(t);
				dl.addText(px - tw / 2 + 1, py - r - ImGui.getTextLineHeight() + 1, 0xC0000000, t);
				dl.addText(px - tw / 2, py - r - ImGui.getTextLineHeight(), 0xFFFFFFFF, t);
			}
		}
		waypointsDrawn = drawn;
	}

	/** ARGB (the world overlay's colours) to ImGui's ABGR. */
	private static int abgr(int argb) {
		return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
	}

	/** Where a world point is on the minimap (screen coordinates), or null behind the camera. */
	static float[] project(double x, double y, double z, float x0, float y0, float side) {
		Matrix4f mvp = new Matrix4f().setPerspective((float) Math.toRadians(FOV), 1, 1, 1000).mul(modelView);
		Vector4f v = mvp.transform(new Vector4f((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z), 1));
		if (v.w <= 0) return null;
		return new float[]{x0 + (v.x / v.w * 0.5f + 0.5f) * side, y0 + (0.5f - v.y / v.w * 0.5f) * side};
	}
}
