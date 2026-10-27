package dev.plainsight;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plainsight.editor.Editor;
import dev.plainsight.render.BlockOverlayRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Plainsight implements ClientModInitializer {
	public static final String ID = "plainsight";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
	private static KeyMapping toggleEditor;

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
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
				BlockOverlayRenderer.submit(ctx.submitNodeCollector(), ctx.poseStack(), ctx.levelState().cameraRenderState.pos));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> Editor.get().onDisconnect()));
		LOG.info("Plainsight loaded");
	}
}
