package dev.plainsight.editor.ui;

import dev.plainsight.editor.Editor;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.DimensionTrees;
import dev.plainsight.editor.selection.EntityNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.editor.selection.SelectionTree;
import dev.plainsight.editor.selection.TreeSearch;
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
import imgui.flag.ImGuiTabItemFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** The editor's panels: menu bar, tool bar (left), selection tree (right), status bar, viewport buttons. */
public final class EditorUI {
	public static final float DEFAULT_LEFT_WIDTH = 300, DEFAULT_RIGHT_WIDTH = 320;
	/** Width of the grab area on a panel's inner edge (half over the panel, half over the viewport). */
	private static final float SPLITTER_WIDTH = 8;
	private static final String NODE_PAYLOAD = "PLAINSIGHT_NODE";
	private static final int PANEL_FLAGS = ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse
			| ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoSavedSettings;

	/** Tree rows: frame padding (moves the arrows), icon width and the gap to the text, in pixels. */
	private static final float TREE_FRAME_PAD_X = 2, ICON_WIDTH = 16, ICON_GAP = 7;
	/** Width of the waypoint and eye buttons at a row's right end, plus a little space before them. */
	private static final float ROW_BUTTONS_WIDTH = 48;
	/** Width of the gradient that fades out a clipped row text. */
	private static final float FADE_WIDTH = 24;
	private static final int TEXT_COLOR = 0xFFEBEBEB, DETAIL_COLOR = 0xFF9A9A9A; // ABGR
	private static final int HIDDEN_TEXT_COLOR = 0xFF808080, HIDDEN_DETAIL_COLOR = 0xFF626262;

	private final Editor editor;
	/** Widths of the side panels (dragged at their inner edges, saved in the editor config); NaN until read. */
	private float leftWidth = Float.NaN, rightWidth = Float.NaN;
	/** The splitter being dragged: 0 none, 1 left, 2 right; and where it was grabbed (mouse x minus the edge's). */
	private int dragging;
	private float dragOffset;
	private final ImString searchField = new ImString(128);
	/** This frame's tree search, or null while the search field is empty. */
	private @Nullable TreeSearch search;
	private String searchQuery = "";
	/** Groups opened or closed by the user during the current search (their own state stays as it was). */
	private final java.util.Map<java.util.UUID, Boolean> searchOpen = new java.util.HashMap<>();
	/** Dimension of the tree view's tab ("" until the panel is drawn: the player's). */
	private String viewDimension = "", lastDimension = "";
	/** Selects {@link #viewDimension}'s tab in the next frame (after entering a dimension). */
	private boolean selectTab;
	/** The position lines of the selected node's properties, last drawn (for tests). */
	public String positionText = "";
	/** Title of the tree row whose full text is shown in a tooltip this frame (for tests), or "". */
	public String nodeTooltip = "";
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
		nodeTooltip = "";
		menuHeight = ImGui.getFrameHeight();
		statusHeight = ImGui.getFrameHeight() + 4;
		menuBar();
		float top = menuHeight, bottom = height - statusHeight;
		panelWidths(width);
		leftPanel(0, top, leftWidth, bottom - top);
		rightPanel(width - rightWidth, top, rightWidth, bottom - top);
		statusBar(0, bottom, width, statusHeight);
		editor.viewport.set(leftWidth, top, width - leftWidth - rightWidth, bottom - top, width, height);
		splitter(1, leftWidth, top, bottom - top);
		splitter(2, width - rightWidth, top, bottom - top);
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

	public float leftWidth() {
		return leftWidth;
	}

	public float rightWidth() {
		return rightWidth;
	}

	/**
	 * This frame's panel widths, before anything is laid out with them (so the panels and the viewport move
	 * together): read from the config, following a dragged splitter, fitted to the window.
	 */
	private void panelWidths(float width) {
		if (Float.isNaN(leftWidth)) {
			leftWidth = dev.plainsight.editor.EditorConfig.getFloat("leftPanelWidth", DEFAULT_LEFT_WIDTH);
			rightWidth = dev.plainsight.editor.EditorConfig.getFloat("rightPanelWidth", DEFAULT_RIGHT_WIDTH);
		}
		if (dragging != 0) {
			float edge = ImGui.getMousePosX() - dragOffset;
			if (dragging == 1) leftWidth = PanelWidths.dragged(edge, rightWidth, width);
			else rightWidth = PanelWidths.dragged(width - edge, leftWidth, width);
			if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
				dragging = 0;
				dev.plainsight.editor.EditorConfig.setFloat("leftPanelWidth", leftWidth);
				dev.plainsight.editor.EditorConfig.setFloat("rightPanelWidth", rightWidth);
			}
		}
		float[] fit = PanelWidths.fit(leftWidth, rightWidth, width);
		leftWidth = fit[0];
		rightWidth = fit[1];
	}

	/** Sets the panel widths (tests); they are fitted to the window in the next frame. Not saved. */
	public void setPanelWidths(float left, float right) {
		leftWidth = left;
		rightWidth = right;
	}

	/**
	 * The grab area on a panel's inner edge at {@code x}: a small window over the panels, so it gets the
	 * mouse before them (not NoBringToFrontOnFocus: ImGui creates such windows behind the others); a drag
	 * changes the width from the next frame's {@link #panelWidths} on.
	 */
	private void splitter(int which, float x, float top, float h) {
		float x0 = x - SPLITTER_WIDTH / 2;
		ImGui.setNextWindowPos(x0, top);
		ImGui.setNextWindowSize(SPLITTER_WIDTH, h);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 1, 1);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
		ImGui.begin("##splitter" + which, ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoSavedSettings
				| ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoNav | ImGuiWindowFlags.NoMove);
		ImGui.popStyleVar(3);
		try {
			ImGui.setCursorScreenPos(x0, top);
			ImGui.invisibleButton("##split", SPLITTER_WIDTH, h);
			rect(which == 1 ? "splitter_left" : "splitter_right");
			if (ImGui.isItemActivated()) {
				dragging = which;
				dragOffset = ImGui.getMousePosX() - x;
			}
			boolean hot = ImGui.isItemHovered() || ImGui.isItemActive() || dragging == which;
			if (hot) {
				ImGui.setMouseCursor(imgui.flag.ImGuiMouseCursor.ResizeEW);
				ImGui.getWindowDrawList().addRectFilled(x - 1, top, x + 1, top + h, ImGui.getColorU32(
						dragging == which ? ImGuiCol.SeparatorActive : ImGuiCol.SeparatorHovered));
			}
		} finally {
			ImGui.end();
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
			boolean any = !viewTree().selected().isEmpty();
			if (ImGui.menuItem("Group selected", "Ctrl+G", false, any)) groupSelected();
			if (ImGui.menuItem("Duplicate", "Ctrl+D", false, any)) duplicateSelected();
			if (ImGui.menuItem("Delete", "Del", false, any)) deleteSelected();
			ImGui.endMenu();
		}
		boolean help = ImGui.beginMenu("Help");
		rect("menu_help");
		if (help) {
			if (ImGui.menuItem("Copy visuals repro")) copyRepro();
			rect("menu_copy_repro");
			if (ImGui.isItemHovered())
				ImGui.setTooltip("Copies what decides this view (camera, seed, render and LOD distance, modules,\nloaded / real / guessed chunks around the camera) as JSON, for a rendering bug report.\nIt contains the world seed.");
			ImGui.separator();
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
			dimensionTabs();
			SelectionTree tree = viewTree();
			if (ImGui.smallButton("+ Group")) {
				deferred = () -> {
					GroupNode g = tree.add(new GroupNode(), Editor.targetGroup(tree), -1);
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
			if (ImGui.smallButton("Delete")) deleteSelected();
			rect("tree_delete");
			if (!any) ImGui.endDisabled();
			searchBar(tree);
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

	/** The tree shown in the tree view: the selections of the dimension picked in the tab bar. */
	public SelectionTree viewTree() {
		return editor.trees().tree(viewDimension());
	}

	/** The dimension whose selections the tree view shows (by default the player's). */
	public String viewDimension() {
		return viewDimension.isEmpty() ? editor.dimension() : viewDimension;
	}

	/** Shows another dimension's selections in the tree view (tests); the world still shows the player's. */
	public void showDimension(String dimension) {
		viewDimension = dimension;
		selectTab = true;
	}

	/**
	 * Tabs above the tree: overworld, nether, end (and the player's dimension if it is another one). They
	 * only switch the tree view; the world only ever shows the selections of the dimension the player is in.
	 * Entering another dimension switches to its tab.
	 */
	private void dimensionTabs() {
		String here = editor.dimension();
		if (!here.equals(lastDimension)) {
			lastDimension = here;
			viewDimension = here;
			selectTab = true;
		}
		List<String> dims = new ArrayList<>(List.of(DimensionTrees.OVERWORLD, DimensionTrees.NETHER, DimensionTrees.END));
		if (!dims.contains(here)) dims.add(here);
		if (!dims.contains(viewDimension())) viewDimension = here;
		if (ImGui.beginTabBar("##dimensions")) {
			try {
				for (String d : dims) {
					String name = DimensionTrees.displayName(d);
					boolean isHere = d.equals(here);
					int flags = selectTab && d.equals(viewDimension()) ? ImGuiTabItemFlags.SetSelected : 0;
					// room for a dot that marks the player's dimension
					boolean open = ImGui.beginTabItem(name + (isHere ? "    " : "") + "###dim_" + d, flags);
					if (open) ImGui.endTabItem();
					rect("tab:" + name);
					if (isHere) {
						float cx = ImGui.getItemRectMaxX() - ImGui.getStyle().getFramePaddingX() - 6;
						float cy = (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2;
						ImGui.getWindowDrawList().addCircleFilled(cx, cy, 3.5f, 0xFF66D9FF);
					}
					tooltip(name, isHere ? "Your dimension: its selections are the ones shown in the world."
							: "Shows the selections kept for the " + name.toLowerCase(java.util.Locale.ROOT)
							+ ". They are only drawn in the world while you are there.");
					if (open && !selectTab) viewDimension = d;
				}
			} finally {
				ImGui.endTabBar();
			}
		}
		selectTab = false;
	}

	/**
	 * The search field above the tree. While it has text the tree shows only the matches and the groups
	 * they are in, opened ({@link TreeSearch}); opening or closing groups then is only for this search, so
	 * clearing it brings back how the groups were.
	 */
	private void searchBar(SelectionTree tree) {
		boolean clear = !searchField.isEmpty();
		ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - (clear ? ImGui.getFrameHeight() + ImGui.getStyle().getItemSpacingX() : 0));
		ImGui.inputTextWithHint("##tree_search", "Search", searchField);
		rect("tree_search");
		if (ImGui.isItemFocused() && ImGui.isKeyPressed(ImGuiKey.Escape)) searchField.set("");
		if (clear) {
			ImGui.sameLine();
			if (ImGui.button("x##clear_search", ImGui.getFrameHeight(), ImGui.getFrameHeight())) searchField.set("");
			rect("tree_search_clear");
			tooltip("Clear the search", "Shows the whole tree again, with the groups opened as before.");
		}
		String q = dev.plainsight.imgui.ImText.of(searchField);
		if (q == null || q.isBlank()) {
			search = null;
			searchQuery = "";
			searchOpen.clear();
			return;
		}
		if (!q.equals(searchQuery)) {
			// a new query opens the groups with matches again
			searchQuery = q;
			searchOpen.clear();
		}
		search = new TreeSearch(tree.root(), q);
	}

	private void treeContents(SelectionTree tree) {
		// a narrower frame padding brings the open/close arrows closer to the panel edge and the icons
		ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, TREE_FRAME_PAD_X, ImGui.getStyle().getFramePaddingY());
		try {
			for (SelectionNode n : tree.root().children()) node(n);
			if (search != null && search.matches() == 0) ImGui.textDisabled("No matches");
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
		} finally {
			ImGui.popStyleVar();
		}
	}

	private void node(SelectionNode n) {
		TreeSearch search = this.search;
		if (search != null && !search.shows(n)) return;
		SelectionTree tree = viewTree();
		int flags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.SpanAvailWidth | ImGuiTreeNodeFlags.FramePadding
				| ImGuiTreeNodeFlags.AllowOverlap;
		if (tree.selected().contains(n)) flags |= ImGuiTreeNodeFlags.Selected;
		boolean group = n instanceof GroupNode;
		if (!group) flags |= ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen;
		// while searching, groups open to show matches; what the user opens or closes then is kept apart
		boolean wantOpen = !group ? false : search == null ? ((GroupNode) n).expanded
				: searchOpen.getOrDefault(n.id(), search.expand.contains(n));
		if (group) ImGui.setNextItemOpen(wantOpen);
		boolean renamingThis = renaming == n;
		boolean shown = shownInWorld(n);
		// the label is drawn below (icon, clipped title, grey detail), the tree node only gets an id
		boolean open = ImGui.treeNodeEx(n.id().toString(), flags, "");
		String key = n.title();
		rect("node:" + key);
		float rowMinX = ImGui.getItemRectMinX(), rowMinY = ImGui.getItemRectMinY();
		float rowMaxX = ImGui.getItemRectMaxX(), rowMaxY = ImGui.getItemRectMaxY();
		float iconX = rowMinX + ImGui.getTreeNodeToLabelSpacing(), textX = iconX + ICON_WIDTH + ICON_GAP;
		if (!renamingThis) {
			drawNodeIcon(n, iconX, shown);
			nodeText(n, key, textX, rowMinY, rowMaxY, rowMaxX - ROW_BUTTONS_WIDTH, shown);
		}
		if (group && search == null) ((GroupNode) n).expanded = open;
		else if (group && open != wantOpen) searchOpen.put(n.id(), open);
		if (ImGui.isItemClicked(ImGuiMouseButton.Left) && !ImGui.isItemToggledOpen()) {
			boolean ctrl = ImGui.getIO().getKeyCtrl();
			if (!ctrl && n == editor.boxTool.editing()) {
				// clicking the box being moved again puts it down
				editor.boxTool.stopEditing();
			} else if (!ctrl && n == editor.pointTool.editing()) {
				editor.pointTool.stopEditing();
			} else {
				tree.select(n, ctrl);
				if (tree != editor.tree) {
					// another dimension's selection: it can be renamed, grouped, hidden..., not moved
				} else if (n instanceof BoxNode b && tree.selected().contains(n)) editor.editBox(b);
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
		if (renamingThis) renameField(n, textX, rowMinY, rowMaxX - ROW_BUTTONS_WIDTH);
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
		rect("vis:" + n.title());
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
			viewTree().touch();
		}
	}

	/** Diamond button left of the eye: shows/hides the node as a waypoint in the world (marker and name at its centre). */
	private void waypointButton(SelectionNode n, float rowMaxX, float rowY, float rowH) {
		float w = 22, x = rowMaxX - w;
		ImGui.setCursorScreenPos(x, rowY);
		ImGui.pushID("wp" + n.id());
		boolean clicked = ImGui.invisibleButton("##wp", w, rowH);
		ImGui.popID();
		rect("wp:" + n.title());
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
			viewTree().touch();
		}
	}

	/**
	 * A row's text: the title, then the detail (position, size) in grey. Both are clipped before the row's
	 * buttons, where a gradient in the row's background colour fades them out; hovering a clipped row shows
	 * the whole text, wrapped, in a tooltip.
	 */
	private void nodeText(SelectionNode n, String key, float x, float minY, float maxY, float clipX, boolean shown) {
		String title = n.title(), detail = n.detail();
		float titleW = ImGui.calcTextSizeX(title);
		float detailX = x + titleW + ImGui.calcTextSizeX(" ");
		float endX = detail.isEmpty() ? x + titleW : detailX + ImGui.calcTextSizeX(detail);
		boolean clipped = endX > clipX;
		float y = (minY + maxY - ImGui.getTextLineHeight()) / 2;
		ImDrawList dl = ImGui.getWindowDrawList();
		dl.pushClipRect(x, minY, Math.max(x, clipX), maxY, true);
		try {
			dl.addText(x, y, shown ? TEXT_COLOR : HIDDEN_TEXT_COLOR, title);
			if (!detail.isEmpty()) dl.addText(detailX, y, shown ? DETAIL_COLOR : HIDDEN_DETAIL_COLOR, detail);
		} finally {
			dl.popClipRect();
		}
		widgetRects.put("nodetext:" + key, new float[]{x, minY, Math.min(endX, clipX), maxY});
		if (!clipped) return;
		// the row is drawn in Header colours while selected or hovered (opaque in our style), else on the window
		boolean hovered = ImGui.isItemHovered(), held = hovered && ImGui.isItemActive();
		int bgCol = held ? ImGuiCol.HeaderActive : hovered ? ImGuiCol.HeaderHovered
				: viewTree().selected().contains(n) ? ImGuiCol.Header : ImGuiCol.WindowBg;
		int bg = ImGui.getColorU32(bgCol) | 0xFF000000, clear = bg & 0x00FFFFFF;
		float fade = Math.min(FADE_WIDTH, clipX - x);
		dl.addRectFilledMultiColor(clipX - fade, minY, clipX, maxY, clear, bg, bg, clear);
		widgetRects.put("nodefade:" + key, new float[]{clipX - fade, minY, clipX, maxY});
		if (hovered && ImGui.getMousePosX() < clipX && !ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
			ImGui.beginTooltip();
			ImGui.pushTextWrapPos(ImGui.getFontSize() * 20);
			ImGui.textWrapped(title);
			if (!detail.isEmpty()) {
				ImGui.pushStyleColor(ImGuiCol.Text, DETAIL_COLOR);
				ImGui.textWrapped(detail);
				ImGui.popStyleColor();
			}
			ImGui.popTextWrapPos();
			ImGui.endTooltip();
			nodeTooltip = title;
		}
	}

	/** Small vector icon in front of a tree label: folder (group), box outline, person (entity). */
	private void drawNodeIcon(SelectionNode n, float x, boolean shown) {
		ImDrawList dl = ImGui.getWindowDrawList();
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
		SelectionTree tree = viewTree();
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
		SelectionTree tree = viewTree();
		if (ImGui.beginPopupContextItem("ctx" + n.id())) {
			try {
				contextMenuItems(n);
			} finally {
				ImGui.endPopup();
			}
		}
	}

	private void contextMenuItems(SelectionNode n) {
		SelectionTree tree = viewTree();
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
			boolean here = tree == editor.tree;
			if (ImGui.menuItem("Focus camera", "", false, here)) editor.focus(n);
			if (n instanceof EntityNode en) {
				var mc = Minecraft.getInstance();
				var entity = mc.level == null ? null : mc.level.getEntity(en.entity());
				if (ImGui.menuItem("Follow with camera", "", false, here && entity != null)) {
					editor.focus(n);
					editor.follow(entity);
				}
			}
			ImGui.separator();
			if (ImGui.menuItem("Delete", "Del")) deleteSelected();
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
			if (!ImGui.isKeyPressed(ImGuiKey.Escape)) viewTree().rename(n, dev.plainsight.imgui.ImText.of(renameField));
			renaming = null;
		}
	}

	private void groupSelected() {
		SelectionTree tree = viewTree();
		List<SelectionNode> sel = new ArrayList<>(tree.selected());
		if (sel.isEmpty()) return;
		deferred = () -> {
			GroupNode g = tree.group(sel);
			tree.select(g, false);
		};
	}

	private void duplicateSelected() {
		SelectionTree tree = viewTree();
		List<SelectionNode> sel = tree.topLevel(new ArrayList<>(tree.selected()));
		deferred = () -> {
			tree.clearSelection();
			for (SelectionNode n : sel) tree.select(tree.duplicate(n), true);
		};
	}

	private void deleteSelected() {
		SelectionTree tree = viewTree();
		deferred = () -> editor.deleteSelected(tree);
	}

	/** Shortcuts that act on the tree while the tree panel is focused or the viewport has focus. */
	public void shortcuts() {
		if (ImGui.getIO().getWantTextInput()) return;
		if (ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.G, false)) groupSelected();
		if (ImGui.getIO().getKeyCtrl() && ImGui.isKeyPressed(ImGuiKey.D, false)) duplicateSelected();
		if (ImGui.isKeyPressed(ImGuiKey.F2, false) && viewTree().selected().size() == 1) startRename(viewTree().selected().iterator().next());
	}

	private void properties() {
		ImGui.setNextItemOpen(true, ImGuiCond.FirstUseEver);
		if (!ImGui.collapsingHeader("Properties")) return;
		var sel = viewTree().selected();
		if (sel.isEmpty()) {
			ImGui.textDisabled("Nothing selected");
			return;
		}
		if (sel.size() > 1) {
			long blocks = 0;
			for (SelectionNode n : viewTree().topLevel(new ArrayList<>(sel))) blocks += n.blockCount();
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
				positions("centre", c.x, c.y, c.z, false);
				ImGui.textDisabled(String.format("size %.2f x %.2f x %.2f%s", b.box().half.x * 2, b.box().half.y * 2, b.box().half.z * 2,
						b.box().isAxisAligned() ? "" : "  (rotated)"));
			}
			case EntityNode e -> {
				ImGui.textDisabled(e.entity().toString());
				var mc = Minecraft.getInstance();
				boolean loaded = mc.level != null && mc.level.getEntity(e.entity()) != null;
				ImGui.textDisabled(loaded ? "loaded" : "not loaded");
			}
			case dev.plainsight.editor.selection.PointNode p -> positions("position", p.x, p.y, p.z, true);
		}
		boolean here = viewTree() == editor.tree;
		if (!here) ImGui.beginDisabled();
		if (ImGui.button("Focus")) editor.focus(n);
		if (!here) ImGui.endDisabled();
		copyButtons(List.of(n));
	}

	/**
	 * A selection's position in the shown tree's dimension. Overworld and Nether selections also show where
	 * that is in the other one (x and z times or divided by 8); an End selection seen from another dimension
	 * says "End".
	 */
	private void positions(String what, double x, double y, double z, boolean blocks) {
		String dim = viewDimension(), here = editor.dimension();
		boolean overworld = dim.equals(DimensionTrees.OVERWORLD), nether = dim.equals(DimensionTrees.NETHER);
		if (overworld || nether) {
			String other = overworld ? DimensionTrees.NETHER : DimensionTrees.OVERWORLD;
			double f = overworld ? 1 / 8.0 : 8;
			String real = DimensionTrees.displayName(dim) + " " + what + " (real): " + coords(x, y, z, blocks);
			String translated = DimensionTrees.displayName(other) + " " + what + " (translated): " + coords(x * f, y, z * f, blocks);
			// the player's dimension first
			boolean swap = here.equals(other);
			ImGui.textDisabled(swap ? translated : real);
			rect(swap ? "pos_translated" : "pos_real");
			ImGui.textDisabled(swap ? real : translated);
			rect(swap ? "pos_real" : "pos_translated");
			positionText = (swap ? translated + "\n" + real : real + "\n" + translated);
		} else {
			String label = dim.equals(DimensionTrees.END) && !here.equals(dim) ? "End " + what : what;
			positionText = label + " " + coords(x, y, z, blocks);
			ImGui.textDisabled(positionText);
			rect("pos_real");
		}
	}

	private static String coords(double x, double y, double z, boolean blocks) {
		return blocks ? (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z)
				: String.format("%.2f %.2f %.2f", x, y, z);
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

	private void copyRepro() {
		String json = dev.plainsight.editor.VisualsRepro.capture(editor);
		Minecraft.getInstance().keyboardHandler.setClipboard(json);
		editor.setStatus("Copied the visuals repro (" + json.length() + " characters, contains the seed)");
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
		if (dev.infiniterra.api.LodTerrain.distanceScaleSupported()) {
			// how far the LOD reaches in the editor (Voxy's LOD distance times this)
			ImGui.sameLine(w - 2 * sliderW - 210);
			ImGui.text("LOD distance");
			ImGui.sameLine();
			ImGui.setNextItemWidth(sliderW);
			float[] lod = {dev.plainsight.editor.Editor.lodDistanceScale()};
			if (ImGui.sliderFloat("##lodDistance", lod, 1f, dev.infiniterra.api.LodTerrain.MAX_DISTANCE_SCALE, "x%.1f"))
				dev.plainsight.editor.Editor.setLodDistanceScale(lod[0], false);
			if (ImGui.isItemDeactivatedAfterEdit()) dev.plainsight.editor.Editor.setLodDistanceScale(lod[0], true);
			if (ImGui.isItemHovered()) ImGui.setTooltip("How far Voxy draws terrain in the editor, times its own LOD distance.\nFurther needs more GPU memory.");
			rect("lod_distance");
		}
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
