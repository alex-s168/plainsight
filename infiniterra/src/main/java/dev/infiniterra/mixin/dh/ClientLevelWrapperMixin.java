package dev.infiniterra.mixin.dh;

import dev.infiniterra.impl.dh.DhHost;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While DH runs on the worldgen server (DhHost), a client level's server side is that server's level of the dimension. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper", remap = false)
public abstract class ClientLevelWrapperMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "tryGetServerSideWrapper", at = @At("HEAD"), cancellable = true)
	private void infiniterra$worldgenLevel(CallbackInfoReturnable<Object> cir) {
		if (DhHost.hosting() != null) cir.setReturnValue(DhHost.levelWrapper(level.dimension().identifier().toString()));
	}
}
