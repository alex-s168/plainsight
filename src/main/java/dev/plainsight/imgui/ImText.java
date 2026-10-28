package dev.plainsight.imgui;

import imgui.type.ImString;

import java.nio.charset.StandardCharsets;

/** Text helpers for imgui-java's {@link ImString}. */
public final class ImText {
	private ImText() {
	}

	/**
	 * The text in the buffer up to ImGui's terminating zero. {@link ImString#get()} relies on a length that
	 * is not always updated by the native side, so a shorter new text can come back with the tail of the
	 * previous, longer one.
	 */
	public static String of(ImString s) {
		byte[] data = s.getData();
		int n = 0;
		while (n < data.length && data[n] != 0) n++;
		return new String(data, 0, n, StandardCharsets.UTF_8);
	}
}
