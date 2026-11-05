package dev.infiniterra.mixin.dh;

import dev.infiniterra.impl.dh.DhHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While DH runs on the worldgen server (DhHost), DH's "is there a singleplayer server" is yes, and its levels are that server's. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper", remap = false)
public abstract class MinecraftClientWrapperMixin {
	@Inject(method = "hasSinglePlayerServer", at = @At("HEAD"), cancellable = true)
	private void infiniterra$worldgenServer(CallbackInfoReturnable<Boolean> cir) {
		if (DhHost.hosting() != null) cir.setReturnValue(true);
	}

	@Inject(method = "getLevelWrapper", at = @At("HEAD"), cancellable = true)
	private void infiniterra$worldgenLevel(String dimension, CallbackInfoReturnable<Object> cir) {
		if (DhHost.hosting() != null) cir.setReturnValue(DhHost.levelWrapper(dimension));
	}
}
