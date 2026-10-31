package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.render.HudInViewport;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The HUD's picture-in-picture elements are blitted into the editor's viewport (see HudInViewport). */
@Mixin(PictureInPictureRenderer.class)
public abstract class PictureInPictureRendererMixin {
	@WrapOperation(method = "blitTexture", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;pose()Lorg/joml/Matrix3x2fc;"))
	private Matrix3x2fc plainsight$pose(PictureInPictureRenderState state, Operation<Matrix3x2fc> original) {
		return HudInViewport.pose(state, original.call(state));
	}

	@WrapOperation(method = "blitTexture", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/state/gui/pip/PictureInPictureRenderState;scissorArea()Lnet/minecraft/client/gui/navigation/ScreenRectangle;"))
	private ScreenRectangle plainsight$scissor(PictureInPictureRenderState state, Operation<ScreenRectangle> original) {
		return HudInViewport.scissor(state, original.call(state));
	}
}
