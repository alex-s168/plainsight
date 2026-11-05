package dev.plainsight.devbridge.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.plainsight.devbridge.DevBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hidden (headless compositor) window never receives focus; injected input needs it to count. Tests can take it away with /focus. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void devbridge$alwaysFocused(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(DevBridge.focused);
	}
}
