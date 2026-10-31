package dev.plainsight.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.plainsight.render.HudInViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the HUD is laid out in the editor's viewport, the GUI is as large as the viewport (see HudInViewport). */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "getGuiScaledWidth", at = @At("HEAD"), cancellable = true)
	private void plainsight$viewportWidth(CallbackInfoReturnable<Integer> cir) {
		if (HudInViewport.active) cir.setReturnValue(HudInViewport.width);
	}

	@Inject(method = "getGuiScaledHeight", at = @At("HEAD"), cancellable = true)
	private void plainsight$viewportHeight(CallbackInfoReturnable<Integer> cir) {
		if (HudInViewport.active) cir.setReturnValue(HudInViewport.height);
	}
}
