package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.command.ChatAlias;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chat messages for the {@code #ps} alias ({@link ChatAlias}) run it instead of going out as chat, before
 * Baritone (which catches {@code #} messages further down, when they are sent) sees them. They still go
 * into the chat history as typed.
 */
@Mixin(value = ChatScreen.class, priority = 900)
public abstract class ChatScreenMixin {
	@WrapOperation(method = "handleChatInput", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendChat(Ljava/lang/String;)V"))
	private void plainsight$chatAlias(ClientPacketListener connection, String message, Operation<Void> original) {
		if (ChatAlias.matches(message) && ChatAlias.dispatcher() != null) ChatAlias.execute(message);
		else original.call(connection, message);
	}
}
