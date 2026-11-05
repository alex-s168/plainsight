package dev.infiniterra;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * InfiniTerra: terrain beyond the loaded chunks (an LOD mod's, and terrain guessed from the world seed),
 * views of it from other cameras, and the LOD and rendering mods' interop. A library: it shows nothing by
 * itself; mods drive it through {@code dev.infiniterra.api}.
 */
public final class InfiniTerraMod implements ClientModInitializer {
	public static final String ID = "infiniterra";
	public static final Logger LOG = LoggerFactory.getLogger("InfiniTerra");
	/** -Dinfiniterra.disabled=true turns the mod off (used by the Axiom study client). */
	public static final boolean DISABLED = Boolean.getBoolean("infiniterra.disabled");

	@Override
	public void onInitializeClient() {
		if (DISABLED) return;
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> dev.infiniterra.impl.guess.GuessWorld.shutdown());
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			dev.infiniterra.impl.guess.SeedCheckImpl.loaded(chunk);
			dev.infiniterra.impl.guess.WorldMirror.loaded(chunk);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			dev.infiniterra.impl.guess.GuessWorld.shutdown();
			if (dev.infiniterra.impl.dh.DhProbe.present()) dev.infiniterra.impl.dh.DhHost.disconnected();
		}));
	}
}
