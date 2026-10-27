package dev.plainsight.editor.tool;

import dev.plainsight.editor.Editor;
import dev.plainsight.editor.selection.EntityNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.render.Overlay;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;

/** Adds entities (players, mobs, items, ...) to the selection tree with a right click. */
public final class EntitySelectTool implements Tool {
	private final Editor editor;
	private @Nullable Entity hovered;
	public boolean playersOnly;

	public EntitySelectTool(Editor editor) {
		this.editor = editor;
	}

	@Override
	public String id() {
		return "entity_select";
	}

	@Override
	public String name() {
		return "Entity Select";
	}

	@Override
	public String description() {
		return "Select entities. RMB on an entity adds it to the selection tree.";
	}

	@Override
	public String hints() {
		return "RMB - Select Entity";
	}

	public @Nullable Entity hovered() {
		return hovered;
	}

	/** The entity under the cursor, unless a block is in front of it. */
	private @Nullable Entity pick() {
		Vector3d dir = editor.mouseRay();
		BlockHitResult block = editor.raycastBlock(dir, false);
		double max = block != null ? block.getLocation().distanceTo(new Vec3(editor.camera.pos.x, editor.camera.pos.y, editor.camera.pos.z)) : Editor.REACH;
		Editor.EntityHit hit = editor.raycastEntity(dir, max);
		if (hit == null) return null;
		if (playersOnly && !(hit.entity() instanceof Player)) return null;
		return hit.entity();
	}

	@Override
	public void mouseMoved(double x, double y) {
		hovered = pick();
	}

	@Override
	public void rightPressed() {
		Entity e = pick();
		if (e == null) return;
		GroupNode target = editor.targetGroup();
		GroupNode parent = target == null ? editor.tree.root() : target;
		for (SelectionNode n : parent.children()) {
			if (n instanceof EntityNode en && en.entity().equals(e.getUUID())) {
				editor.tree.select(n, editor.ctrl);
				return;
			}
		}
		EntityNode node = editor.tree.add(of(e), target, -1);
		editor.tree.select(node, editor.ctrl);
		editor.setStatus("Selected " + node.label());
	}

	public static EntityNode of(Entity e) {
		String type = e instanceof Player ? "player" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
		String name = e instanceof Player p ? p.getGameProfile().name() : e.getName().getString();
		return new EntityNode(e.getUUID(), type, name);
	}

	@Override
	public void render(Overlay o) {
		if (hovered == null || hovered.isRemoved()) return;
		AABB b = hovered.getBoundingBox();
		o.aabbEdges(Overlay.Layer.ON_TOP, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0xFFB0E0FF, 2.5f);
	}

	@Override
	public void options() {
		ImGui.textDisabled("Entity Select");
		ImGui.separator();
		ImGui.textWrapped("RMB on an entity to add it to the selection tree (into the selected group). Ctrl+RMB keeps the tree selection.");
		if (ImGui.checkbox("Players only", playersOnly)) playersOnly = !playersOnly;
		if (hovered != null) ImGui.text("Under cursor: " + of(hovered).label());
	}

	@Override
	public void deactivate() {
		hovered = null;
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2;
		dl.addCircleFilled(cx, y + size * 0.3f, size * 0.13f, color);
		dl.addRectFilled(cx - size * 0.18f, y + size * 0.47f, cx + size * 0.18f, y + size * 0.85f, color, 3f);
		dl.addTriangleFilled(x + size * 0.72f, y + size * 0.55f, x + size * 0.9f, y + size * 0.65f, x + size * 0.78f, y + size * 0.72f, color);
	}
}
