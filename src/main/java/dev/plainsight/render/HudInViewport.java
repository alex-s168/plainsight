package dev.plainsight.render;

import dev.plainsight.editor.Editor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * In the editor the HUD (vanilla's and what mods add to it, e.g. a minimap) is laid out in the viewport
 * between the panels (below its button bar) instead of over them: while it is extracted the GUI is moved to the viewport's
 * corner and clipped to it, and the window reports the viewport's size as its GUI size, so elements
 * anchor to the viewport's edges at their normal size. Picture-in-picture elements (models drawn into a
 * texture first, like a mod's minimap) carry absolute positions: they are noted when added and their
 * texture is blitted shifted into the viewport and clipped to it.
 */
public final class HudInViewport {
	/** While the HUD is extracted in the editor: the GUI size the window reports (GUI pixels). */
	public static boolean active;
	public static int width, height;
	/** This frame's viewport in GUI pixels, and the picture-in-picture states the HUD added. */
	private static int x, y;
	private static final java.util.Set<Object> PIP = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	private HudInViewport() {
	}

	public static void extract(GuiGraphicsExtractor graphics, Runnable hud) {
		if (!Editor.isActive() || active) {
			hud.run();
			return;
		}
		var w = Minecraft.getInstance().getWindow();
		var vp = Editor.get().viewport;
		// viewport (window units, as the editor UI) to GUI pixels
		float f = w.getGuiScaledWidth() / Math.max(1f, vp.windowWidth);
		// below the viewport's button bar
		float top = Math.max(vp.y, Math.min(vp.y + vp.height / 2, Editor.get().ui.viewportButtonsBottom + 4));
		x = Math.round(vp.x * f);
		y = Math.round(top * f);
		width = Math.max(1, Math.round(vp.width * f));
		height = Math.max(1, Math.round((vp.y + vp.height - top) * f));
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.enableScissor(0, 0, width, height);
		active = true;
		try {
			hud.run();
		} finally {
			active = false;
			graphics.disableScissor();
			graphics.pose().popMatrix();
		}
	}

	/** A new GUI frame is extracted (the previous frame's states are gone). */
	public static void newFrame() {
		PIP.clear();
	}

	public static void notePictureInPicture(Object state) {
		if (active) PIP.add(state);
	}

	/** The pose a picture-in-picture state is blitted with: moved into the viewport if the HUD added it. */
	public static org.joml.Matrix3x2fc pose(Object state, org.joml.Matrix3x2fc pose) {
		return PIP.contains(state) ? new org.joml.Matrix3x2f().translation(x, y).mul(pose) : pose;
	}

	/** Its scissor: moved along and clipped to the viewport. */
	public static net.minecraft.client.gui.navigation.@org.jspecify.annotations.Nullable ScreenRectangle scissor(Object state,
			net.minecraft.client.gui.navigation.@org.jspecify.annotations.Nullable ScreenRectangle scissor) {
		if (!PIP.contains(state)) return scissor;
		var viewport = new net.minecraft.client.gui.navigation.ScreenRectangle(x, y, width, height);
		if (scissor == null) return viewport;
		var moved = new net.minecraft.client.gui.navigation.ScreenRectangle(scissor.left() + x, scissor.top() + y, scissor.width(), scissor.height());
		var clipped = moved.intersection(viewport);
		return clipped != null ? clipped : new net.minecraft.client.gui.navigation.ScreenRectangle(x, y, 0, 0);
	}
}
