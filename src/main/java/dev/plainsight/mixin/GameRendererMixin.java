package dev.plainsight.mixin;

import dev.plainsight.render.EditorRendering;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** World overlays, drawn once the level (including translucent terrain) is complete. */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void plainsight$overlays(CallbackInfo ci) {
		EditorRendering.afterLevel((GameRenderer) (Object) this);
	}

	/**
	 * With a shader pack the minimap renders through it as a frame of its own, before the level's (which then
	 * sets up all the state it needs again; see MinimapIris).
	 */
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void plainsight$minimapThroughPack(CallbackInfo ci) {
		if (dev.plainsight.minimap.Minimap.visible())
			dev.plainsight.minimap.MinimapVoxy.throughPack(net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	/** The editor UI goes below Minecraft's screens (chat, menus) but above the world. */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void plainsight$ui(CallbackInfo ci) {
		EditorRendering.ui((GameRenderer) (Object) this);
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void plainsight$noHand(CallbackInfo ci) {
		if (dev.plainsight.editor.Editor.isActive()) ci.cancel();
	}
}
