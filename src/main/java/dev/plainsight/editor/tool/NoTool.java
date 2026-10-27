package dev.plainsight.editor.tool;

import imgui.ImDrawList;
import imgui.ImGui;

/** No tool selected: RMB does nothing, the camera controls still work. */
public final class NoTool implements Tool {
	public static final NoTool INSTANCE = new NoTool();

	private NoTool() {
	}

	@Override
	public String id() {
		return "none";
	}

	@Override
	public String name() {
		return "No Tool";
	}

	@Override
	public String description() {
		return "No tool selected.";
	}

	@Override
	public String hints() {
		return "No tool selected";
	}

	@Override
	public void options() {
		ImGui.textDisabled("No tool selected. Click a tool above to use it; click it again to unselect it.");
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
	}
}
