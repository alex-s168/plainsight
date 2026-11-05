package dev.infiniterra.api;

/**
 * How terrain is drawn, applied by InfiniTerra to every terrain source it renders as shader state (no
 * re-mesh when it changes). So far: fluid opacity in the LOD (Voxy).
 */
public final class TerrainStyle {
	private TerrainStyle() {
	}

	private static volatile float fluidOpacity = 1f;

	/**
	 * Opacity of fluids in the LOD: 1 as the LOD mod draws them, 0 hides them. Translucent fluids (water)
	 * take any value; opaque ones (lava) are drawn as they are above 0 and hidden at 0.
	 */
	public static void setFluidOpacity(float opacity) {
		fluidOpacity = Math.max(0f, Math.min(1f, opacity));
	}

	public static float fluidOpacity() {
		return fluidOpacity;
	}
}
