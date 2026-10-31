package dev.plainsight.mixin;

import dev.plainsight.render.HudInViewport;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes the picture-in-picture elements the HUD adds in the editor (see HudInViewport). */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
	@Inject(method = "addPicturesInPictureState", at = @At("HEAD"))
	private void plainsight$notePip(PictureInPictureRenderState state, CallbackInfo ci) {
		HudInViewport.notePictureInPicture(state);
	}
}
