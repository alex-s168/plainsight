package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.render.HudInViewport;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** In the editor the HUD and the debug screen (F3) go into the viewport, not over the panels. */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@org.spongepowered.asm.mixin.injection.Inject(method = "extractRenderState", at = @At("HEAD"))
	private void plainsight$newFrame(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		HudInViewport.newFrame();
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Hud;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
	private void plainsight$hudInViewport(Hud hud, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		HudInViewport.extract(graphics, () -> original.call(hud, graphics, deltaTracker));
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Hud;extractDebugOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
	private void plainsight$debugInViewport(Hud hud, GuiGraphicsExtractor graphics, Operation<Void> original) {
		HudInViewport.extract(graphics, () -> original.call(hud, graphics));
	}
}
