package dev.infiniterra.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes in loaded chunks reach Voxy's LOD (and so the camera pass), see {@link dev.infiniterra.impl.pass.Passes#blockChanged}. */
@Mixin(ClientLevel.class)
public abstract class BlockChangeMixin {
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void infiniterra$blockChanged(BlockPos pos, BlockState old, BlockState updated, int flags, CallbackInfo ci) {
		if (old != updated) dev.infiniterra.impl.pass.Passes.blockChanged(pos.getX() >> 4, pos.getZ() >> 4);
	}
}
