package dev.plainsight.module;

import dev.plainsight.guess.SeedHider;
import imgui.ImDrawList;

/** The seed hider ({@link SeedHider}) as a tool bar toggle; its state is kept in its own config file. */
public final class SeedHiding implements Module {
	@Override
	public String id() {
		return "hide_seeds";
	}

	@Override
	public String name() {
		return "Hide Seeds";
	}

	@Override
	public String description() {
		return "For screen sharing: seeds in chat (SeedcrackerX, /seed), titles and the editor show as dots.";
	}

	@Override
	public boolean enabled() {
		return SeedHider.enabled();
	}

	@Override
	public void setEnabled(boolean enabled) {
		SeedHider.setEnabled(enabled);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		// a masked field: a frame with dots in it
		dl.addRect(x + size * 0.14f, y + size * 0.32f, x + size * 0.86f, y + size * 0.68f, color, 3f, 0, 2f);
		for (int i = 0; i < 3; i++) dl.addCircleFilled(x + size * (0.32f + 0.18f * i), y + size * 0.5f, size * 0.055f, color, 10);
	}
}
