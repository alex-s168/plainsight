package dev.plainsight.editor.tool;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plainsight.editor.Editor;
import dev.plainsight.editor.gizmo.TransformGizmo;
import dev.plainsight.editor.math.OrientedBox;
import dev.plainsight.editor.selection.PointNode;
import dev.plainsight.render.Overlay;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Point selection: RMB on a block selects that position; the point then carries a gizmo that moves it in
 * whole blocks (in the map only across it). Enter or a click on empty space puts it down.
 */
public final class PointSelectTool implements Tool {
	private final Editor editor;
	private @Nullable PointNode editing;
	public final TransformGizmo gizmo = new TransformGizmo();
	private boolean dragging;
	private int startX, startY, startZ;
	private final ImString nameField = new ImString(128);
	private @Nullable PointNode nameFieldFor;

	public PointSelectTool(Editor editor) {
		this.editor = editor;
		gizmo.rings = false;
	}

	@Override
	public String id() {
		return "point_select";
	}

	@Override
	public String name() {
		return "Point Select";
	}

	@Override
	public String description() {
		return "Select a position: RMB on a block. Drag the gizmo to move it.";
	}

	@Override
	public String hints() {
		if (dragging) return "Moving the point";
		return editing != null ? "RMB - New Point | LMB on gizmo - Move | Enter - Confirm" : "RMB - Select Point";
	}

	public @Nullable PointNode editing() {
		return editing;
	}

	public void edit(PointNode p) {
		editing = p;
		dragging = false;
		gizmo.endDrag();
	}

	public void stopEditing() {
		PointNode was = editing;
		editing = null;
		dragging = false;
		gizmo.endDrag();
		gizmo.hovered = null;
		if (was != null) editor.onPointPutDown(was);
	}

	private Vector3d centre(PointNode p) {
		return new Vector3d(p.x + 0.5, p.y + 0.5, p.z + 0.5);
	}

	private void updateGizmo() {
		if (editing == null) return;
		gizmo.origin.set(centre(editing));
		gizmo.orientation.identity();
		gizmo.planes = true;
		gizmo.allowed = editor.map.active() ? TransformGizmo.MAP_PARTS : TransformGizmo.ALL_PARTS;
		gizmo.update(editor.camera.pos);
	}

	@Override
	public void mouseMoved(double x, double y) {
		if (editing == null) return;
		Vector3d dir = editor.mouseRay();
		Vector3d o = editor.camera.pos;
		if (dragging) {
			Vector3d t = gizmo.dragTranslation(o, dir);
			editing.set(startX + (int) Math.round(t.x), startY + (int) Math.round(t.y), startZ + (int) Math.round(t.z));
			return;
		}
		updateGizmo();
		gizmo.hovered = gizmo.pick(o, dir);
	}

	@Override
	public boolean leftPressed() {
		if (editing == null) return false;
		Vector3d dir = editor.mouseRay();
		updateGizmo();
		TransformGizmo.Part part = gizmo.pick(editor.camera.pos, dir);
		if (part == null) return false;
		dragging = true;
		editor.camera.down = false;
		startX = editing.x;
		startY = editing.y;
		startZ = editing.z;
		gizmo.beginDrag(part, editor.camera.pos, dir);
		return true;
	}

	@Override
	public boolean isDragging() {
		return dragging;
	}

	@Override
	public void rightPressed() {
		BlockHitResult hit = editor.raycastUnderCursor(editor.mouseRay());
		if (hit == null) return;
		var b = hit.getBlockPos();
		PointNode p = editor.tree.add(new PointNode(b.getX(), b.getY(), b.getZ()), editor.targetGroup(), -1);
		editor.tree.select(p, false);
		edit(p);
	}

	@Override
	public void mouseReleased(int button) {
		if (button == 1 && dragging) {
			dragging = false;
			gizmo.endDrag();
			editor.tree.touch();
		}
	}

	@Override
	public void clickedNothing() {
		if (editing != null && !dragging) stopEditing();
	}

	@Override
	public boolean keyPressed(int sc) {
		if (editing == null || dragging) return false;
		if (sc == InputConstants.KEY_RETURN || sc == 88) {
			stopEditing();
			return true;
		}
		int[] d = switch (sc) {
			case 82 -> new int[]{0, 0, -1}; // up arrow: north
			case 81 -> new int[]{0, 0, 1};
			case 79 -> new int[]{1, 0, 0};
			case 80 -> new int[]{-1, 0, 0};
			case 75 -> new int[]{0, 1, 0}; // page up
			case 78 -> new int[]{0, -1, 0};
			default -> null;
		};
		if (d == null) return false;
		editing.set(editing.x + d[0], editing.y + d[1], editing.z + d[2]);
		editor.tree.touch();
		return true;
	}

	@Override
	public boolean cancel() {
		if (dragging && editing != null) {
			editing.set(startX, startY, startZ);
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

	@Override
	public void deactivate() {
		if (dragging) cancel();
		stopEditing();
	}

	@Override
	public void render(Overlay o) {
		if (editing == null) return;
		o.selectionBox(OrientedBox.ofBlocks(editing.x, editing.y, editing.z, editing.x, editing.y, editing.z), 0xE6FFFFFF, 2f, 0x30, 0, 0x60);
		updateGizmo();
		gizmo.draw(o);
	}

	@Override
	public void options() {
		ImGui.textDisabled("Point Select");
		ImGui.separator();
		if (editing == null) {
			ImGui.textWrapped("RMB on a block to select its position. Click a point in the Selections tree to edit it.");
			return;
		}
		if (nameFieldFor != editing) {
			nameField.set(editing.customName() == null ? "" : editing.customName());
			nameFieldFor = editing;
		}
		ImGui.setNextItemWidth(-70);
		if (ImGui.inputTextWithHint("Name", editing.name(), nameField)) editor.tree.rename(editing, dev.plainsight.imgui.ImText.of(nameField));
		dev.plainsight.editor.ui.EditorUI.track("point_name");
		int[] p = {editing.x, editing.y, editing.z};
		ImGui.setNextItemWidth(-70);
		if (ImGui.dragInt3("Position", p, 0.2f)) {
			editing.set(p[0], p[1], p[2]);
			editor.tree.touch();
		}
		if (ImGui.button("Confirm (Enter)")) stopEditing();
		if (editing != null) {
			ImGui.sameLine();
			if (ImGui.button("Delete")) {
				PointNode n = editing;
				stopEditing();
				editor.tree.remove(n);
			}
		}
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2, cy = y + size / 2, r = size * 0.3f;
		dl.addCircle(cx, cy, r, color, 20, 2f);
		dl.addLine(cx - r * 1.3f, cy, cx - r * 0.45f, cy, color, 2f);
		dl.addLine(cx + r * 0.45f, cy, cx + r * 1.3f, cy, color, 2f);
		dl.addLine(cx, cy - r * 1.3f, cx, cy - r * 0.45f, color, 2f);
		dl.addLine(cx, cy + r * 0.45f, cx, cy + r * 1.3f, color, 2f);
		dl.addCircleFilled(cx, cy, size * 0.06f, color, 10);
	}

	/** The gizmo's centre for the camera (tests). */
	public @Nullable Vector3dc gizmoOrigin() {
		return editing == null ? null : gizmo.origin;
	}
}
