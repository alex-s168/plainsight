package dev.plainsight.editor.ui;

import dev.plainsight.editor.Editor;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.EntityNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.editor.selection.SelectionTree;
import dev.plainsight.editor.tool.Tool;
import dev.plainsight.module.Module;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The editor's panels: menu bar, tool bar (left), selection tree (right), status bar, viewport buttons. */
public final class EditorUI {
	public static final float LEFT_WIDTH = 300, RIGHT_WIDTH = 320;
	private static final String NODE_PAYLOAD = "PLAINSIGHT_NODE";
	private static final int PANEL_FLAGS = ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse
			| ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoSavedSettings;

	private final Editor editor;
	private @Nullable SelectionNode renaming;
	private boolean renameFocus;
	private int renameFrames;
	private final ImString renameField = new ImString(128);
	/** Pending structural change, applied after the tree has been drawn. */
	private @Nullable Runnable deferred;
	private @Nullable Module popupModule;
	private float menuHeight, statusHeight;
	/** Bottom of the viewport's button bar (window units): the HUD is laid out below it. */
	public float viewportButtonsBottom;
	/** The hints shown in the status bar this frame (for tests). */
	public String statusHintsText = "";
	/** Screen rectangles of the last frame's widgets, for automated tests. */
	public final java.util.Map<String, float[]> widgetRects = new java.util.LinkedHashMap<>();

	public EditorUI(Editor editor) {
		this.editor = editor;
	}

	public void draw(float width, float height) {
		widgetRects.clear();
		menuHeight = ImGui.getFrameHeight();
		statusHeight = ImGui.getFrameHeight() + 4;
		menuBar();
		float top = menuHeight, bottom = height - statusHeight;
		leftPanel(0, top, LEFT_WIDTH, bottom - top);
		rightPanel(width - RIGHT_WIDTH, top, RIGHT_WIDTH, bottom - top);
		statusBar(0, bottom, width, statusHeight);
		editor.viewport.set(LEFT_WIDTH, top, width - LEFT_WIDTH - RIGHT_WIDTH, bottom - top, width, height);
		viewportButtons();
		if (dev.plainsight.compat.Axiom.PRESENT) axiomButton();
		waypointMarkers(width, height);
		shortcuts();
		if (deferred != null) {
			Runnable r = deferred;
			deferred = null;
			r.run();
		}
	}

	private final java.util.Set<String> reportedFailures = new java.util.HashSet<>();

	/**
	 * Runs part of the UI so that an exception does not leave ImGui's window stack unbalanced (which
	 * would abort the game in native code): the caller closes its scope in a finally block.
	 */
	private void guarded(String section, Runnable body) {
		try {
			body.run();
		} catch (RuntimeException e) {
			if (reportedFailures.add(section + e)) dev.plainsight.Plainsight.LOG.error("Editor UI section '{}' failed", section, e);
		}
	}

	/** Records the last ImGui item's rectangle under {@code id} (for tests); usable from tool option panels. */
	public static void track(String id) {
		Editor.get().ui.rect(id);
	}

	private void rect(String id) {
		ImVec2 a = ImGui.getItemRectMin(), b = ImGui.getItemRectMax();
		widgetRects.put(id, new float[]{a.x, a.y, b.x, b.y});
	}

	// -- menu bar ---------------------------------------------------------------------------------------------

	private void menuBar() {
		if (!ImGui.beginMainMenuBar()) return;
		try {
			guarded("menu", this::menus);
		} finally {
			ImGui.endMainMenuBar();
		}
	}

	private void menus() {
		if (ImGui.beginMenu("File")) {
			if (ImGui.menuItem("Save selections")) editor.saveSelections();
			if (ImGui.menuItem("Close editor", "RShift")) deferred = editor::disable;
			ImGui.endMenu();
		}
		if (ImGui.beginMenu("Edit")) {
			boolean any = !editor.tree.selected().isEmpty();
			if (ImGui.menuItem("Group selected", "Ctrl+G", false, any)) groupSelected();
			if (ImGui.menuItem("Duplicate", "Ctrl+D", false, any)) duplicateSelected();
			if (ImGui.menuItem("Delete", "Del", false, any)) deferred = editor::deleteSelected;
			ImGui.endMenu();
		}
		if (ImGui.beginMenu("Help")) {
			helpContents();
			ImGui.endMenu();
		}
	}

	private void helpContents() {
		ImGui.textDisabled("Camera");
		ImGui.bulletText("LMB drag: rotate (look around)");
		ImGui.bulletText("Ctrl + LMB drag: arcball - orbit the block or entity under the cursor");
		ImGui.bulletText("  (an entity pivot moves with the entity; the horizon stays level)");
		ImGui.bulletText("Ctrl + Shift + LMB drag: arcball, the pivot search skips the rock the camera is in");
		ImGui.bulletText("Ctrl + RMB drag: pan   Scroll: move to/from the point under the cursor");
		ImGui.bulletText("Follow an entity: right-click it in the tree; arcball then orbits it");
		ImGui.bulletText("WASD: fly, Space / Shift: up / down, hold Ctrl: faster");
		ImGui.bulletText(dev.plainsight.Plainsight.mapKeyName() + ": map (like the minimap, north up): scroll to zoom, LMB drag to move, Ctrl + LMB drag to turn and tilt; selections move across it");
		ImGui.bulletText("  in the game, " + dev.plainsight.Plainsight.mapKeyName() + " opens the editor on the map");
		ImGui.bulletText("  numpad + / -: zoom; with Ctrl: the shown layer up / down; Ctrl + *: the player's level or the top");
		ImGui.textDisabled("Modules");
		ImGui.bulletText("RMB: use tool   MMB: pick block");
		ImGui.bulletText("Modules (light overlay, brightness, minimap, waypoints, hide seeds): click to switch, right-click for options");
		ImGui.bulletText("Box: LMB on a handle selects it; drag gizmo arrows, squares, rings");
		ImGui.bulletText("Enter, a click on empty space or Done: put the box down");
		ImGui.bulletText("Esc: cancel   Del: delete   Arrows / PgUp / PgDn: nudge");
		ImGui.textDisabled("World view and overlays (buttons at the top of the viewport)");
		ImGui.bulletText("Transparent fluids, see-through to players; right-click for settings");
	}

	// -- left: modules (tools and switchable modules) and options -------------------------------------------------------------------------

	private void leftPanel(float x, float y, float w, float h) {
		ImGui.setNextWindowPos(x, y);
		ImGui.setNextWindowSize(w, h);
		ImGui.begin("##tools", PANEL_FLAGS);
		try {
			guarded("tools", () -> toolsPanel(w));
		} finally {
			ImGui.end();
		}
	}

	private void toolsPanel(float w) {
		ImGui.setNextItemOpen(true, ImGuiCond.FirstUseEver);
		if (ImGui.collapsingHeader("Modules")) {
			float size = 40;
			int perRow = Math.max(1, (int) ((w - 16) / (size + 4)));
			int i = 0;
			for (Tool t : editor.tools) {
				if (i++ % perRow != 0) ImGui.sameLine(0, 4);
				if (iconButton("tool_" + t.id(), size, editor.tool() == t, t::drawIcon)) editor.toggleTool(t);
				tooltip(t.name(), t.description() + (editor.tool() == t ? " Click again to unselect." : ""));
			}
			for (Module m : editor.modules) {
				if (i++ % perRow != 0) ImGui.sameLine(0, 4);
				if (iconButton("module_" + m.id(), size, m.enabled(), m::drawIcon)) m.toggle();
				if (ImGui.isItemClicked(ImGuiMouseButton.Right)) openModulePopup(m);
				tooltip(m.name() + (m.enabled() ? " (on)" : " (off)"), m.description());
			}
		}
		ImGui.setNextItemOpen(true, ImGuiCond.FirstUseEver);
		if (ImGui.collapsingHeader("Tool Options")) {
			ImGui.pushID("options");
			try {
				guarded("tool options", editor.tool()::options);
			} finally {
				ImGui.popID();
			}
		}
		modulePopup();
	}

	interface IconPainter {
		void paint(ImDrawList dl, float x, float y, float size, int color);
	}

	private boolean iconButton(String id, float size, boolean active, IconPainter painter) {
		boolean clicked = ImGui.invisibleButton("##" + id, size, size);
		rect(id);
		boolean hovered = ImGui.isItemHovered();
		ImVec2 a = ImGui.getItemRectMin();
		ImDrawList dl = ImGui.getWindowDrawList();
		int bg = active ? 0xFFB06A33 : hovered ? 0xFF4D4D4D : 0xFF333333; // ABGR
		dl.addRectFilled(a.x, a.y, a.x + size, a.y + size, bg, 3f);
		painter.paint(dl, a.x, a.y, size, active ? 0xFFFFFFFF : 0xFFE0E0E0);
		return clicked;
	}

	/** A small, clickable hint when no level-of-detail mod is installed (Voxy or Distant Horizons). */
	private void lodWarning() {
		if (!dev.plainsight.LodSupport.showWarning()) return;
		ImGui.sameLine(0, 14);
		ImGui.pushStyleColor(ImGuiCol.Button, 0xFF1F4A6B);
		ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0xFF2A6390);
		ImGui.pushStyleColor(ImGuiCol.Text, 0xFF66D9FF);
		boolean open = ImGui.button("! No LOD mod", 0, 30);
		ImGui.popStyleColor(3);
		rect("view_lod_warning");
		if (ImGui.isItemHovered()) tooltip("No LOD mod installed", "The camera only sees the chunks around the player. Click for details.");
		if (open) ImGui.openPopup("##lod_warning");
		if (ImGui.beginPopup("##lod_warning")) {
			try {
				ImGui.text("No level-of-detail mod found");
				ImGui.separator();
				ImGui.pushTextWrapPos(ImGui.getCursorPosX() + 340);
				ImGui.textWrapped("Minecraft only has the chunks around your player, so the editor camera shows nothing beyond "
						+ "your render distance when you fly away from it. That is a limit of the game, not something the editor "
						+ "can work around.");
				ImGui.spacing();
				ImGui.textWrapped("A level-of-detail mod draws the far terrain: install Voxy (needs Sodium) or Distant Horizons.");
				ImGui.popTextWrapPos();
				ImGui.spacing();
				if (ImGui.smallButton("Voxy on Modrinth")) com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create("https://modrinth.com/mod/voxy"));
				ImGui.sameLine();
				if (ImGui.smallButton("Distant Horizons on Modrinth")) com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create("https://modrinth.com/mod/distanthorizons"));
				ImGui.spacing();
				if (ImGui.smallButton("Don't show again")) {
					dev.plainsight.LodSupport.dismiss();
					ImGui.closeCurrentPopup();
				}
				rect("lod_dismiss");
			} finally {
				ImGui.endPopup();
			}
		}
	}

	/** Viewport toggle: icon plus a label, highlighted while on; right-click opens its settings. */
	private void labeledToggle(String id, String label, Module m) {
		float h = 30, w = h + ImGui.calcTextSizeX(label) + 12;
		boolean clicked = ImGui.invisibleButton("##" + id, w, h);
		rect(id);
		boolean hovered = ImGui.isItemHovered();
		ImVec2 a = ImGui.getItemRectMin();
		ImDrawList dl = ImGui.getWindowDrawList();
		int bg = m.enabled() ? 0xFFB06A33 : hovered ? 0xFF4D4D4D : 0xFF333333;
		dl.addRectFilled(a.x, a.y, a.x + w, a.y + h, bg, 3f);
		m.drawIcon(dl, a.x, a.y, h, 0xFFFFFFFF);
		dl.addText(a.x + h + 2, a.y + (h - ImGui.getTextLineHeight()) / 2, 0xFFFFFFFF, label);
		if (clicked) m.toggle();
		if (ImGui.isItemClicked(ImGuiMouseButton.Right)) openModulePopup(m);
		tooltip(m.name() + (m.enabled() ? " (on)" : " (off)"), m.description());
	}

	private static void tooltip(String title, String text) {
		if (ImGui.isItemHovered()) {
			ImGui.beginTooltip();
			ImGui.text(title);
			ImGui.pushTextWrapPos(280);
			ImGui.textDisabled(text);
			ImGui.popTextWrapPos();
			ImGui.endTooltip();
		}
	}

	private void openModulePopup(Module m) {
		popupModule = m;
		ImGui.openPopup("##module_popup");
	}

	private void modulePopup() {
		if (ImGui.beginPopup("##module_popup")) {
			try {
				Module m = popupModule;
				if (m != null) {
					ImGui.text(m.name());
					ImGui.separator();
					guarded("module " + m.id(), m::options);
				}
			} finally {
				ImGui.endPopup();
			}
		}
	}

	/** Waypoint selections in the viewport (under the panels and buttons); the mouse shows their names. */
	private void waypointMarkers(float width, float height) {
		var vp = editor.viewport;
		ImDrawList dl = ImGui.getBackgroundDrawList();
		dl.pushClipRect(vp.x, vp.y, vp.x + vp.width, vp.y + vp.height, true);
		try {
			dev.plainsight.render.WaypointMarkers.draw(dl, vp.x, vp.y, vp.x + vp.width, vp.y + vp.height, width, height,
					(float) editor.mouseX, (float) editor.mouseY);
			if (editor.map.active()) {
				playerArrow(dl, width, height);
				mapStats(dl);
			}
		} finally {
			dl.popClipRect();
		}
	}

	/** The player on the map: an arrow pointing where it looks, as on the minimap. */
	private void playerArrow(ImDrawList dl, float width, float height) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		var p = player.getPosition(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
		float[] s = dev.plainsight.render.WaypointMarkers.project(p.x, p.y, p.z, width, height);
		double a = Math.toRadians(player.getYRot());
		// its facing on screen: where a point a block ahead of it is drawn (the map is tilted)
		float[] ahead = dev.plainsight.render.WaypointMarkers.project(p.x - Math.sin(a), p.y, p.z + Math.cos(a), width, height);
		if (s == null || ahead == null) return;
		float fx = ahead[0] - s[0], fy = ahead[1] - s[1], len = (float) Math.hypot(fx, fy);
		if (len < 1e-3f) return;
		fx /= len;
		fy /= len;
		float r = 9;
		float tx = s[0] + fx * r, ty = s[1] + fy * r;
		float lx = s[0] - fx * r * 0.6f - fy * r * 0.7f, ly = s[1] - fy * r * 0.6f + fx * r * 0.7f;
		float rx = s[0] - fx * r * 0.6f + fy * r * 0.7f, ry = s[1] - fy * r * 0.6f - fx * r * 0.7f;
		dl.addTriangleFilled(tx, ty, lx, ly, rx, ry, 0xFFFFFFFF);
		dl.addTriangle(tx, ty, lx, ly, rx, ry, 0xFF000000, 1.5f);
	}

	/** The map's layer (Y level) and zoom, at the bottom of the viewport like on the minimap. */
	private void mapStats(ImDrawList dl) {
		var vp = editor.viewport;
		String y = dev.plainsight.minimap.Minimap.layerText(), zoom = Math.round(editor.map.viewDistance()) + " m";
		float h = ImGui.getTextLineHeight(), by = vp.y + vp.height - h - 8;
		float zx = vp.x + vp.width - ImGui.calcTextSizeX(zoom) - 10;
		dl.addRectFilled(vp.x + 4, by - 3, vp.x + ImGui.calcTextSizeX(y) + 16, by + h + 3, 0x90000000, 3);
		dl.addText(vp.x + 10, by, 0xE0FFFFFF, y);
		dl.addRectFilled(zx - 6, by - 3, vp.x + vp.width - 4, by + h + 3, 0x90000000, 3);
		dl.addText(zx, by, 0xE0FFFFFF, zoom);
	}

	// -- viewport buttons -------------------------------------------------------------------------------------

	private void viewportButtons() {
		var vp = editor.viewport;
		ImGui.setNextWindowPos(vp.x + 8, vp.y + 8);
		ImGui.setNextWindowBgAlpha(0.55f);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4, 4);
		ImGui.begin("##viewport_buttons", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings
				| ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav);
		ImGui.popStyleVar();
		try {
			guarded("viewport buttons", () -> {
				boolean map = editor.map.active();
				if (map) ImGui.pushStyleColor(ImGuiCol.Button, 0xFFB06A33);
				if (ImGui.button((map ? "Map (" : "3D (") + dev.plainsight.Plainsight.mapKeyName() + ")###mapswitch", 0, 30)) editor.map.toggle(editor);
				if (map) ImGui.popStyleColor();
				rect("view_map");
				tooltip(map ? "Map" : "3D view", "M switches between the 3D view (fly, look, arcball) and the map (tilted like the minimap, north up: "
						+ "scroll to zoom, drag to move; selections are moved across it).");
				ImGui.sameLine(0, 6);
				Module[] buttons = {editor.fluids, editor.seeThrough, editor.blockOverlayDemo, editor.pathDemo};
				String[] labels = {"Fluids", "See-through", "Block overlay", "Path overlay"};
				for (int i = 0; i < buttons.length; i++) {
					Module m = buttons[i];
					if (i > 0) ImGui.sameLine(0, 6);
					labeledToggle("view_" + m.id(), labels[i], m);
				}
				// only once a seed is known (and Voxy can show it)
				if (dev.infiniterra.api.TerrainGuess.current() != null) {
					ImGui.sameLine(0, 6);
					labeledToggle("view_" + editor.guessedTerrain.id(), "Guessed terrain", editor.guessedTerrain);
				}
				if (editor.followed() != null) {
					ImGui.sameLine(0, 14);
					if (ImGui.button("Following " + editor.followName() + "  x", 0, 30)) editor.follow(null);
					rect("view_follow");
					tooltip("Following", "The camera moves with this entity and the arcball orbits it. Click to stop.");
				}
				if (editor.boxTool.editing() != null) {
					ImGui.sameLine(0, 14);
					if (ImGui.button("Done (Enter)", 0, 30)) editor.boxTool.stopEditing();
					rect("view_done");
				}
				lodWarning();
				modulePopup();
			});
			viewportButtonsBottom = ImGui.getWindowPosY() + ImGui.getWindowHeight();
		} finally {
			ImGui.end();
		}
	}

	/** With Axiom installed: switches to Axiom's editor (top right of the viewport). */
	private void axiomButton() {
		var vp = editor.viewport;
		ImGui.setNextWindowPos(vp.x + vp.width - 8, vp.y + 8, ImGuiCond.Always, 1, 0);
		ImGui.setNextWindowBgAlpha(0.55f);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 4, 4);
		ImGui.begin("##axiom", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings
				| ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav);
		ImGui.popStyleVar();
		try {
			guarded("axiom button", () -> {
				// grey like the view buttons when they are off
				long icon = dev.plainsight.compat.Axiom.icon();
				float h = 30, w = (icon >= 0 ? h + 2 : 10) + ImGui.calcTextSizeX("Axiom") + 12;
				boolean clicked = ImGui.invisibleButton("##axiom_switch", w, h);
				ImVec2 a = ImGui.getItemRectMin();
				ImDrawList dl = ImGui.getWindowDrawList();
				dl.addRectFilled(a.x, a.y, a.x + w, a.y + h, ImGui.isItemHovered() ? 0xFF4D4D4D : 0xFF333333, 3f);
				float tx = a.x + 10;
				if (icon >= 0) {
					dl.addImage(icon, a.x + 5, a.y + 4, a.x + h - 3, a.y + h - 4);
					tx = a.x + h + 2;
				}
				dl.addText(tx, a.y + (h - ImGui.getTextLineHeight()) / 2, 0xFFFFFFFF, "Axiom");
				rect("view_axiom");
				tooltip("Switch to Axiom", "Closes this editor and opens Axiom's (its editor key: " + dev.plainsight.compat.Axiom.editorKeyName() + ").");
				if (clicked) deferred = dev.plainsight.compat.Axiom::switchTo;
			});
		} finally {
			ImGui.end();
		}
	}

	// -- right: selection tree --------------------------------------------------------------------------------

	private void rightPanel(float x, float y, float w, float h) {
		ImGui.setNextWindowPos(x, y);
		ImGui.setNextWindowSize(w, h);
		ImGui.begin("##selections", PANEL_FLAGS);
		try {
			guarded("selections", () -> selectionsPanel(h));
		} finally {
			ImGui.end();
		}
	}

	private void selectionsPanel(float h) {
		ImGui.setNextItemOpen(true, ImGuiCond.FirstUseEver);
		if (ImGui.collapsingHeader("Selections")) {
			SelectionTree tree = editor.tree;
			if (ImGui.smallButton("+ Group")) {
				deferred = () -> {
					GroupNode g = tree.add(new GroupNode(), editor.targetGroup(), -1);
					tree.select(g, false);
				};
			}
			rect("tree_new_group");
			ImGui.sameLine();
			boolean any = !tree.selected().isEmpty();
			if (!any) ImGui.beginDisabled();
			if (ImGui.smallButton("Group")) groupSelected();
			rect("tree_group");
			ImGui.sameLine();
			if (ImGui.smallButton("Delete")) deferred = editor::deleteSelected;
			rect("tree_delete");
			if (!any) ImGui.endDisabled();
			ImGui.separator();
			float treeHeight = Math.max(120, h * 0.55f);
			ImGui.beginChild("##tree", 0, treeHeight, false);
			try {
				guarded("tree", () -> treeContents(tree));
			} finally {
				ImGui.endChild();
			}
			guarded("properties", this::properties);
		}
	}

	private void treeContents(SelectionTree tree) {
		{
			for (SelectionNode n : tree.root().children()) node(n);
			// drop below the last node: move to the end of the top level
			ImGui.dummy(ImGui.getContentRegionAvailX(), Math.max(20, ImGui.getContentRegionAvailY()));
			rect("tree_empty");
			if (ImGui.beginDragDropTarget()) {
				SelectionNode dropped = ImGui.acceptDragDropPayload(NODE_PAYLOAD);
				if (dropped != null) deferred = () -> tree.move(dropped, null, -1);
				ImGui.endDragDropTarget();
			}
			if (ImGui.isItemClicked()) {
				tree.clearSelection();
				editor.boxTool.stopEditing();
			}
		}
	}

	private void node(SelectionNode n) {
		SelectionTree tree = editor.tree;
		int flags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.SpanAvailWidth | ImGuiTreeNodeFlags.FramePadding
				| ImGuiTreeNodeFlags.AllowOverlap;
		if (tree.selected().contains(n)) flags |= ImGuiTreeNodeFlags.Selected;
		boolean group = n instanceof GroupNode;
		if (!group) flags |= ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen;
		if (group) ImGui.setNextItemOpen(((GroupNode) n).expanded);
		boolean renamingThis = renaming == n;
		String label = renamingThis ? "" : "     " + n.label();
		boolean shown = shownInWorld(n);
		if (!shown) ImGui.pushStyleColor(ImGuiCol.Text, 0xFF808080);
		boolean open = ImGui.treeNodeEx(n.id().toString(), flags, label);
		if (!shown) ImGui.popStyleColor();
		rect("node:" + (n instanceof EntityNode ? n.label() : n.name()));
		float rowMinX = ImGui.getItemRectMinX(), rowMinY = ImGui.getItemRectMinY();
		float rowMaxX = ImGui.getItemRectMaxX(), rowMaxY = ImGui.getItemRectMaxY();
		if (!renamingThis) drawNodeIcon(n, shown);
		if (group) ((GroupNode) n).expanded = open;
		if (ImGui.isItemClicked(ImGuiMouseButton.Left) && !ImGui.isItemToggledOpen()) {
			boolean ctrl = ImGui.getIO().getKeyCtrl();
			if (!ctrl && n == editor.boxTool.editing()) {
				// clicking the box being moved again puts it down
				editor.boxTool.stopEditing();
			} else if (!ctrl && n == editor.pointTool.editing()) {
				editor.pointTool.stopEditing();
			} else {
				tree.select(n, ctrl);
				if (n instanceof BoxNode b && tree.selected().contains(n)) editor.editBox(b);
				else if (n instanceof dev.plainsight.editor.selection.PointNode p && tree.selected().contains(n)) editor.editPoint(p);
				else if (!ctrl) editor.boxTool.stopEditing();
			}
		}
		if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) startRename(n);
		dragAndDrop(n);
		contextMenu(n);
		// widgets over the row: the eye toggle at the right end, the rename field over the label
		ImVec2 next = ImGui.getCursorScreenPos();
		visibilityButton(n, rowMaxX, rowMinY, rowMaxY - rowMinY);
		waypointButton(n, rowMaxX - 22, rowMinY, rowMaxY - rowMinY);
		if (renamingThis) renameField(n, rowMinX + ImGui.getTreeNodeToLabelSpacing() + 18, rowMinY, rowMaxX - 48);
		ImGui.setCursorScreenPos(next.x, next.y);
		if (group && open) {
			try {
				for (SelectionNode c : ((GroupNode) n).children()) node(c);
			} finally {
				ImGui.treePop();
			}
		}
	}

	/** Whether the node and all its groups are shown in the world. */
	private static boolean shownInWorld(SelectionNode n) {
		for (SelectionNode p = n; p != null; p = p.parent()) if (!p.visible) return false;
		return true;
	}

	/** Eye button at the right end of a tree row: shows/hides the node (groups: with their children). */
	private void visibilityButton(SelectionNode n, float rowMaxX, float rowY, float rowH) {
		float w = 22, x = rowMaxX - w;
		ImGui.setCursorScreenPos(x, rowY);
		ImGui.pushID("vis" + n.id());
		boolean clicked = ImGui.invisibleButton("##vis", w, rowH);
		ImGui.popID();
		rect("vis:" + (n instanceof EntityNode ? n.label() : n.name()));
		boolean hovered = ImGui.isItemHovered();
		ImDrawList dl = ImGui.getWindowDrawList();
		float cx = x + w / 2, cy = rowY + rowH / 2;
		int col = n.visible ? (hovered ? 0xFFFFFFFF : 0xFFC8C8C8) : (hovered ? 0xFFB0B0B0 : 0xFF707070);
		dl.addBezierCubic(cx - 7, cy, cx - 3, cy - 5, cx + 3, cy - 5, cx + 7, cy, col, 1.5f, 10);
		dl.addBezierCubic(cx - 7, cy, cx - 3, cy + 5, cx + 3, cy + 5, cx + 7, cy, col, 1.5f, 10);
		if (n.visible) dl.addCircleFilled(cx, cy, 2.2f, col);
		else dl.addLine(cx - 6, cy + 5, cx + 6, cy - 5, col, 1.5f);
		if (hovered) tooltip(n.visible ? "Hide" : "Show", "Show or hide this selection in the world" + (n instanceof GroupNode ? " (with everything in it)." : "."));
		if (clicked) {
			n.visible = !n.visible;
			editor.tree.touch();
		}
	}

	/** Diamond button left of the eye: shows/hides the node as a waypoint in the world (marker and name at its centre). */
	private void waypointButton(SelectionNode n, float rowMaxX, float rowY, float rowH) {
		float w = 22, x = rowMaxX - w;
		ImGui.setCursorScreenPos(x, rowY);
		ImGui.pushID("wp" + n.id());
		boolean clicked = ImGui.invisibleButton("##wp", w, rowH);
		ImGui.popID();
		rect("wp:" + (n instanceof EntityNode ? n.label() : n.name()));
		boolean hovered = ImGui.isItemHovered();
		ImDrawList dl = ImGui.getWindowDrawList();
		float cx = x + w / 2, cy = rowY + rowH / 2, r = 5;
		if (n.waypoint) {
			dl.addQuadFilled(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, hovered ? 0xFF80F0FF : 0xFF40E0FF);
		} else {
			dl.addQuad(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, hovered ? 0xFFB0B0B0 : 0xFF707070, 1.5f);
		}
		if (hovered) tooltip(n.waypoint ? "Waypoint (on)" : "Waypoint (off)",
				"Show this selection as a waypoint in the world: a marker at its centre, with its name when you look at it.");
		if (clicked) {
			n.waypoint = !n.waypoint;
			editor.tree.touch();
		}
	}

	/** Small vector icon in front of a tree label: folder (group), box outline, person (entity). */
	private void drawNodeIcon(SelectionNode n, boolean shown) {
		ImDrawList dl = ImGui.getWindowDrawList();
		float x = ImGui.getItemRectMinX() + ImGui.getTreeNodeToLabelSpacing() + 2;
		float cy = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2;
		int col = shown ? 0xFFE8E8E8 : 0xFF808080;
		switch (n) {
			case GroupNode g -> {
				int folder = shown ? 0xFF5CC8F0 : 0xFF606060; // ABGR: warm yellow
				dl.addRectFilled(x, cy - 5, x + 6, cy - 3, folder, 1f);
				dl.addRectFilled(x, cy - 3, x + 14, cy + 6, folder, 1.5f);
			}
			case BoxNode b -> {
				int c = b.box().isAxisAligned() ? 0xFF40E0FF : 0xFF40A0FF;
				if (!shown) c = 0xFF606060;
				dl.addRect(x + 1, cy - 5, x + 12, cy + 6, c, 0f, 0, 1.8f);
				dl.addLine(x + 1, cy - 5, x + 4, cy - 8, c, 1.2f);
				dl.addLine(x + 12, cy - 5, x + 15, cy - 8, c, 1.2f);
				dl.addLine(x + 4, cy - 8, x + 15, cy - 8, c, 1.2f);
				dl.addLine(x + 15, cy - 8, x + 15, cy + 3, c, 1.2f);
				dl.addLine(x + 12, cy + 6, x + 15, cy + 3, c, 1.2f);
			}
			case EntityNode e -> {
				int c = shown ? 0xFFFFE040 : 0xFF606060; // ABGR: cyan
				dl.addCircleFilled(x + 7, cy - 3, 3f, c);
				dl.addRectFilled(x + 3, cy + 1, x + 11, cy + 7, c, 2f);
			}
			case dev.plainsight.editor.selection.PointNode p -> {
				int c = shown ? 0xFF40E0FF : 0xFF606060; // ABGR: yellow, as boxes
				dl.addCircle(x + 7, cy, 5f, c, 14, 1.6f);
				dl.addCircleFilled(x + 7, cy, 1.8f, c, 8);
			}
		}
	}

	private void dragAndDrop(SelectionNode n) {
		SelectionTree tree = editor.tree;
		if (ImGui.beginDragDropSource(ImGuiDragDropFlags.None)) {
			ImGui.setDragDropPayload(NODE_PAYLOAD, n);
			ImGui.text(n.label());
			ImGui.endDragDropSource();
		}
		if (ImGui.beginDragDropTarget()) {
			SelectionNode dropped = ImGui.acceptDragDropPayload(NODE_PAYLOAD);
			if (dropped != null && dropped != n) {
				// top quarter: before, bottom quarter: after, middle: into a group (after a leaf)
				float min = ImGui.getItemRectMinY(), max = ImGui.getItemRectMaxY();
				float rel = (ImGui.getMousePosY() - min) / Math.max(1, max - min);
				deferred = () -> {
					if (rel < 0.25f) tree.moveBefore(dropped, n);
					else if (rel > 0.75f || !(n instanceof GroupNode)) tree.moveAfter(dropped, n);
					else tree.move(dropped, (GroupNode) n, -1);
				};
			}
			ImGui.endDragDropTarget();
		}
	}

	private void contextMenu(SelectionNode n) {
		SelectionTree tree = editor.tree;
		if (ImGui.beginPopupContextItem("ctx" + n.id())) {
			try {
				contextMenuItems(n);
			} finally {
				ImGui.endPopup();
			}
		}
	}

	private void contextMenuItems(SelectionNode n) {
		SelectionTree tree = editor.tree;
		{
			if (!tree.selected().contains(n)) tree.select(n, false);
			if (ImGui.menuItem("Rename", "F2")) startRename(n);
			if (ImGui.menuItem("New group inside", "", false, n instanceof GroupNode)) {
				deferred = () -> tree.select(tree.add(new GroupNode(), (GroupNode) n, -1), false);
			}
			if (ImGui.menuItem("Group selected", "Ctrl+G")) groupSelected();
			if (n instanceof GroupNode g && ImGui.menuItem("Ungroup")) deferred = () -> tree.ungroup(g);
			if (ImGui.menuItem("Duplicate", "Ctrl+D")) duplicateSelected();
			if (ImGui.menuItem(n.visible ? "Hide" : "Show")) {
				n.visible = !n.visible;
				tree.touch();
			}
			if (ImGui.menuItem("Waypoint", "", n.waypoint)) {
				n.waypoint = !n.waypoint;
				tree.touch();
			}
			if (ImGui.menuItem("Focus camera")) editor.focus(n);
			if (n instanceof EntityNode en) {
				var mc = Minecraft.getInstance();
				var entity = mc.level == null ? null : mc.level.getEntity(en.entity());
				if (ImGui.menuItem("Follow with camera", "", false, entity != null)) {
					editor.focus(n);
					editor.follow(entity);
				}
			}
			ImGui.separator();
			if (ImGui.menuItem("Delete", "Del")) deferred = editor::deleteSelected;
		}
	}

	public void startRename(SelectionNode n) {
		renaming = n;
		renameFocus = true;
		renameFrames = 0;
		renameField.set(n.customName() != null ? n.customName() : n instanceof EntityNode ? n.label() : n.name());
	}

	/** The rename text field, drawn over the row's label (x..maxX at the row's y). */
	private void renameField(SelectionNode n, float x, float y, float maxX) {
		ImGui.setCursorScreenPos(x, y);
		// focus takes effect within a frame or two; until then an inactive field is not "clicked away"
		boolean starting = renameFrames++ < 3;
		if (renameFocus) {
			ImGui.setKeyboardFocusHere();
			renameFocus = false;
		}
		ImGui.setNextItemWidth(Math.max(60, maxX - x));
		boolean done = ImGui.inputText("##rename", renameField, ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
		rect("rename");
		// Enter or clicking elsewhere commits, Escape cancels
		if (done || (!starting && !ImGui.isItemActive())) {
			if (!ImGui.isKeyPressed(ImGuiKey.Escape)) editor.tree.rename(n, dev.plainsight.imgui.ImText.of(renameField));
			renaming = null;
		}
	}

	private void groupSelected() {
		List<SelectionNode> sel = new ArrayList<>(editor.tree.selected());
		if (sel.isEmpty()) return;
		deferred = () -> {
			GroupNode g = editor.tree.group(sel);
			editor.tree.select(g, false);
		};
	}

	private void duplicateSelected() {
		List<SelectionNode> sel = editor.tree.topLevel(new ArrayList<>(editor.tree.selected()));
		deferred = () -> {
			editor.tree.clearSelection();
			for (SelectionNode n : sel) editor.tree.select(editor.tree.duplicate(n), true);
		};
	}

	/** Shortcuts that act on the tree while the tree panel is focused or the viewport has focus. */
	public void shortcuts() {
		if (ImGui.getIO().getWantTextInput()) return;
		if (ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.G, false)) groupSelected();
		if (ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.D, false)) duplicateSelected();
		if (ImGui.isKeyPressed(ImGuiKey.F2, false) && editor.tree.selected().size() == 1) startRename(editor.tree.selected().iterator().next());
	}

	private void properties() {
		ImGui.setNextItemOpen(true, ImGuiCond.FirstUseEver);
		if (!ImGui.collapsingHeader("Properties")) return;
		var sel = editor.tree.selected();
		if (sel.isEmpty()) {
			ImGui.textDisabled("Nothing selected");
			return;
		}
		if (sel.size() > 1) {
			long blocks = 0;
			for (SelectionNode n : editor.tree.topLevel(new ArrayList<>(sel))) blocks += n.blockCount();
			ImGui.text(sel.size() + " nodes, " + blocks + " blocks");
			copyButtons(new ArrayList<>(sel));
			return;
		}
		SelectionNode n = sel.iterator().next();
		ImGui.text(n.label());
		switch (n) {
			case GroupNode g -> ImGui.textDisabled(g.children().size() + " children, " + g.blockCount() + " blocks");
			case BoxNode b -> {
				var c = b.box().center;
				ImGui.textDisabled(String.format("centre %.2f %.2f %.2f", c.x, c.y, c.z));
				ImGui.textDisabled(String.format("size %.2f x %.2f x %.2f%s", b.box().half.x * 2, b.box().half.y * 2, b.box().half.z * 2,
						b.box().isAxisAligned() ? "" : "  (rotated)"));
			}
			case EntityNode e -> {
				ImGui.textDisabled(e.entity().toString());
				var mc = Minecraft.getInstance();
				boolean loaded = mc.level != null && mc.level.getEntity(e.entity()) != null;
				ImGui.textDisabled(loaded ? "loaded" : "not loaded");
			}
			case dev.plainsight.editor.selection.PointNode p -> ImGui.textDisabled("at " + p.x + " " + p.y + " " + p.z);
		}
		if (ImGui.button("Focus")) editor.focus(n);
		copyButtons(List.of(n));
	}

	/**
	 * Copies the selection as text for chat or commands: the centre block "X Y Z" and, unless only entities
	 * are selected, the blocks covered "min X Y Z, max X Y Z".
	 */
	private void copyButtons(List<SelectionNode> nodes) {
		List<double[]> all = new ArrayList<>();
		boolean onlyEntities = true;
		for (SelectionNode n : nodes) {
			all.add(dev.plainsight.render.SelectionRenderer.bounds(editor, n));
			if (!(n instanceof EntityNode) && !(n instanceof dev.plainsight.editor.selection.PointNode)) onlyEntities = false;
		}
		double[] b = dev.plainsight.editor.selection.SelectionText.union(all);
		if (b == null) ImGui.beginDisabled();
		if (ImGui.button("Copy center coords") && b != null) copy(dev.plainsight.editor.selection.SelectionText.center(b));
		rect("copy_center");
		tooltip("Copy center coords", "The block at the centre as \"X Y Z\", to paste into chat or a command.");
		if (!onlyEntities) {
			ImGui.sameLine();
			if (ImGui.button("Copy specs (text)") && b != null) copy(dev.plainsight.editor.selection.SelectionText.specs(b));
			rect("copy_specs");
			tooltip("Copy specs (text)", "The blocks it covers as \"min X Y Z, max X Y Z\", to paste into chat.");
		}
		if (b == null) ImGui.endDisabled();
	}

	private void copy(String text) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
		editor.setStatus("Copied: " + text);
	}

	// -- status bar -------------------------------------------------------------------------------------------

	private void statusBar(float x, float y, float w, float h) {
		ImGui.setNextWindowPos(x, y);
		ImGui.setNextWindowSize(w, h);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8, 3);
		ImGui.begin("##status", PANEL_FLAGS | ImGuiWindowFlags.NoScrollbar);
		ImGui.popStyleVar();
		try {
			guarded("status", () -> statusContents(w));
		} finally {
			ImGui.end();
		}
	}

	private void statusContents(float w) {
		boolean ctrl = editor.ctrl;
		String[] hints = editor.map.active()
				? new String[]{"LMB - Move Map", "WASD - Move Map", "Scroll - Zoom", "Ctrl + LMB - Turn / Tilt", dev.plainsight.Plainsight.mapKeyName() + " - 3D View", editor.tool().hints()}
				: ctrl
				? new String[]{"Ctrl+LMB - Arcball Camera", "Ctrl+Shift+LMB - Arcball through rock", "Ctrl+RMB - Pan Camera"}
				: new String[]{"LMB - Rotate Camera", "MMB - Pick Block", editor.tool().hints()};
		for (int i = 0; i < hints.length; i++) {
			if (i > 0) {
				ImGui.sameLine(0, 14);
				ImGui.textDisabled("|");
				ImGui.sameLine(0, 14);
			}
			ImGui.text(hints[i]);
		}
		ImGui.sameLine(0, 30);
		if (ctrl) {
			ImGui.textDisabled("arcball pivot: " + (editor.followed() != null ? editor.followName()
					: "the block or entity under the cursor"));
		} else {
			ImGui.textDisabled("Hold Ctrl for more options");
		}
		rect("status_hints");
		statusHintsText = String.join(" | ", hints);
		if (!editor.status.isEmpty()) {
			ImGui.sameLine(0, 40);
			ImGui.textColored(0.55f, 0.8f, 1f, 1f, editor.status);
		}
		float sliderW = 160;
		ImGui.sameLine(w - sliderW - 70);
		ImGui.text("Speed");
		ImGui.sameLine();
		ImGui.setNextItemWidth(sliderW);
		float[] speed = {editor.camera.speed};
		if (ImGui.sliderFloat("##speed", speed, 0.1f, 10f, "%.2f", imgui.flag.ImGuiSliderFlags.Logarithmic)) editor.camera.speed = speed[0];
		rect("speed");
	}

	public float menuHeight() {
		return menuHeight;
	}
}
