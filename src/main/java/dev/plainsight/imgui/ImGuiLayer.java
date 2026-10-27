package dev.plainsight.imgui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.plainsight.Plainsight;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Owns the Dear ImGui context: fonts, style, the per-frame lifecycle and the translation of
 * Minecraft's (SDL) input events into ImGui input events.
 */
public final class ImGuiLayer {
	private static ImGuiLayer instance;

	private final ImGuiRenderer renderer = new ImGuiRenderer();
	private ImGuiIO io;
	private boolean frameStarted;
	private long lastFrameNanos;
	private int mods;

	public static ImGuiLayer get() {
		if (instance == null) {
			instance = new ImGuiLayer();
			instance.init();
		}
		return instance;
	}

	public static boolean isInitialized() {
		return instance != null;
	}

	public ImGuiRenderer renderer() {
		return renderer;
	}

	private void init() {
		ImGui.createContext();
		// failed assertions are logged instead of aborting the game
		ImGui.setAssertCallback(new imgui.assertion.ImAssertCallback() {
			@Override
			public void imAssertCallback(String assertion, int line, String file) {
				Plainsight.LOG.error("ImGui assertion failed: {} ({}:{})", assertion, file, line);
			}
		});
		io = ImGui.getIO();
		Path dir = FabricLoader.getInstance().getConfigDir().resolve(Plainsight.ID);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not create {}", dir, e);
		}
		io.setIniFilename(dir.resolve("imgui.ini").toString());
		io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
		io.addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
		io.setBackendPlatformName("plainsight-minecraft");
		io.setBackendRendererName("plainsight-renderpearl");
		io.setConfigWindowsMoveFromTitleBarOnly(true);
		io.setSetClipboardTextFn(new imgui.callback.ImStrConsumer() {
			@Override
			public void accept(String s) {
				Minecraft.getInstance().keyboardHandler.setClipboard(s);
			}
		});
		io.setGetClipboardTextFn(new imgui.callback.ImStrSupplier() {
			@Override
			public String get() {
				return Minecraft.getInstance().keyboardHandler.getClipboard();
			}
		});
		loadFonts();
		applyStyle(ImGui.getStyle());
		renderer.createFontTexture(io.getFonts());
		lastFrameNanos = System.nanoTime();
	}

	private void loadFonts() {
		try (InputStream in = ImGuiLayer.class.getResourceAsStream("/assets/plainsight/fonts/AdwaitaSans-Regular.ttf")) {
			if (in == null) throw new IOException("font missing");
			byte[] ttf = in.readAllBytes();
			ImFontConfig cfg = new ImFontConfig();
			cfg.setOversampleH(2);
			cfg.setOversampleV(2);
			io.getFonts().addFontFromMemoryTTF(ttf, 16f, cfg);
			cfg.destroy();
		} catch (IOException e) {
			Plainsight.LOG.warn("Using ImGui's default font", e);
			io.getFonts().addFontDefault();
		}
		io.getFonts().build();
	}

	/** Dark panels with blue accents. */
	private static void applyStyle(ImGuiStyle s) {
		ImGui.styleColorsDark();
		s.setWindowRounding(0);
		s.setChildRounding(0);
		s.setFrameRounding(2);
		s.setPopupRounding(2);
		s.setScrollbarRounding(2);
		s.setGrabRounding(2);
		s.setTabRounding(2);
		s.setWindowBorderSize(1);
		s.setFrameBorderSize(0);
		s.setWindowPadding(8, 6);
		s.setFramePadding(5, 3);
		s.setItemSpacing(6, 4);
		s.setIndentSpacing(16);
		s.setWindowTitleAlign(0f, 0.5f);
		float[][] c = {
				{ImGuiCol.Text, .92f, .92f, .92f, 1},
				{ImGuiCol.TextDisabled, .50f, .50f, .50f, 1},
				{ImGuiCol.WindowBg, .135f, .135f, .135f, 1},
				{ImGuiCol.ChildBg, .135f, .135f, .135f, 0},
				{ImGuiCol.PopupBg, .11f, .11f, .11f, .98f},
				{ImGuiCol.Border, .06f, .06f, .06f, 1},
				{ImGuiCol.FrameBg, .20f, .20f, .20f, 1},
				{ImGuiCol.FrameBgHovered, .26f, .32f, .42f, 1},
				{ImGuiCol.FrameBgActive, .24f, .38f, .60f, 1},
				{ImGuiCol.TitleBg, .10f, .10f, .10f, 1},
				{ImGuiCol.TitleBgActive, .16f, .29f, .48f, 1},
				{ImGuiCol.TitleBgCollapsed, .10f, .10f, .10f, 1},
				{ImGuiCol.MenuBarBg, .11f, .11f, .11f, 1},
				{ImGuiCol.ScrollbarBg, .10f, .10f, .10f, 1},
				{ImGuiCol.CheckMark, .40f, .65f, 1f, 1},
				{ImGuiCol.SliderGrab, .33f, .55f, .90f, 1},
				{ImGuiCol.SliderGrabActive, .45f, .65f, 1f, 1},
				{ImGuiCol.Button, .20f, .36f, .60f, 1},
				{ImGuiCol.ButtonHovered, .27f, .46f, .76f, 1},
				{ImGuiCol.ButtonActive, .33f, .55f, .90f, 1},
				{ImGuiCol.Header, .20f, .36f, .60f, 1},
				{ImGuiCol.HeaderHovered, .26f, .44f, .72f, 1},
				{ImGuiCol.HeaderActive, .33f, .55f, .90f, 1},
				{ImGuiCol.Separator, .25f, .25f, .25f, 1},
				{ImGuiCol.Tab, .16f, .16f, .16f, 1},
				{ImGuiCol.TabHovered, .27f, .46f, .76f, 1},
				{ImGuiCol.TabActive, .20f, .36f, .60f, 1},
				{ImGuiCol.TabUnfocused, .16f, .16f, .16f, 1},
				{ImGuiCol.TabUnfocusedActive, .20f, .30f, .45f, 1},
				{ImGuiCol.DragDropTarget, .45f, .65f, 1f, 1},
				{ImGuiCol.NavHighlight, .45f, .65f, 1f, 1},
				{ImGuiCol.TextSelectedBg, .26f, .46f, .80f, .5f},
		};
		for (float[] e : c) s.setColor((int) e[0], e[1], e[2], e[3], e[4]);
	}

	// -- frame ------------------------------------------------------------------------------------------

	public void newFrame(int width, int height, float scaleX, float scaleY) {
		long now = System.nanoTime();
		float dt = Math.max(1e-4f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		io.setDisplaySize(width / scaleX, height / scaleY);
		io.setDisplayFramebufferScale(scaleX, scaleY);
		io.setDeltaTime(dt);
		ImGui.newFrame();
		frameStarted = true;
	}

	public void endFrameAndRender(RenderTarget target) {
		if (!frameStarted) return;
		frameStarted = false;
		ImGui.render();
		renderer.render(ImGui.getDrawData(), target);
	}

	public boolean wantsMouse() {
		return io.getWantCaptureMouse();
	}

	public boolean wantsKeyboard() {
		return io.getWantCaptureKeyboard();
	}

	public boolean wantsTextInput() {
		return io.getWantTextInput();
	}

	// -- input ------------------------------------------------------------------------------------------

	public void mouseMove(double x, double y) {
		io.addMousePosEvent((float) x, (float) y);
	}

	/** Minecraft/SDL buttons: 1 left, 2 middle, 3 right. */
	public void mouseButton(int sdlButton, boolean down) {
		int b = switch (sdlButton) {
			case 1 -> 0;
			case 3 -> 1;
			case 2 -> 2;
			default -> sdlButton - 1;
		};
		if (b >= 0 && b < 5) io.addMouseButtonEvent(b, down);
	}

	public void scroll(double dx, double dy) {
		io.addMouseWheelEvent((float) dx, (float) dy);
	}

	public void key(int scancode, boolean down) {
		switch (scancode) {
			case 224, 228 -> setMod(ImGuiKey.ModCtrl, 1, down);
			case 225, 229 -> setMod(ImGuiKey.ModShift, 2, down);
			case 226, 230 -> setMod(ImGuiKey.ModAlt, 4, down);
			case 227, 231 -> setMod(ImGuiKey.ModSuper, 8, down);
			default -> {
			}
		}
		int k = imguiKey(scancode);
		if (k != ImGuiKey.None) io.addKeyEvent(k, down);
	}

	private void setMod(int key, int bit, boolean down) {
		mods = down ? mods | bit : mods & ~bit;
		io.addKeyEvent(key, (mods & bit) != 0);
	}

	public void chars(String text) {
		io.addInputCharactersUTF8(text);
	}

	public void focusLost() {
		io.addFocusEvent(false);
		mods = 0;
	}

	/** SDL (USB HID) scancode to ImGuiKey. */
	static int imguiKey(int sc) {
		if (sc >= 4 && sc <= 29) return ImGuiKey.A + (sc - 4);
		if (sc >= 30 && sc <= 38) return ImGuiKey._1 + (sc - 30);
		if (sc >= 58 && sc <= 69) return ImGuiKey.F1 + (sc - 58);
		if (sc >= 89 && sc <= 97) return ImGuiKey.Keypad1 + (sc - 89);
		return switch (sc) {
			case 39 -> ImGuiKey._0;
			case 40 -> ImGuiKey.Enter;
			case 41 -> ImGuiKey.Escape;
			case 42 -> ImGuiKey.Backspace;
			case 43 -> ImGuiKey.Tab;
			case 44 -> ImGuiKey.Space;
			case 45 -> ImGuiKey.Minus;
			case 46 -> ImGuiKey.Equal;
			case 47 -> ImGuiKey.LeftBracket;
			case 48 -> ImGuiKey.RightBracket;
			case 49 -> ImGuiKey.Backslash;
			case 51 -> ImGuiKey.Semicolon;
			case 52 -> ImGuiKey.Apostrophe;
			case 53 -> ImGuiKey.GraveAccent;
			case 54 -> ImGuiKey.Comma;
			case 55 -> ImGuiKey.Period;
			case 56 -> ImGuiKey.Slash;
			case 57 -> ImGuiKey.CapsLock;
			case 73 -> ImGuiKey.Insert;
			case 74 -> ImGuiKey.Home;
			case 75 -> ImGuiKey.PageUp;
			case 76 -> ImGuiKey.Delete;
			case 77 -> ImGuiKey.End;
			case 78 -> ImGuiKey.PageDown;
			case 79 -> ImGuiKey.RightArrow;
			case 80 -> ImGuiKey.LeftArrow;
			case 81 -> ImGuiKey.DownArrow;
			case 82 -> ImGuiKey.UpArrow;
			case 88 -> ImGuiKey.KeypadEnter;
			case 98 -> ImGuiKey.Keypad0;
			case 224 -> ImGuiKey.LeftCtrl;
			case 225 -> ImGuiKey.LeftShift;
			case 226 -> ImGuiKey.LeftAlt;
			case 227 -> ImGuiKey.LeftSuper;
			case 228 -> ImGuiKey.RightCtrl;
			case 229 -> ImGuiKey.RightShift;
			case 230 -> ImGuiKey.RightAlt;
			case 231 -> ImGuiKey.RightSuper;
			default -> ImGuiKey.None;
		};
	}
}
