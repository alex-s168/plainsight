package dev.plainsight.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.plainsight.Plainsight;
import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.io.InputStream;
import java.nio.file.Files;

/**
 * Switching from this editor to Axiom's (both open with Right Shift by default): this editor closes and
 * Axiom gets the press of its own editor key, which this mod lets through ({@link #passing()}).
 */
public final class Axiom {
	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("axiom");
	/** Axiom's editor key mapping, as named in its language file. */
	private static final String EDITOR_KEY = "axiom.keybind.toggle_editor_ui";
	private static boolean passing, axiomOpen;
	private static long icon = -1;
	private static boolean iconTried;

	private Axiom() {
	}

	/** While true, key presses go past this mod's key handling (to Axiom). */
	public static boolean passing() {
		return passing;
	}

	/**
	 * Whether Axiom's editor was opened from this one and is still open: until a press of Axiom's editor
	 * key (which goes to Axiom and closes it) or the mouse is captured again (Axiom closed some other way).
	 */
	public static boolean editorOpen() {
		if (!axiomOpen) return false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || Editor.isActive() || mc.mouseHandler.isMouseGrabbed()) axiomOpen = false;
		return axiomOpen;
	}

	/** A key press while Axiom's editor is open: it is Axiom's; its editor key closes it. */
	public static void keyInAxiom(KeyEvent event) {
		if (editorKey().getValue() == event.key()) axiomOpen = false;
	}

	/** Axiom's editor key: its own binding if it uses a game key mapping, else Right Shift (its default). */
	private static InputConstants.Key editorKey() {
		for (KeyMapping k : Minecraft.getInstance().options.keyMappings)
			if (k.getName().equals(EDITOR_KEY)) return KeyMappingHelper.getBoundKeyOf(k);
		return InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_RSHIFT);
	}

	public static String editorKeyName() {
		return editorKey().getDisplayName().getString();
	}

	/** Closes this editor and opens Axiom's. */
	public static void switchTo() {
		Minecraft mc = Minecraft.getInstance();
		Editor.get().disable();
		int key = editorKey().getValue();
		long window = mc.getWindow().handle();
		passing = true;
		try {
			mc.keyboardHandler.keyPress(window, 1, new KeyEvent(key, 0, 0));
			mc.keyboardHandler.keyPress(window, 0, new KeyEvent(key, 0, 0));
		} finally {
			passing = false;
		}
		axiomOpen = true;
		Plainsight.LOG.info("Switched to Axiom's editor");
	}

	/** Axiom's mod icon as an ImGui texture, or -1. */
	public static long icon() {
		if (iconTried) return icon;
		iconTried = true;
		try {
			var path = FabricLoader.getInstance().getModContainer("axiom").flatMap(m -> m.findPath("assets/axiom/icon.png"));
			if (path.isEmpty()) return icon;
			try (InputStream in = Files.newInputStream(path.get())) {
				DynamicTexture tex = new DynamicTexture(() -> "plainsight axiom icon", NativeImage.read(in));
				icon = ImGuiLayer.get().renderer().registerTexture(tex.getTextureView(), true);
			}
		} catch (Exception | LinkageError e) {
			Plainsight.LOG.debug("No Axiom icon", e);
		}
		return icon;
	}
}
