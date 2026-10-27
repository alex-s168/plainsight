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
		editor.buildOverlay();
		double t = System.nanoTime() / 1e9;
		for (PathOverlay p : PathOverlay.visible()) p.draw(editor.overlay, t);
		OVERLAY.render(editor.overlay, renderer.gameRenderState().levelRenderState.cameraRenderState);
	}

	public static void ui(GameRenderer renderer) {
		Minecraft mc = Minecraft.getInstance();
		Editor editor = Editor.get();
		if (!Editor.isActive()) {
			if (textInput) {
				mc.textInputManager().stopTextInput(EditorRendering.class);
				textInput = false;
			}
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
