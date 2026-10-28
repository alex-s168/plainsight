package dev.plainsight.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.editor.tool.BoxSelectTool;
import dev.plainsight.module.Module;
import dev.plainsight.module.SeeThrough;
import dev.plainsight.render.BlockOverlayRenderer;
import dev.plainsight.render.EditorRendering;
import dev.plainsight.render.Overlay;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

import java.util.Map;

/**
 * Read-only state dumps and a few setters for automated tests (called through the dev bridge's
 * {@code /static} endpoint). Not used by the mod itself.
 */
public final class EditorDebug {
	private EditorDebug() {
	}

	public static String dump() {
		Editor e = Editor.get();
		JsonObject o = new JsonObject();
		o.addProperty("active", Editor.isActive());
		o.addProperty("frames", EditorRendering.frames);
		o.addProperty("imgui", dev.plainsight.imgui.ImGuiRenderer.lastFrameInfo);
		JsonObject cam = new JsonObject();
		cam.add("pos", vec(e.camera.pos));
		cam.addProperty("yaw", e.camera.yaw);
		cam.addProperty("pitch", e.camera.pitch);
		cam.addProperty("orbitEntity", e.orbitEntity() == null ? "" : e.orbitEntity().getName().getString());
		cam.addProperty("following", e.followName());
		cam.add("pivot", vec(e.camera.pivot()));
		cam.addProperty("drag", e.camera.drag().name());
		cam.addProperty("speed", e.camera.speed);
		var mc = Minecraft.getInstance();
		var mcCam = mc.gameRenderer.mainCamera();
		cam.add("mcCamera", vec(new Vector3d(mcCam.position().x, mcCam.position().y, mcCam.position().z)));
		cam.addProperty("mcDetached", mcCam.isDetached());
		o.add("camera", cam);
		if (mc.player != null) o.add("player", vec(new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
		JsonArray vp = new JsonArray();
		vp.add(e.viewport.x);
		vp.add(e.viewport.y);
		vp.add(e.viewport.width);
		vp.add(e.viewport.height);
		o.add("viewport", vp);
		o.addProperty("mouse", e.mouseX + "," + e.mouseY);
		o.addProperty("tool", e.tool().id());
		o.addProperty("hints", e.tool().hints());
		o.addProperty("statusHints", e.ui.statusHintsText);
		o.addProperty("ctrl", e.ctrl);
		JsonObject mods = new JsonObject();
		for (Module m : e.viewModules) mods.addProperty(m.id(), m.enabled());
		for (Module m : e.modules) mods.addProperty(m.id(), m.enabled());
		o.add("modules", mods);
		o.addProperty("cutawaySegments", SeeThrough.region().segments().size());
		o.addProperty("cutawayRebuilds", e.seeThrough.rebuilds());
		o.addProperty("overlayBlocksSubmitted", BlockOverlayRenderer.submitted);
		o.addProperty("lightOverlayFaces", e.lightOverlay.markedFaces());
		JsonObject ov = new JsonObject();
		for (Overlay.Layer l : Overlay.Layer.values()) ov.addProperty(l.name(), e.overlay.lineCount(l) + " lines, " + e.overlay.triangleCount(l) + " tris");
		o.add("overlay", ov);
		o.add("tree", tree(e.tree.root()));
		JsonArray sel = new JsonArray();
		for (SelectionNode n : e.tree.selected()) sel.add(n.label());
		o.add("selected", sel);
		BoxSelectTool bt = e.boxTool;
		if (bt.editing() != null) {
			JsonObject b = new JsonObject();
			b.addProperty("label", bt.editing().label());
			b.add("center", vec(bt.editing().box().center));
			b.add("half", vec(bt.editing().box().half));
			b.addProperty("rotation", bt.editing().box().rotation.toString());
			b.addProperty("handle", bt.activeHandle().name());
			b.addProperty("hoveredHandle", String.valueOf(bt.hoveredHandle()));
			b.addProperty("gizmoHovered", String.valueOf(bt.gizmo.hovered));
			b.addProperty("dragging", bt.isDragging());
			o.add("editing", b);
		}
		o.addProperty("creating", bt.isCreating());
		o.addProperty("status", e.status);
		JsonObject rects = new JsonObject();
		for (Map.Entry<String, float[]> r : e.ui.widgetRects.entrySet()) {
			float[] v = r.getValue();
			rects.addProperty(r.getKey(), v[0] + "," + v[1] + "," + v[2] + "," + v[3]);
		}
		o.add("widgets", rects);
		return new GsonBuilder().setPrettyPrinting().create().toJson(o);
	}

	private static JsonArray tree(GroupNode g) {
		JsonArray a = new JsonArray();
		for (SelectionNode c : g.children()) {
			if (c instanceof GroupNode cg) {
				JsonObject o = new JsonObject();
				o.add(cg.label(), tree(cg));
				a.add(o);
			} else {
				a.add(c.label());
			}
		}
		return a;
	}

	private static JsonArray vec(Vector3d v) {
		JsonArray a = new JsonArray();
		a.add(Math.round(v.x * 1000) / 1000.0);
		a.add(Math.round(v.y * 1000) / 1000.0);
		a.add(Math.round(v.z * 1000) / 1000.0);
		return a;
	}

	/** Window pixel position of a world point, or "behind". */
	public static String project(double x, double y, double z) {
		Editor e = Editor.get();
		double[] out = new double[2];
		if (!e.viewport.project(e.camera, new Vector3d(x, y, z), e.fov(), out)) return "behind";
		return Math.round(out[0] * 10) / 10.0 + "," + Math.round(out[1] * 10) / 10.0;
	}

	/** Screen positions of the editing box's handles and of points on the gizmo's arrows and rings. */
	public static String handles() {
		Editor e = Editor.get();
		BoxSelectTool bt = e.boxTool;
		if (bt.editing() == null) return "{}";
		JsonObject o = new JsonObject();
		for (BoxSelectTool.Handle h : BoxSelectTool.Handle.values()) {
			Vector3d p = bt.handlePos(h, new Vector3d());
			o.addProperty(h.name(), project(p.x, p.y, p.z));
		}
		var g = bt.gizmo;
		for (int i = 0; i < 3; i++) {
			Vector3d tip = new Vector3d(g.dir(i)).mul(g.size() * 0.7).add(g.origin);
			o.addProperty("arrow" + "XYZ".charAt(i), project(tip.x, tip.y, tip.z));
		}
		for (int i = 0; i < 3; i++) {
			Vector3d u = g.axis((i + 1) % 3), v = g.axis((i + 2) % 3);
			Vector3d ring = new Vector3d(g.origin).fma(g.size() * 0.85 * Math.cos(0.6), u).fma(g.size() * 0.85 * Math.sin(0.6), v);
			o.addProperty("ring" + "XYZ".charAt(i), project(ring.x, ring.y, ring.z));
		}
		o.addProperty("gizmoSize", g.size());
		return o.toString();
	}

	public static String setCamera(double x, double y, double z, double yaw, double pitch) {
		Editor.get().camera.set(new Vector3d(x, y, z), (float) yaw, (float) pitch);
		return "ok";
	}

	public static String selectTool(String id) {
		Editor e = Editor.get();
		if (id.equals("none")) {
			e.setTool(dev.plainsight.editor.tool.NoTool.INSTANCE);
			return "ok";
		}
		for (var t : e.tools) {
			if (t.id().equals(id)) {
				e.setTool(t);
				return "ok";
			}
		}
		return "no tool " + id;
	}

	public static String setModule(String id, String on) {
		Editor e = Editor.get();
		boolean v = Boolean.parseBoolean(on);
		for (Module m : e.viewModules) if (m.id().equals(id)) m.setEnabled(v);
		for (Module m : e.modules) if (m.id().equals(id)) m.setEnabled(v);
		return "ok";
	}

	/** Which of the blocks in the given column range are cut away by the see-through module. */
	public static String cutColumn(int x, int z, int y1, int y2) {
		StringBuilder sb = new StringBuilder();
		for (int y = y1; y <= y2; y++) sb.append(SeeThrough.region().contains(x, y, z) ? '#' : '.');
		return sb + " segments=" + SeeThrough.region().segments();
	}

	public static String seeThroughOpacity(float o) {
		Editor.get().seeThrough.setOpacity(o);
		return "ok";
	}

	public static String pathInfo() {
		var p = Editor.get().pathDemo.path;
		return "nodes=" + p.nodes.size() + " break=" + p.toBreak.size() + " place=" + p.toPlace.size() + " goal=" + p.goal;
	}

	public static String overlayOpacity(float o) {
		Editor.get().blockOverlayDemo.overlay.opacity = o;
		return "ok";
	}

	/** Follows the first loaded entity whose name matches (or stops with an empty name). */
	public static String follow(String name) {
		Editor e = Editor.get();
		if (name.isEmpty()) {
			e.follow(null);
			return "ok";
		}
		var level = Minecraft.getInstance().level;
		for (var ent : level.entitiesForRendering()) {
			if (ent.getName().getString().equals(name)) {
				e.follow(ent);
				return "ok";
			}
		}
		return "no entity " + name;
	}

	public static String overlaysInGame() {
		Editor e = Editor.get();
		return e.overlaysInGame() + " " + e.overlay.lineCount(dev.plainsight.render.Overlay.Layer.WORLD)
				+ " " + e.overlay.triangleCount(dev.plainsight.render.Overlay.Layer.WORLD);
	}

	public static String spectatorCulling(String on) {
		Editor.spectatorCulling = Boolean.parseBoolean(on);
		return "ok";
	}

	/** Window position of the first point outside solid blocks along the view direction, or "behind". */
	public static String outOfSolidAhead() {
		Editor e = Editor.get();
		Vector3d p = e.outOfSolid(e.camera.pos, e.camera.forward(new Vector3d()));
		return p.x + "," + p.y + "," + p.z;
	}

	public static String lightOverlay(int threshold, int radius) {
		Editor.get().lightOverlay.threshold = threshold;
		Editor.get().lightOverlay.radius = radius;
		return "ok";
	}

	public static String setOverlaysInGame(String on) {
		if (Editor.get().overlaysInGame() != Boolean.parseBoolean(on)) Editor.get().toggleOverlaysInGame();
		return "ok";
	}

	public static String renderDistance(int chunks) {
		Minecraft.getInstance().options.renderDistance().set(chunks);
		return "ok";
	}

	public static String clouds(String on) {
		Minecraft.getInstance().options.cloudStatus().set(Boolean.parseBoolean(on) ? net.minecraft.client.CloudStatus.FANCY : net.minecraft.client.CloudStatus.OFF);
		return "ok";
	}

	public static String brightnessLevel(float editorLevel, float ingameLevel) {
		Editor.get().editorBrightness.level = editorLevel;
		Editor.get().ingameBrightness.level = ingameLevel;
		return "ok";
	}

	public static String unfollow() {
		Editor.get().follow(null);
		return "ok";
	}

	public static String toggle() {
		Editor.get().toggle();
		return String.valueOf(Editor.isActive());
	}

	public static String clearTree() {
		Editor.get().boxTool.stopEditing();
		Editor.get().tree.clear();
		return "ok";
	}
}
