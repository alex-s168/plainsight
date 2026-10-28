package dev.plainsight.mixin.seedcracker;

import dev.plainsight.guess.SeedStore;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SeedcrackerX hands seeds to API users ({@link dev.plainsight.guess.SeedCrackerHook}) only when it cracks
 * them itself, not when it finds them in its online database; both are printed through
 * {@code Log.printSeed}, so world seeds are taken from there too (if they match the server's hashed seed).
 */
@Mixin(targets = "kaptainwutax.seedcrackerX.util.Log", remap = false)
public abstract class SeedLogMixin {
	@Inject(method = "printSeed(Ljava/lang/String;J)V", at = @At("HEAD"))
	private static void plainsight$worldSeed(String key, long seed, CallbackInfo ci) {
		if (key == null || !key.contains("foundWorldSeed")) return;
		Minecraft.getInstance().execute(() -> {
			if (SeedStore.matchesServer(seed).orElse(true)) SeedStore.fromSeedCracker(seed);
		});
	}
}
