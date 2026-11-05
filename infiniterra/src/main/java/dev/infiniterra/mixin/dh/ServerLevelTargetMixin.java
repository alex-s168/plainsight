package dev.infiniterra.mixin.dh;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import dev.infiniterra.impl.dh.DhHost;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The worldgen server has no players: DH generates around the client's player instead (DhHost). */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.AbstractDhServerLevel", remap = false)
public abstract class ServerLevelTargetMixin {
	@Inject(method = "getTargetPosForGeneration", at = @At("HEAD"), cancellable = true)
	private void infiniterra$aroundThePlayer(CallbackInfoReturnable<DhBlockPos2D> cir) {
		var player = Minecraft.getInstance().player;
		if (DhHost.hosting() != null && player != null) cir.setReturnValue(new DhBlockPos2D((int) player.getX(), (int) player.getZ()));
	}
}
