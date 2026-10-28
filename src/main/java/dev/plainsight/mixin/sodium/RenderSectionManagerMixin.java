package dev.plainsight.mixin.sodium;

import dev.plainsight.editor.Editor;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium only draws sections within render distance of the camera: with the editor camera away from the
 * player, reach the chunks loaded around the player too (the fog is pushed out alike, see FogRendererMixin).
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class RenderSectionManagerMixin {
	@Inject(method = "getRenderDistance", at = @At("RETURN"), cancellable = true)
	private void plainsight$reachPlayer(CallbackInfoReturnable<Float> cir) {
		if (Editor.cameraToPlayer > 0) cir.setReturnValue(cir.getReturnValueF() + Editor.cameraToPlayer);
	}
}
