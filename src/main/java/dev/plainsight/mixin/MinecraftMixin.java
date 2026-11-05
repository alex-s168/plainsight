package dev.plainsight.mixin;

import dev.plainsight.editor.Editor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Losing window focus while in the editor does not open the pause menu (#54). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void plainsight$noPauseInEditor(CallbackInfo ci) {
		if (Editor.isActive()) ci.cancel();
	}
}
