package dev.plainsight.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block changes in loaded chunks reach Voxy's LOD (and so the minimap), see {@link dev.plainsight.minimap.Minimap#blockChanged}. */
@Mixin(ClientLevel.class)
public abstract class MinimapBlockChangeMixin {
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void plainsight$blockChanged(BlockPos pos, BlockState old, BlockState updated, int flags, CallbackInfo ci) {
		if (old != updated) dev.plainsight.minimap.Minimap.blockChanged(pos.getX() >> 4, pos.getZ() >> 4);
	}
}
