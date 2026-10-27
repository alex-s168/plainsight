package dev.plainsight.mixin;

import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes mouse input to ImGui and the editor while it is open (and no game screen is). */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	private boolean plainsight$editorInput() {
		return Editor.isActive() && minecraft.gui.screen() == null && minecraft.gui.overlay() == null;
	}

	@Inject(method = "onMove", at = @At("TAIL"))
	private void plainsight$move(long handle, double x, double y, double xrel, double yrel, CallbackInfo ci) {
		if (!plainsight$editorInput() || handle != minecraft.getWindow().handle()) return;
		ImGuiLayer.get().mouseMove(x, y);
		Editor.get().onMouseMove(x, y);
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void plainsight$button(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
		if (!plainsight$editorInput() || handle != minecraft.getWindow().handle()) return;
		boolean down = action == 1;
		ImGuiLayer.get().mouseButton(info.button(), down);
		Editor.get().onMouseButton(info.button(), down);
		ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void plainsight$scroll(long handle, double dx, double dy, CallbackInfo ci) {
		if (!plainsight$editorInput() || handle != minecraft.getWindow().handle()) return;
		ImGuiLayer.get().scroll(dx, dy);
		Editor.get().onScroll(dy);
		ci.cancel();
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void plainsight$noGrab(CallbackInfo ci) {
		if (Editor.isActive()) ci.cancel();
	}

	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void plainsight$noTurn(double mousea, CallbackInfo ci) {
		if (Editor.isActive()) ci.cancel();
	}
}
