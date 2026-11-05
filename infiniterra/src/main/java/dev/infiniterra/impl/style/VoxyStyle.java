package dev.infiniterra.impl.style;

/** The terrain style in Voxy: a model flag for fluids and the shader uniforms that apply the style. */
public final class VoxyStyle {
	private VoxyStyle() {
	}

	/**
	 * The bit in Voxy's model flags ({@code BlockModel.flagsA}: 2 biome colour, 4 translucent, 8 shaded) that
	 * marks fluid models (set by {@code ModelFluidFlagMixin}, tested by the patched shaders).
	 */
	public static final int FLUID_FLAG = 0x10000;

	/** Fluid models flagged so far, and whether the shader applies the fluid opacity (tests, warnings). */
	public static volatile int fluidModels;
	public static volatile boolean fluidAlphaPatched;

	/** For tests: "models patched". */
	public static String debug() {
		return fluidModels + " " + fluidAlphaPatched;
	}
}
