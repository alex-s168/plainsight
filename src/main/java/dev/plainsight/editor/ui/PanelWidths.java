package dev.plainsight.editor.ui;

/** Limits for the widths of the editor's side panels, which the user drags at their inner edges. */
final class PanelWidths {
	static final float MIN_PANEL = 180, MIN_VIEWPORT = 240;

	private PanelWidths() {
	}

	/** The narrowest a panel gets in a window {@code width} wide (less than MIN_PANEL in tiny windows). */
	static float min(float width) {
		return Math.min(MIN_PANEL, Math.max(0, width - MIN_VIEWPORT) / 2);
	}

	/** A dragged panel's width: at least {@link #min}, at most what leaves the viewport MIN_VIEWPORT next to the other panel. */
	static float dragged(float wanted, float other, float width) {
		float min = min(width);
		return Math.max(min, Math.min(wanted, width - MIN_VIEWPORT - other));
	}

	/**
	 * Both widths fitted to the window: each at least {@link #min}; when they leave the viewport less than
	 * MIN_VIEWPORT, both give up room in proportion to what they have above the minimum.
	 */
	static float[] fit(float left, float right, float width) {
		float min = min(width), room = Math.max(2 * min, width - MIN_VIEWPORT);
		left = Math.max(min, left);
		right = Math.max(min, right);
		float excess = left + right - room;
		if (excess > 0) {
			float l = left - min, r = right - min;
			left -= excess * l / (l + r);
			right -= excess * r / (l + r);
		}
		return new float[]{left, right};
	}
}
