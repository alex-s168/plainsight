package dev.plainsight.render;

import dev.infiniterra.api.CameraPass;
import dev.infiniterra.api.TerrainGuess;
import com.mojang.blaze3d.platform.Window;
import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/** Per-frame entry points called from the game renderer. */
public final class EditorRendering {
	private static final OverlayRenderer OVERLAY = new OverlayRenderer();
	private static boolean textInput;
	public static long frames;
	/** Frames that showed the editor map's image (tests). */
	public static long mapBlits;

	private EditorRendering() {
	}

	public static void afterLevel(GameRenderer renderer) {
		Editor editor = Editor.get();
		WaypointMarkers.capture(renderer.gameRenderState().levelRenderState.cameraRenderState);
		// the editor's map (M): its image (and depth, unless through a shader pack) replaces the level's
		if (Editor.isActive() && dev.plainsight.minimap.Minimap.takeEditorMap()) {
			mapBlits++;
			MapBlit.blit(CameraPass.colour(), CameraPass.throughPack() ? null : CameraPass.depth(), renderer.mainRenderTarget(), editor.viewport);
		}
		// the surface under the cursor, before overlays add their own depth
		if (Editor.isActive()) DepthPick.afterLevel(renderer.gameRenderState().levelRenderState.cameraRenderState);
		// block and path overlays: always in the editor, in the normal game while its overlays are shown (J)
		boolean show = Editor.isActive() || editor.overlaysInGame();
		if (show) BlockOverlayRenderer.render(renderer.gameRenderState().levelRenderState.cameraRenderState);
		else BlockOverlayRenderer.submitted = 0;
		editor.buildOverlay();
		double t = System.nanoTime() / 1e9;
		if (show) for (PathOverlay p : PathOverlay.visible()) p.draw(editor.overlay, t);
		OVERLAY.render(editor.overlay, renderer.gameRenderState().levelRenderState.cameraRenderState);
		if (Editor.isActive() && editor.guessedTerrain.active()) TerrainGuess.drawTint(renderer.gameRenderState().levelRenderState.cameraRenderState);
	}

	/** HUD outside the editor: an ImGui frame for the minimap and waypoints (no input goes to ImGui here). */
	private static void hud(GameRenderer renderer) {
		Window w = Minecraft.getInstance().getWindow();
		float sw = Math.max(1, w.getScreenWidth()), sh = Math.max(1, w.getScreenHeight());
		ImGuiLayer layer = ImGuiLayer.get();
		try {
			layer.newFrame(w.getWidth(), w.getHeight(), w.getWidth() / sw, w.getHeight() / sh);
			dev.plainsight.minimap.Minimap.draw();
			if (!Minecraft.getInstance().gui.hud.isHidden())
				WaypointMarkers.draw(imgui.ImGui.getBackgroundDrawList(), 0, 0, sw, sh, sw, sh, sw / 2, sh / 2);
		} finally {
			layer.endFrameAndRender(renderer.mainRenderTarget());
		}
	}

	public static void ui(GameRenderer renderer) {
		Minecraft mc = Minecraft.getInstance();
		Editor editor = Editor.get();
		if (!Editor.isActive()) {
			if (textInput) {
				mc.textInputManager().stopTextInput(EditorRendering.class);
				textInput = false;
			}
			if (mc.level != null && (dev.plainsight.minimap.Minimap.visible() || !mc.gui.hud.isHidden() && WaypointMarkers.any())) hud(renderer);
			return;
		}
		Window w = mc.getWindow();
		float sw = Math.max(1, w.getScreenWidth()), sh = Math.max(1, w.getScreenHeight());
		ImGuiLayer layer = ImGuiLayer.get();
		try {
			layer.newFrame(w.getWidth(), w.getHeight(), w.getWidth() / sw, w.getHeight() / sh);
			editor.ui.draw(sw, sh);
		} catch (RuntimeException e) {
			dev.plainsight.Plainsight.LOG.error("Editor UI failed; closing the editor", e);
			editor.disable();
		} finally {
			// always close the ImGui frame, even if drawing the UI failed half-way
			layer.endFrameAndRender(renderer.mainRenderTarget());
		}
		frames++;
		// SDL only delivers text events while text input is started
		boolean wants = layer.wantsTextInput();
		if (wants != textInput) {
			if (wants) mc.textInputManager().startTextInput(EditorRendering.class);
			else mc.textInputManager().stopTextInput(EditorRendering.class);
			textInput = wants;
		}
	}
}
