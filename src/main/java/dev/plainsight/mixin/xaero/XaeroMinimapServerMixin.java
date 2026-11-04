package dev.plainsight.mixin.xaero;

import dev.infiniterra.api.WorldgenServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Xaero's Minimap sets up its server side for the game's server, not plainsight's worldgen server. */
@Mixin(targets = "xaero.common.events.CommonEvents", remap = false)
public abstract class XaeroMinimapServerMixin {
	@Inject(method = {"onServerStarting", "onServerStopped"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void plainsight$notWorldgen(MinecraftServer server, CallbackInfo ci) {
		if (WorldgenServer.isWorldgenServer(server)) ci.cancel();
	}
}
