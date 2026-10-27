package dev.plainsight.render;

/**
 * Per-thread vertex alpha override. While set, quads land in translucent layers/render types and every
 * vertex written to a {@code BufferBuilder} gets its alpha scaled. Used for cut-away blocks (chunk
 * compile threads) and translucent block overlays (render thread). It acts on the buffers rather than
 * on quad outputs because renderers may replace vanilla tesselation (Fabric's renderer API does).
 */
public final class AlphaOverride {
	private static final ThreadLocal<int[]> ALPHA = ThreadLocal.withInitial(() -> new int[]{-1});
	/** Fast path: while no feature can use an override, vertex writes skip the thread-local entirely. */
	public static volatile boolean cutActive, overlayActive;

	private AlphaOverride() {
	}

	public static void begin(int alpha) {
		ALPHA.get()[0] = alpha;
	}

	public static void end() {
		ALPHA.get()[0] = -1;
	}

	/** Alpha (0..255) for what is being tesselated on this thread, or -1. */
	public static int alpha() {
		return cutActive || overlayActive ? ALPHA.get()[0] : -1;
	}

	public static int apply(int argb) {
		int a = alpha();
		if (a < 0) return argb;
		return ((argb >>> 24) * a / 255) << 24 | (argb & 0xFFFFFF);
	}
}
