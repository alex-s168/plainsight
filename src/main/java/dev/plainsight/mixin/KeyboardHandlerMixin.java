package dev.plainsight.mixin;

import dev.plainsight.Plainsight;
import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The editor toggle key, and keyboard input for ImGui and the editor while it is open. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void plainsight$key(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (handle != minecraft.getWindow().handle() || minecraft.gui.screen() != null || minecraft.gui.overlay() != null) return;
		boolean active = Editor.isActive();
		if (action == 1 && Plainsight.toggleEditor().matches(event) && !(active && ImGuiLayer.get().wantsTextInput())) {
			Editor.get().toggle();
			ci.cancel();
			return;
		}
		if (!active) {
			if (action == 1 && Plainsight.editorMap().matches(event)) {
				Editor.get().openMap();
				ci.cancel();
			}
			return;
		}
		boolean down = action != 0;
		ImGuiLayer.get().key(event.key(), down);
		if (action == 2) {
			// repeats only matter to ImGui text fields
			ci.cancel();
			return;
		}
		if (Editor.get().onKey(event.key(), down)) ci.cancel();
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void plainsight$chars(long handle, CharacterEvent event, CallbackInfo ci) {
		if (!Editor.isActive() || minecraft.gui.screen() != null || handle != minecraft.getWindow().handle()) return;
		ImGuiLayer.get().chars(new String(Character.toChars(event.codepoint())));
		ci.cancel();
	}
}
