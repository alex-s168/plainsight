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
	private static boolean modulesLoaded;
	private static KeyMapping toggleEditor, editorMap, overlaysInGame, minimap, minimapZoomIn, minimapZoomOut, minimapLayer;
	/** Numpad minus (no InputConstants name; "key.keyboard.keypad.subtract"). */
	private static final int KEY_NUMPAD_SUBTRACT = 86;
	/** Ctrl + the zoom keys in the game: the minimap's layer up and down (see {@link #minimapKeys}). */
	private static final dev.plainsight.minimap.HoldRepeat layerUp = layerRepeat(), layerDown = layerRepeat();

	/** Y level keys: one block per press; held, many blocks, faster over time. */
	public static dev.plainsight.minimap.HoldRepeat layerRepeat() {
		return new dev.plainsight.minimap.HoldRepeat(0.35, 8, 40, 160);
	}

	/** The minimap's zoom keys and the layer reset key (Ctrl + it): also the editor map's. */
	public static KeyMapping minimapZoomIn() {
		return minimapZoomIn;
	}

	public static KeyMapping minimapZoomOut() {
		return minimapZoomOut;
	}

	public static KeyMapping minimapLayer() {
		return minimapLayer;
	}

	private static boolean leftCtrl, rightCtrl;

	/**
	 * The zoom keys moved from = and - to numpad + and -: bindings still at the old defaults move along
	 * (once, see MinimapModule's settings).
	 */
	public static void moveZoomKeysToNumpad() {
		boolean changed = false;
		if (KeyMappingHelper.getBoundKeyOf(minimapZoomIn).getValue() == InputConstants.KEY_EQUALS) {
			minimapZoomIn.setKey(minimapZoomIn.getDefaultKey());
			changed = true;
		}
		if (KeyMappingHelper.getBoundKeyOf(minimapZoomOut).getValue() == InputConstants.KEY_MINUS) {
			minimapZoomOut.setKey(minimapZoomOut.getDefaultKey());
			changed = true;
		}
		if (!changed) return;
		KeyMapping.resetMapping();
		net.minecraft.client.Minecraft.getInstance().options.save();
		LOG.info("Minimap zoom keys moved to numpad + and -");
	}

	/** Whether a Ctrl key is held, from the key events (KeyboardHandlerMixin). */
	public static boolean ctrlDown() {
		return leftCtrl || rightCtrl;
	}

	public static void ctrlEvent(int key, int action) {
		if (key == InputConstants.KEY_LCONTROL) leftCtrl = action != 0;
		if (key == InputConstants.KEY_RCONTROL) rightCtrl = action != 0;
	}

	public static KeyMapping toggleEditor() {
		return toggleEditor;
	}

	/** Name of the map key (M by default), for hints. */
	public static String mapKeyName() {
		return editorMap == null ? "M" : KeyMappingHelper.getBoundKeyOf(editorMap).getDisplayName().getString();
	}

	/** Opens the editor on the map; in the editor it switches between the map and the 3D view. */
	public static KeyMapping editorMap() {
		return editorMap;
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
		editorMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.editor_map", InputConstants.KEY_M, CATEGORY));
		overlaysInGame = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.overlays_in_game", InputConstants.KEY_J, CATEGORY));
		minimap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.minimap", InputConstants.KEY_N, CATEGORY));
		minimapZoomIn = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.minimap_zoom_in", InputConstants.KEY_ADD, CATEGORY));
		minimapZoomOut = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.minimap_zoom_out", KEY_NUMPAD_SUBTRACT, CATEGORY));
		minimapLayer = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.plainsight.minimap_layer", InputConstants.KEY_MULTIPLY, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!modulesLoaded) {
				// once the game runs (the editor's modules touch the game when they are switched)
				modulesLoaded = true;
				dev.plainsight.module.ModuleConfig.load(Editor.get().allModules());
			}
			dev.plainsight.module.ModuleConfig.tick();
			while (overlaysInGame.consumeClick()) Editor.get().toggleOverlaysInGame();
			while (minimap.consumeClick()) dev.plainsight.minimap.Minimap.enabled = !dev.plainsight.minimap.Minimap.enabled;
			minimapKeys();
			guessedTerrainTick();
		});
		net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
				(dispatcher, context) -> dev.plainsight.guess.GuessCommands.register(dispatcher));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
				client -> {
					dev.plainsight.guess.LocateCommands.shutdown();
					if (modulesLoaded) dev.plainsight.module.ModuleConfig.save(false);
					dev.plainsight.compat.BaritoneShutdown.schedule();
				});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Editor.get().onDisconnect()));
		// InfiniTerra writes seeds in its log as plainsight shows them (hidden when seeds are hidden)
		dev.infiniterra.api.WorldgenServer.setSeedFormat(dev.plainsight.guess.SeedHider::show);
		dev.plainsight.minimap.Minimap.init();
		LOG.info("Plainsight loaded");
	}

	/** The minimap's keys in the game: zoom; with Ctrl the layer's Y level, and Ctrl + * resets it. */
	private static void minimapKeys() {
		int in = 0, out = 0, reset = 0;
		while (minimapZoomIn.consumeClick()) in++;
		while (minimapZoomOut.consumeClick()) out++;
		while (minimapLayer.consumeClick()) reset++;
		if (!ctrlDown()) {
			layerUp.reset();
			layerDown.reset();
			if (in - out != 0) dev.plainsight.minimap.Minimap.zoom(in - out);
			return;
		}
		int dy = layerUp.update(in, minimapZoomIn.isDown(), 0.05) - layerDown.update(out, minimapZoomOut.isDown(), 0.05);
		if (dy != 0) dev.plainsight.minimap.Minimap.moveLayer(dy);
		if (reset % 2 == 1) dev.plainsight.minimap.Minimap.toggleLayer();
	}

	/** Guessed terrain follows the world, the seed and the editor camera; Voxy shows it only in the editor. */
	private static void guessedTerrainTick() {
		Editor editor = Editor.get();
		var mc = net.minecraft.client.Minecraft.getInstance();
		dev.infiniterra.api.TerrainStyle.setFluidOpacity(dev.plainsight.module.FluidTransparency.isActive() ? dev.plainsight.module.FluidTransparency.opacity() : 1f);
		dev.infiniterra.api.TerrainGuess.setTint(dev.plainsight.module.GuessedTerrain.tintOpacity, dev.plainsight.module.GuessedTerrain.pulseSeconds);
		dev.infiniterra.api.TerrainGuess.update(mc.level == null ? null : dev.plainsight.guess.SeedStore.worldKey(),
				mc.level == null ? java.util.OptionalLong.empty() : dev.plainsight.guess.SeedStore.seed(),
				Editor.isActive() && editor.guessedTerrain.enabled(), editor.camera.pos.x, editor.camera.pos.z);
		// the bedrock of the chunks around does not fit the seed: stop using it, and say so
		var check = dev.infiniterra.api.SeedCheck.result();
		var seed = dev.plainsight.guess.SeedStore.seed();
		if (check != null && check.verdict() == dev.infiniterra.api.SeedCheck.Verdict.MISMATCH && seed.isPresent() && seed.getAsLong() == check.seed()) {
			dev.plainsight.guess.SeedStore.reject();
			mc.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("[Plainsight] Stopped using seed "
					+ dev.plainsight.guess.SeedHider.show(check.seed()) + ": the bedrock of " + check.mismatched() + " chunks here does not match it"
					+ " (" + check.matched() + " do), so it is probably not this world's seed. Guessed terrain and /plainsight locate are off;"
					+ " /plainsight seed <seed> sets one.").withStyle(net.minecraft.ChatFormatting.YELLOW));
		}
	}
}
