package dev.plainsight.editor.tool;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plainsight.editor.Editor;
import dev.plainsight.editor.gizmo.TransformGizmo;
import dev.plainsight.editor.math.OrientedBox;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.render.Overlay;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Box selection. RMB-drag from one block to another creates a box covering both; the box then has three
 * handles (the two opposite corners and the centre). LMB on a handle makes it active; the active handle
 * carries a gizmo: corners resize along the box's own axes (the opposite corner stays put), the centre
 * moves the box and rotates it with its rings, so boxes need not stay axis-aligned. Enter confirms.
 * Moves snap to whole blocks (rotation to 15°) unless Shift is held.
 */
public final class BoxSelectTool implements Tool {
	/** The corner where the box was started, the opposite corner where it ended, and the centre. */
	public enum Handle {START, END, CENTER}

	private final Editor editor;
	private @Nullable BoxNode editing;
	private Handle active = Handle.END;
	private @Nullable Handle hoveredHandle;
	public final TransformGizmo gizmo = new TransformGizmo();

	private boolean creating;
	private BlockPos createStart = BlockPos.ZERO, createEnd = BlockPos.ZERO;

	private boolean dragging;
	private final OrientedBox dragStart = new OrientedBox();

	public float snapStep = 1f;
	public float angleStep = 15f;
	private final ImString nameField = new ImString(128);
	private @Nullable BoxNode nameFieldFor;

	public BoxSelectTool(Editor editor) {
		this.editor = editor;
	}

	@Override
	public String id() {
		return "box_select";
	}

	@Override
	public String name() {
		return "Box Select";
	}

	@Override
	public String description() {
		return "Select blocks inside a box. RMB-drag to create; drag the gizmo to move, resize and rotate.";
	}

	@Override
	public String hints() {
		if (dragging) return "Shift - Free (no snapping)";
		if (creating) return "Release RMB - Finish box";
		return editing != null ? "RMB - New Box | LMB on handle - Select handle | Enter - Confirm" : "RMB drag - Select Box";
	}

	public @Nullable BoxNode editing() {
		return editing;
	}

	public Handle activeHandle() {
		return active;
	}

	public @Nullable Handle hoveredHandle() {
		return hoveredHandle;
	}

	public boolean isCreating() {
		return creating;
	}

	@Override
	public boolean isDragging() {
		return dragging;
	}

	public void edit(BoxNode box) {
		editing = box;
		active = Handle.CENTER;
		dragging = false;
		gizmo.endDrag();
	}

	public void stopEditing() {
		BoxNode was = editing;
		editing = null;
		dragging = false;
		creating = false;
		gizmo.endDrag();
		gizmo.hovered = null;
		hoveredHandle = null;
		if (was != null) editor.onBoxPutDown(was);
	}

	// -- geometry -----------------------------------------------------------------------------------------------

	/** Corner bits of a corner handle (see {@link OrientedBox#corner}). */
	public int cornerBits(Handle h) {
		return h == Handle.START ? editing.startCorner : editing.startCorner ^ 7;
	}

	public Vector3d handlePos(Handle h, Vector3d dest) {
		OrientedBox b = editing.box();
		return h == Handle.CENTER ? dest.set(b.center) : b.corner(cornerBits(h), dest);
	}

	public double handleSize(Vector3dc pos) {
		return Math.max(0.2, pos.distance(editor.camera.pos) * 0.022);
	}

	private void updateGizmo() {
		if (editing == null) return;
		gizmo.origin.set(handlePos(active, new Vector3d()));
		gizmo.orientation.set(editing.box().rotation);
		gizmo.rings = active == Handle.CENTER;
		gizmo.planes = true;
		gizmo.update(editor.camera.pos);
	}

	private @Nullable Handle pickHandle(Vector3dc o, Vector3dc d) {
		if (editing == null) return null;
		Handle best = null;
		double bestT = Double.POSITIVE_INFINITY;
		for (Handle h : Handle.values()) {
			Vector3d p = handlePos(h, new Vector3d());
			double s = handleSize(p) * 0.75;
			double t = rayAabb(o, d, p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s);
			if (t >= 0 && t < bestT) {
				bestT = t;
				best = h;
			}
		}
		return best;
	}

	static double rayAabb(Vector3dc o, Vector3dc d, double x1, double y1, double z1, double x2, double y2, double z2) {
		double tMin = 0, tMax = Double.POSITIVE_INFINITY;
		double[] lo = {x1, y1, z1}, hi = {x2, y2, z2}, oo = {o.x(), o.y(), o.z()}, dd = {d.x(), d.y(), d.z()};
		for (int i = 0; i < 3; i++) {
			if (Math.abs(dd[i]) < 1e-12) {
				if (oo[i] < lo[i] || oo[i] > hi[i]) return -1;
			} else {
				double a = (lo[i] - oo[i]) / dd[i], b = (hi[i] - oo[i]) / dd[i];
				tMin = Math.max(tMin, Math.min(a, b));
				tMax = Math.min(tMax, Math.max(a, b));
				if (tMin > tMax) return -1;
			}
		}
		return tMin;
	}

	// -- input --------------------------------------------------------------------------------------------------

	@Override
	public void mouseMoved(double x, double y) {
		Vector3d dir = editor.mouseRay();
		Vector3d o = editor.camera.pos;
		if (creating) {
			BlockHitResult hit = editor.raycastBlock(dir, false);
			if (hit != null) createEnd = hit.getBlockPos();
			return;
		}
		if (editing == null) return;
		if (dragging) {
			applyDrag(o, dir);
			return;
		}
		updateGizmo();
		gizmo.hovered = gizmo.pick(o, dir);
		hoveredHandle = gizmo.hovered == null ? pickHandle(o, dir) : null;
	}

	@Override
	public boolean leftPressed() {
		if (editing == null) return false;
		Vector3d dir = editor.mouseRay();
		Vector3d o = editor.camera.pos;
		updateGizmo();
		TransformGizmo.Part part = gizmo.pick(o, dir);
		if (part != null) {
			dragging = true;
			// Shift held for free moving must not keep the camera descending
			editor.camera.down = false;
			dragStart.set(editing.box());
			gizmo.beginDrag(part, o, dir);
			return true;
		}
		Handle h = pickHandle(o, dir);
		if (h != null) {
			active = h;
			updateGizmo();
			return true;
		}
		return false;
	}

	@Override
	public void rightPressed() {
		BlockHitResult hit = editor.raycastBlock(editor.mouseRay(), false);
		if (hit == null) return;
		creating = true;
		createStart = hit.getBlockPos();
		createEnd = createStart;
	}

	@Override
	public void mouseReleased(int button) {
		if (button == 3 && creating) {
			creating = false;
			OrientedBox box = OrientedBox.ofBlocks(createStart.getX(), createStart.getY(), createStart.getZ(),
					createEnd.getX(), createEnd.getY(), createEnd.getZ());
			BoxNode node = new BoxNode(box);
			node.startCorner = startCorner(createStart, createEnd);
			editor.tree.add(node, editor.targetGroup(), -1);
			editor.tree.select(node, false);
			edit(node);
			// like Axiom: the corner where the drag ended becomes the active handle
			active = Handle.END;
		} else if (button == 1 && dragging) {
			dragging = false;
			gizmo.endDrag();
			editor.tree.touch();
		}
	}

	/** Corner bits of the box corner at the start block: a bit is set where the start lies on the + side. */
	static int startCorner(BlockPos start, BlockPos end) {
		return (start.getX() > end.getX() ? 1 : 0) | (start.getY() > end.getY() ? 2 : 0) | (start.getZ() > end.getZ() ? 4 : 0);
	}

	private void applyDrag(Vector3dc o, Vector3dc dir) {
		OrientedBox box = editing.box();
		TransformGizmo.Part part = gizmo.active;
		if (part == null) return;
		boolean free = editor.shift;
		if (part.isRing()) {
			double angle = gizmo.dragAngle(o, dir);
			if (!free && angleStep > 0) angle = TransformGizmo.snap(angle, Math.toRadians(angleStep));
			Vector3d axis = dragStart.axis(part.axis(), new Vector3d());
			box.set(dragStart);
			box.rotation.set(new Quaterniond().rotateAxis(angle, axis.x, axis.y, axis.z).mul(dragStart.rotation).normalize());
			return;
		}
		Vector3d t = gizmo.dragTranslation(o, dir);
		// snap along the box's own axes so rotated boxes move in whole steps of their grid
		Vector3d world = new Vector3d();
		for (int i = 0; i < 3; i++) {
			Vector3d ax = dragStart.axis(i, new Vector3d());
			double c = t.dot(ax);
			if (!free) c = TransformGizmo.snap(c, snapStep);
			world.fma(c, ax);
		}
		if (active == Handle.CENTER) {
			box.set(dragStart);
			box.center.add(world);
		} else {
			int bits = cornerBits(active);
			Vector3d target = dragStart.corner(bits, new Vector3d()).add(world);
			box.set(dragStart);
			box.moveCorner(bits, target, free || snapStep <= 0 ? 0.05 : snapStep);
		}
	}

	/** Clicking empty space puts the box down (the same as confirming with Enter). */
	@Override
	public void clickedNothing() {
		if (editing != null && !dragging) stopEditing();
	}

	@Override
	public boolean keyPressed(int sc) {
		if (sc == InputConstants.KEY_RETURN || sc == 88) {
			if (editing != null) {
				stopEditing();
				return true;
			}
		}
		if (editing != null && !dragging) {
			// nudge the active handle along the world axes like Axiom's gizmo keys
			Vector3d n = switch (sc) {
				case 82 -> new Vector3d(0, 0, -1); // up arrow: north
				case 81 -> new Vector3d(0, 0, 1);
				case 79 -> new Vector3d(1, 0, 0);
				case 80 -> new Vector3d(-1, 0, 0);
				case 75 -> new Vector3d(0, 1, 0); // page up
				case 78 -> new Vector3d(0, -1, 0);
				default -> null;
			};
			if (n != null) {
				OrientedBox b = editing.box();
				if (active == Handle.CENTER) b.center.add(n);
				else b.moveCorner(cornerBits(active), b.corner(cornerBits(active), new Vector3d()).add(n), Math.max(0.05, snapStep));
				editor.tree.touch();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean cancel() {
		if (creating) {
			creating = false;
			return true;
		}
		if (dragging && editing != null) {
			editing.box().set(dragStart);
			dragging = false;
			gizmo.endDrag();
			return true;
		}
		if (editing != null) {
			stopEditing();
			return true;
		}
		return false;
	}

	/** Switching away puts the edited box down (its handles belong to this tool). */
	@Override
	public void deactivate() {
		creating = false;
		if (dragging) cancel();
		stopEditing();
	}

	// -- rendering ----------------------------------------------------------------------------------------------

	@Override
	public void render(Overlay o) {
		if (creating) {
			OrientedBox preview = OrientedBox.ofBlocks(createStart.getX(), createStart.getY(), createStart.getZ(),
					createEnd.getX(), createEnd.getY(), createEnd.getZ());
			o.boxFaces(Overlay.Layer.WORLD, preview, 0x30FFFFFF);
			o.boxEdges(Overlay.Layer.WORLD, preview, 0xE0FFFFFF, 2f);
			o.boxEdges(Overlay.Layer.XRAY, preview, 0x40FFFFFF, 1.5f);
		}
		if (editing == null) return;
		OrientedBox box = editing.box();
		if (!dragging) o.boxFaces(Overlay.Layer.WORLD, box, 0x26FFFFFF);
		o.boxEdges(Overlay.Layer.WORLD, box, 0xE6FFFFFF, 2f);
		o.boxEdges(Overlay.Layer.XRAY, box, 0x50FFFFFF, 1.5f);
		for (Handle h : Handle.values()) {
			Vector3d p = handlePos(h, new Vector3d());
			double s = handleSize(p);
			int face = h == active ? 0xF0FFFFFF : h == hoveredHandle ? 0xC0B0D0FF : 0x70FFFFFF;
			o.cube(Overlay.Layer.ON_TOP, p, s, face, 0x60000000, 1f);
		}
		updateGizmo();
		gizmo.draw(o);
	}

	// -- tool options -------------------------------------------------------------------------------------------

	@Override
	public void options() {
		ImGui.textDisabled("Box Select");
		ImGui.separator();
		if (editing == null) {
			ImGui.textWrapped("RMB-drag from one block to another to select a box. Click a box in the Selections tree to edit it.");
		} else {
			if (nameFieldFor != editing) {
				nameField.set(editing.customName() == null ? "" : editing.customName());
				nameFieldFor = editing;
			}
			ImGui.setNextItemWidth(-70);
			if (ImGui.inputTextWithHint("Name", editing.name(), nameField)) editor.tree.rename(editing, nameField.get());
			dev.plainsight.editor.ui.EditorUI.track("box_name");
			OrientedBox b = editing.box();
			float[] c = {(float) b.center.x, (float) b.center.y, (float) b.center.z};
			ImGui.setNextItemWidth(-70);
			if (ImGui.dragFloat3("Center", c, 0.25f)) {
				b.center.set(c[0], c[1], c[2]);
				editor.tree.touch();
			}
			float[] s = {(float) (b.half.x * 2), (float) (b.half.y * 2), (float) (b.half.z * 2)};
			ImGui.setNextItemWidth(-70);
			if (ImGui.dragFloat3("Size", s, 0.25f, 0.05f, 100000f)) {
				b.half.set(Math.max(0.025, s[0] / 2), Math.max(0.025, s[1] / 2), Math.max(0.025, s[2] / 2));
				editor.tree.touch();
			}
			Vector3d e = b.rotation.getEulerAnglesYXZ(new Vector3d());
			float[] r = {(float) Math.toDegrees(e.y), (float) Math.toDegrees(e.x), (float) Math.toDegrees(e.z)};
			ImGui.setNextItemWidth(-70);
			if (ImGui.dragFloat3("Rotation", r, 0.5f)) {
				b.rotation.rotationYXZ(Math.toRadians(r[0]), Math.toRadians(r[1]), Math.toRadians(r[2]));
				editor.tree.touch();
			}
			ImGui.text("Handle: " + active.name().toLowerCase() + "   " + editing.label());
			if (ImGui.button("Confirm (Enter)")) stopEditing();
			ImGui.sameLine();
			if (ImGui.button("Reset rotation") && editing != null) {
				editing.box().rotation.identity();
				editor.tree.touch();
			}
			if (editing != null) {
				ImGui.sameLine();
				if (ImGui.button("Delete")) {
					BoxNode n = editing;
					stopEditing();
					editor.tree.remove(n);
				}
			}
		}
		ImGui.spacing();
		ImGui.textDisabled("Snapping");
		ImGui.separator();
		float[] step = {snapStep};
		ImGui.setNextItemWidth(-70);
		if (ImGui.sliderFloat("Move", step, 0f, 4f, step[0] == 0 ? "off" : "%.2f")) snapStep = Math.round(step[0] * 16) / 16f;
		float[] ang = {angleStep};
		ImGui.setNextItemWidth(-70);
		if (ImGui.sliderFloat("Angle", ang, 0f, 90f, ang[0] == 0 ? "off" : "%.0f deg")) angleStep = Math.round(ang[0]);
		ImGui.textDisabled("Hold Shift while dragging to move freely.");
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float p = size * 0.2f, q = size - p;
		int n = 6;
		// dashed rectangle
		for (int i = 0; i < n; i += 2) {
			float a = p + (q - p) * i / n, b = p + (q - p) * (i + 1) / n;
			dl.addLine(x + a, y + p, x + b, y + p, color, 2f);
			dl.addLine(x + a, y + q, x + b, y + q, color, 2f);
			dl.addLine(x + p, y + a, x + p, y + b, color, 2f);
			dl.addLine(x + q, y + a, x + q, y + b, color, 2f);
		}
		dl.addRectFilled(x + q - 4, y + q - 4, x + q + 2, y + q + 2, color);
		dl.addRectFilled(x + p - 3, y + p - 3, x + p + 3, y + p + 3, color);
	}
}
