package dev.plainsight.mixin.voxy;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The minimap pass gets its own Voxy viewport (occlusion buffers, frame state), like Vivecraft's second eye. */
@Mixin(value = ViewportSelector.class, remap = false)
public abstract class MinimapViewportMixin {
	@Unique
	private static final Object PLAINSIGHT_MINIMAP = new Object();

	@Shadow
	private Viewport getOrCreate(Object holder) {
		throw new AssertionError();
	}

	@Inject(method = "getViewport", at = @At("HEAD"), cancellable = true)
	private void plainsight$minimapViewport(CallbackInfoReturnable<Viewport> cir) {
		if (dev.plainsight.minimap.MinimapVoxy.inPass) cir.setReturnValue(getOrCreate(PLAINSIGHT_MINIMAP));
	}
}
