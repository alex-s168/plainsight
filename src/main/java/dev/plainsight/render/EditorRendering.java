package dev.plainsight.render;

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

	private EditorRendering() {
	}

	public static void afterLevel(GameRenderer renderer) {
		Editor editor = Editor.get();
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
		var guess = dev.plainsight.guess.GuessWorld.current();
		if (Editor.isActive() && guess != null && editor.guessedTerrain.active() && dev.plainsight.guess.VoxyGuess.showing())
			GuessTint.render(renderer.gameRenderState().levelRenderState.cameraRenderState, guess);
	}

	/** HUD outside the editor: an ImGui frame for the minimap only (no input goes to ImGui here). */
	private static void hud(GameRenderer renderer) {
		Window w = Minecraft.getInstance().getWindow();
		float sw = Math.max(1, w.getScreenWidth()), sh = Math.max(1, w.getScreenHeight());
		ImGuiLayer layer = ImGuiLayer.get();
		try {
			layer.newFrame(w.getWidth(), w.getHeight(), w.getWidth() / sw, w.getHeight() / sh);
			dev.plainsight.minimap.Minimap.draw();
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
			if (dev.plainsight.minimap.Minimap.visible()) hud(renderer);
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
