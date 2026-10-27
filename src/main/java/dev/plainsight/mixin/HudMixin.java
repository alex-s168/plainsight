package dev.plainsight.mixin;

import dev.plainsight.editor.Editor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The editor keeps chat and messages but hides the crosshair, hotbar and full-screen effects (the HUD is
 * drawn over the editor panels, so a vignette would darken them).
 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = {"extractCrosshair", "extractHotbarAndDecorations", "extractEffects", "extractVignette", "extractCameraOverlays"},
			at = @At("HEAD"), cancellable = true)
	private void plainsight$hide(CallbackInfo ci) {
		if (Editor.isActive()) ci.cancel();
	}
}
