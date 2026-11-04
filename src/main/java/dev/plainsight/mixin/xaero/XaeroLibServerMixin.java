package dev.plainsight.mixin.xaero;

import dev.plainsight.guess.SeedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Xaero's library takes every starting server as the game's own and stops the game's (singleplayer) one
 * when a second starts ("Multiple servers running at once is not supported!"): plainsight's worldgen
 * server is left out.
 */
@Mixin(targets = "xaero.lib.common.event.CommonEvents", remap = false)
public abstract class XaeroLibServerMixin {
	@Inject(method = "serverStarting", at = @At("HEAD"), cancellable = true, require = 0)
	private void plainsight$notWorldgen(MinecraftServer server, CallbackInfo ci) {
		if (SeedServer.isWorldgenServer(server)) ci.cancel();
	}
}
