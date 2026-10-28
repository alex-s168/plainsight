package dev.plainsight.guess;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;
import net.minecraft.client.Minecraft;

/** SeedcrackerX entrypoint ({@code seedcrackerx} in fabric.mod.json): it calls this once it found the world seed. */
public final class SeedCrackerHook implements SeedCrackerAPI {
	@Override
	public void pushWorldSeed(long seed) {
		Minecraft.getInstance().execute(() -> SeedStore.set(seed, "SeedcrackerX"));
	}
}
