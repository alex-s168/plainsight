package dev.plainsight.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.plainsight.editor.Editor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	/**
	 * The paused editor renders at most {@link Editor#pausedFps} frames a second, like vanilla's menus
	 * outside a world: behind the pause menu nothing changes, but the editor's frame (level, map pass, menu
	 * blur) is far more work than the menu (#53).
	 */
	@Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
	private void plainsight$pausedEditor(CallbackInfoReturnable<Integer> cir) {
		int max = Editor.pausedFps;
		if (max > 0 && Editor.isActive() && Minecraft.getInstance().isPaused() && cir.getReturnValueI() > max) cir.setReturnValue(max);
	}
}
