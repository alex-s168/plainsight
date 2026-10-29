package dev.plainsight.render;

import dev.plainsight.editor.Editor;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.EntityNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Draws the selection tree: confirmed boxes in yellow, boxes selected in the tree in orange, selected
 * entities as cyan boxes. The box being edited is drawn by the box tool.
 */
public final class SelectionRenderer {
	public static final int BOX = 0xFFFFE040, BOX_SELECTED = 0xFFFFA030, ENTITY = 0xFF40E0FF, ENTITY_SELECTED = 0xFFA0F4FF;

	private SelectionRenderer() {
	}

	public static void render(Editor editor, Overlay o) {
		Map<UUID, Entity> entities = entityIndex();
		Set<SelectionNode> selected = editor.tree.selected();
		draw(editor, o, editor.tree.root(), false, selected, entities);
	}

	private static void draw(Editor editor, Overlay o, SelectionNode n, boolean parentSelected, Set<SelectionNode> selected, Map<UUID, Entity> entities) {
		if (!n.visible) return;
		boolean sel = parentSelected || selected.contains(n);
		switch (n) {
			case GroupNode g -> {
				for (SelectionNode c : g.children()) draw(editor, o, c, sel && g != editor.tree.root(), selected, entities);
			}
			case BoxNode b -> {
				if (b == editor.boxTool.editing()) return;
				int c = sel ? BOX_SELECTED : BOX;
				if (sel) o.boxFaces(Overlay.Layer.WORLD, b.box(), Overlay.withAlpha(c, 34));
				o.boxEdges(Overlay.Layer.WORLD, b.box(), c, sel ? 2.5f : 1.75f);
				o.boxEdges(Overlay.Layer.XRAY, b.box(), Overlay.withAlpha(c, sel ? 110 : 60), 1.25f);
			}
			case EntityNode e -> {
				Entity entity = entities.get(e.entity());
				if (entity == null) return;
				AABB a = Editor.renderBox(entity).inflate(0.05);
				int c = sel ? ENTITY_SELECTED : ENTITY;
				o.aabbEdges(Overlay.Layer.WORLD, a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ, c, sel ? 2.5f : 1.75f);
				o.aabbEdges(Overlay.Layer.XRAY, a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ, Overlay.withAlpha(c, 110), 1.25f);
			}
		}
	}

	public static Map<UUID, Entity> entityIndex() {
		Map<UUID, Entity> m = new HashMap<>();
		var level = Minecraft.getInstance().level;
		if (level != null) for (Entity e : level.entitiesForRendering()) m.put(e.getUUID(), e);
		return m;
	}

	/** World bounds {minX, minY, minZ, maxX, maxY, maxZ} of a node, or null if it has nothing loaded. */
	public static double @Nullable [] bounds(Editor editor, SelectionNode node) {
		Map<UUID, Entity> entities = entityIndex();
		double[] acc = null;
		for (SelectionNode leaf : dev.plainsight.editor.selection.SelectionTree.leaves(node)) {
			double[] b = null;
			if (leaf instanceof BoxNode box) b = box.box().bounds();
			else if (leaf instanceof EntityNode en && entities.get(en.entity()) != null) {
				AABB a = Editor.renderBox(entities.get(en.entity()));
				b = new double[]{a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ};
			}
			if (b == null) continue;
			if (acc == null) acc = b.clone();
			else for (int i = 0; i < 3; i++) {
				acc[i] = Math.min(acc[i], b[i]);
				acc[i + 3] = Math.max(acc[i + 3], b[i + 3]);
			}
		}
		return acc;
	}
}
