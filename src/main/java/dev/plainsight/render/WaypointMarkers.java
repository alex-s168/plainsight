package dev.plainsight.render;

import dev.plainsight.editor.Editor;
import dev.plainsight.editor.selection.EntityNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Selections marked as waypoints, shown in the world at any distance: a diamond at the selection's centre
 * (on the edge of the view in its direction when it is off screen), and its name and distance fading in
 * as the crosshair (in the editor: the mouse) comes close to the diamond. Drawn with ImGui over the
 * level, with the camera of the frame just rendered.
 */
public final class WaypointMarkers {
	/** Where the name starts to show, and where it is fully shown (pixels from the diamond). */
	private static final float NAME_FAR = 90, NAME_NEAR = 30;
	/** The module (on by default) and the diamonds' size (1 = the smallest). */
	public static volatile boolean enabled = true;
	public static volatile float scale = 1;
	public static final float MAX_SCALE = 3;
	private static final Matrix4f viewProjection = new Matrix4f(), viewRotation = new Matrix4f();
	private static final Vector3d cameraPos = new Vector3d();
	private static boolean captured;
	/** Markers drawn and the strongest name shown in the last frame (tests). */
	public static volatile int drawn;
	public static volatile float nameAlpha;
	public static volatile String nameShown = "";

	private record Marker(Vector3d at, String name, int color) {
	}

	private WaypointMarkers() {
	}

	/** The camera of the frame being rendered (after the level). */
	public static void capture(CameraRenderState camera) {
		viewProjection.set(camera.projectionMatrix).mul(camera.viewRotationMatrix);
		viewRotation.set(camera.viewRotationMatrix);
		cameraPos.set(camera.pos.x, camera.pos.y, camera.pos.z);
		captured = true;
	}

	/** Where a world point is on screen (window units) with the last frame's camera, or null behind it. */
	public static float @org.jspecify.annotations.Nullable [] project(double x, double y, double z, float screenW, float screenH) {
		if (!captured) return null;
		Vector4f clip = viewProjection.transform(new Vector4f((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z), 1));
		if (clip.w <= 0.05f) return null;
		return new float[]{(clip.x / clip.w * 0.5f + 0.5f) * screenW, (0.5f - clip.y / clip.w * 0.5f) * screenH};
	}

	/** Whether any shown selection is a waypoint. */
	public static boolean any() {
		return enabled && any(Editor.get().tree.root());
	}

	private static boolean any(SelectionNode n) {
		if (!n.visible) return false;
		if (n.waypoint && n != Editor.get().tree.root()) return true;
		if (n instanceof GroupNode g) for (SelectionNode c : g.children()) if (any(c)) return true;
		return false;
	}

	/**
	 * Draws the markers into {@code dl}: {@code x0..y1} is the area they stay in (screen or viewport),
	 * {@code screenW/H} the window's size in ImGui units, {@code focusX/Y} the crosshair or mouse.
	 */
	public static void draw(ImDrawList dl, float x0, float y0, float x1, float y1, float screenW, float screenH, float focusX, float focusY) {
		List<Marker> markers = new ArrayList<>();
		if (enabled) collect(Editor.get(), Editor.get().tree.root(), markers);
		int count = 0;
		float best = 0;
		String bestName = "";
		if (captured) {
			float margin = 10, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
			float hx = (x1 - x0) / 2 - margin, hy = (y1 - y0) / 2 - margin;
			for (Marker m : markers) {
				Vector3d rel = new Vector3d(m.at).sub(cameraPos);
				Vector4f clip = viewProjection.transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1));
				float sx, sy;
				boolean onScreen = false;
				if (clip.w > 0.05f) {
					sx = (clip.x / clip.w * 0.5f + 0.5f) * screenW;
					sy = (0.5f - clip.y / clip.w * 0.5f) * screenH;
					onScreen = Math.abs(sx - cx) <= hx && Math.abs(sy - cy) <= hy;
				} else {
					// behind the camera: towards its side of the view
					Vector4f view = viewRotation.transform(new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1));
					sx = cx + view.x * 1e4f;
					sy = cy - view.y * 1e4f;
					if (view.x == 0 && view.y == 0) sy = cy + 1e4f;
				}
				if (!onScreen) {
					float dx = sx - cx, dy = sy - cy, out = Math.max(Math.abs(dx) / hx, Math.abs(dy) / hy);
					if (out > 0) {
						sx = cx + dx / out;
						sy = cy + dy / out;
					}
				}
				count++;
				float r = (onScreen ? 7 : 5.5f) * scale;
				dl.addQuadFilled(sx, sy - r, sx + r, sy, sx, sy + r, sx - r, sy, m.color);
				dl.addQuad(sx, sy - r, sx + r, sy, sx, sy + r, sx - r, sy, 0xE0000000, 1.5f * (float) Math.sqrt(scale));
				// the name fades in from the edge of a big diamond as from a small one
				float d = Math.max(0, (float) Math.hypot(sx - focusX, sy - focusY) - (r - 7));
				float alpha = onScreen ? Math.max(0, Math.min(1, (NAME_FAR - d) / (NAME_FAR - NAME_NEAR))) : 0;
				if (alpha > 0) {
					String text = m.name + "  " + Math.round(rel.length()) + " m";
					float tw = ImGui.calcTextSizeX(text), th = ImGui.getTextLineHeight();
					int a = Math.round(alpha * 255);
					float tx = sx - tw / 2, ty = sy - r - th - 4;
					dl.addRectFilled(tx - 4, ty - 2, tx + tw + 4, ty + th + 2, (Math.round(alpha * 150) << 24), 3);
					dl.addText(tx, ty, (a << 24) | 0xFFFFFF, text);
					if (alpha > best) {
						best = alpha;
						bestName = m.name;
					}
				}
			}
		}
		drawn = count;
		nameAlpha = best;
		nameShown = bestName;
	}

	/** The shown waypoint selections: their centre, name and colour (ImGui ABGR). */
	private static void collect(Editor editor, SelectionNode n, List<Marker> out) {
		if (!n.visible) return;
		if (n.waypoint && n != editor.tree.root()) {
			double[] b = SelectionRenderer.bounds(editor, n);
			if (b != null) {
				int color = n instanceof EntityNode ? abgr(SelectionRenderer.ENTITY) : abgr(SelectionRenderer.BOX);
				out.add(new Marker(new Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2),
						n instanceof EntityNode ? n.label() : n.name(), color));
			}
		}
		if (n instanceof GroupNode g) for (SelectionNode c : g.children()) collect(editor, c, out);
	}

	private static int abgr(int argb) {
		return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
	}
}
