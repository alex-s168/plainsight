package dev.plainsight.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.plainsight.editor.Editor;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The render-distance fog is measured from the camera: with the editor camera away from the player, push
 * it out by that distance so the chunks loaded around the player are not fogged (or, with Sodium, culled).
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	// before the return, where Sodium copies the fog for its terrain shader
	@Inject(method = "setupFog", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F",
			opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void plainsight$editorFog(CallbackInfoReturnable<FogData> cir, @Local FogData fog) {
		float d = Editor.cameraToPlayer;
		if (d <= 0) return;
		fog.renderDistanceStart += d;
		fog.renderDistanceEnd += d;
	}
}
