package dev.infiniterra.mixin.dh;

import dev.infiniterra.impl.guess.SeedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons takes every server's start, stop, levels and chunks for the game's (Fabric's server
 * events): a worldgen server starting replaced DH's world, and its stopping unloaded it. Its events are
 * skipped for those servers (they fire on the worldgen server's thread).
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ServerApi", remap = false)
public abstract class ServerApiMixin {
	@Inject(method = {"serverLoadEvent", "serverUnloadEvent", "serverLevelLoadEvent", "serverLevelUnloadEvent",
			"serverChunkLoadEvent", "serverChunkSaveEvent"}, at = @At("HEAD"), cancellable = true)
	private void infiniterra$notForWorldgenServers(CallbackInfo ci) {
		if (SeedServer.onWorldgenThread()) ci.cancel();
	}
}
