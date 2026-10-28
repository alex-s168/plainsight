package dev.plainsight.module;

import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;

/**
 * Renders water and lava translucent (or not at all) so builds under water are visible. Applied while
 * chunk meshes are built, so changing it rebuilds the loaded chunks.
 */
public final class FluidTransparency implements Module {
	private static volatile boolean active;
	private static volatile float opacity = 0.25f;

	/** Read by the chunk compiler threads. */
	public static boolean isActive() {
		return active;
	}

	public static float opacity() {
		return opacity;
	}

	@Override
	public String id() {
		return "fluid_transparency";
	}

	@Override
	public String name() {
		return "Transparent Fluids";
	}

	@Override
	public String description() {
		return "Make water and lava see-through. Right-click for opacity.";
	}

	@Override
	public boolean enabled() {
		return active;
	}

	@Override
	public void setEnabled(boolean enabled) {
		if (active == enabled) return;
		active = enabled;
		dev.plainsight.render.AlphaOverride.fluidActive = enabled;
		rebuild();
	}

	public void setOpacity(float o) {
		float clamped = Math.max(0f, Math.min(1f, o));
		if (clamped == opacity) return;
		opacity = clamped;
		if (active) rebuild();
	}

	private static void rebuild() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) mc.levelExtractor.allChanged();
	}

	private final float[] slider = {opacity};

	@Override
	public void options() {
		if (!ImGui.isAnyItemActive()) slider[0] = opacity;
		ImGui.setNextItemWidth(160);
		ImGui.sliderFloat("Opacity", slider, 0f, 1f, "%.2f");
		// applied on release: every change rebuilds all chunk meshes
		if (ImGui.isItemDeactivatedAfterEdit()) setOpacity(slider[0]);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2, cy = y + size * 0.6f, r = size * 0.24f;
		dl.addTriangleFilled(cx, y + size * 0.14f, cx - r * 0.92f, cy - r * 0.35f, cx + r * 0.92f, cy - r * 0.35f, color);
		dl.addCircleFilled(cx, cy, r, color, 20);
		if (active) dl.addLine(x + size * 0.2f, y + size * 0.85f, x + size * 0.8f, y + size * 0.15f, 0xFF202020, 3f);
	}
}
