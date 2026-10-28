package dev.plainsight;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plainsight.editor.Editor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Plainsight implements ClientModInitializer {
	public static final String ID = "plainsight";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
	private static KeyMapping toggleEditor, overlaysInGame;

	public static KeyMapping toggleEditor() {
		return toggleEditor;
	}

	/** -Dplainsight.disabled=true turns the mod off (used by the Axiom study client). */
	public static final boolean DISABLED = Boolean.getBoolean("plainsight.disabled");

	@Override
	public void onInitializeClient() {
		if (DISABLED) {
			LOG.info("Plainsight disabled by -Dplainsight.disabled");
			return;
		}
		toggleEditor = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.editor", InputConstants.KEY_RSHIFT, CATEGORY));
		overlaysInGame = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.overlays_in_game", InputConstants.KEY_J, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (overlaysInGame.consumeClick()) Editor.get().toggleOverlaysInGame();
			guessedTerrainTick();
		});
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, context) -> dev.plainsight.guess.GuessCommands.register(dispatcher));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
				client -> dev.plainsight.guess.GuessWorld.shutdown());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Editor.get().onDisconnect()));
		LOG.info("Plainsight loaded");
	}

	/** Guessed terrain follows the world, the seed and the editor camera; Voxy shows it only in the editor. */
	private static void guessedTerrainTick() {
		dev.plainsight.guess.GuessWorld.reconcile();
		var world = dev.plainsight.guess.GuessWorld.current();
		Editor editor = Editor.get();
		boolean show = Editor.isActive() && editor.guessedTerrain.active();
		if (show) world.tick(editor.camera.pos.x, editor.camera.pos.z);
		dev.plainsight.guess.VoxyGuess.show(show);
	}
}
