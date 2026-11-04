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

	/** The block of the LOD (most detailed level) at a position, or null where it knows nothing (Voxy only). */
	public static @Nullable BlockState block(int x, int y, int z) {
		return VoxyGuess.lodBlock(x, y, z);
	}
}
