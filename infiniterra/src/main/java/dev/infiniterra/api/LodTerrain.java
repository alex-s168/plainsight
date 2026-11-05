package dev.infiniterra.api;

import dev.infiniterra.impl.LodDetect;
import dev.infiniterra.impl.guess.VoxyGuess;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** The LOD mod's terrain beyond the loaded chunks. */
public final class LodTerrain {
	private LodTerrain() {
	}

	/** Whether a level-of-detail mod is installed (Voxy or Distant Horizons). */
	public static boolean present() {
		return LodDetect.present();
	}

	/**
	 * Scales the LOD mod's render distance (Voxy: its configured LOD distance times this; 1 = as configured).
	 * Takes effect on the next frame and is not saved in the LOD mod's config.
	 */
	public static void setDistanceScale(float scale) {
		distanceScale = Math.max(0.25f, Math.min(MAX_DISTANCE_SCALE, scale));
	}

	/** Whether {@link #setDistanceScale} does anything (Voxy is installed). */
	public static boolean distanceScaleSupported() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy");
	}

	public static float distanceScale() {
		return distanceScale;
	}

	/** The LOD distance last given to the LOD mod (Voxy's units), NaN before its first frame (tests). */
	public static float appliedDistance() {
		return appliedDistance;
	}

	/** Set by the LOD mod's render system when it applies a distance. */
	public static volatile float appliedDistance = Float.NaN;

	/** The largest scale: the LOD's GPU memory and node count grow with the square of the distance. */
	public static final float MAX_DISTANCE_SCALE = 4f;
	private static volatile float distanceScale = 1f;

	/** The block of the LOD (most detailed level) at a position, or null where it knows nothing (Voxy only). */
	public static @Nullable BlockState block(int x, int y, int z) {
		return VoxyGuess.lodBlock(x, y, z);
	}
}
