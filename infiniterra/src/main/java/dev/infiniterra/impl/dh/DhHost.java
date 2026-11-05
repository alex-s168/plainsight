package dev.infiniterra.impl.dh;

import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import dev.infiniterra.InfiniTerraMod;
import dev.infiniterra.api.SeedCheck;
import dev.infiniterra.impl.guess.SeedCheckImpl;
import dev.infiniterra.impl.guess.SeedServer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.OptionalLong;

/**
 * Distant Horizons with a known seed on a server: DH is made to run as in singleplayer, with the
 * worldgen server of the seed as its "integrated server" (the DH mixins answer DH's questions about
 * the singleplayer server with it). DH then builds the LOD itself, from the seed, around the player,
 * stored in the seed's world; the real chunks are mirrored into that world (WorldMirror), so DH shows
 * them where they are known. Always instead of a DH server's LOD; never in singleplayer (DH has the
 * real integrated server there). The guessed terrain shows in the normal game too.
 */
public final class DhHost {
	private static @Nullable String key;
	private static @Nullable SeedServer server;
	private static volatile @Nullable MinecraftServer hosting;
	/** The worldgen server's levels given to DH (one at a time, as the player enters a dimension: DH sets up
	 * levels on threads of their own and its world generator registry is not safe for several at once). */
	private static final java.util.Set<ServerLevel> loaded = new java.util.HashSet<>();

	private DhHost() {
	}

	/** The worldgen server DH runs on now, or null (DH as usual). */
	public static @Nullable MinecraftServer hosting() {
		return hosting;
	}

	/** The worldgen server's level of a dimension (DH's "server side" of a client level), or null. */
	public static @Nullable ServerLevelWrapper levelWrapper(String dimension) {
		MinecraftServer s = hosting;
		if (s == null) return null;
		for (ServerLevel level : s.getAllLevels())
			if (level.dimension().identifier().toString().equals(dimension)) return ServerLevelWrapper.getWrapper(level);
		return null;
	}

	/** Every tick (render thread): the world and seed in use. */
	public static void update(@Nullable String worldKey, OptionalLong seed) {
		if (!DhProbe.present()) return;
		Minecraft mc = Minecraft.getInstance();
		var check = SeedCheckImpl.result();
		boolean rejected = check != null && check.verdict() == SeedCheck.Verdict.MISMATCH;
		// only on servers: in singleplayer DH has the real world's integrated server
		boolean remote = mc.level != null && mc.getSingleplayerServer() == null && mc.getConnection() != null;
		String k = !remote || worldKey == null || seed.isEmpty() || rejected ? null : worldKey + "|" + seed.getAsLong();
		if (!java.util.Objects.equals(k, key)) {
			stop();
			key = k;
			if (k != null) server = SeedServer.acquire(seed.getAsLong());
		}
		if (server != null && hosting == null && server.serverNow() != null) start(server.serverNow());
		MinecraftServer s = hosting;
		if (s != null && mc.level != null) {
			ServerLevel level = s.getLevel(mc.level.dimension());
			if (level != null && loaded.add(level)) {
				try {
					ServerApi.INSTANCE.serverLevelLoadEvent(ServerLevelWrapper.getWrapper(level));
					// DH loads a client level as a side effect of the client's chunk updates, which came before
					// this world: the client level is paired with the worldgen level (its renderer) here
					var world = com.seibel.distanthorizons.core.api.internal.SharedApi.getAbstractDhWorld();
					if (world != null) world.getOrLoadLevel(com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper.getWrapper(mc.level));
				} catch (RuntimeException | LinkageError e) {
					InfiniTerraMod.LOG.error("Could not give Distant Horizons the worldgen level {}", level.dimension().identifier(), e);
				}
			}
		}
	}

	private static void start(MinecraftServer s) {
		try {
			// DH's own world for the server connection goes; the worldgen server's takes its place
			ClientApi.INSTANCE.onClientOnlyDisconnected();
			hosting = s;
			loaded.clear();
			ServerApi.INSTANCE.serverLoadEvent(false);
			InfiniTerraMod.LOG.info("Distant Horizons builds its LOD on the worldgen server of the seed");
		} catch (RuntimeException | LinkageError e) {
			InfiniTerraMod.LOG.error("Could not give Distant Horizons the worldgen server", e);
			hosting = null;
		}
	}

	private static void stop() {
		MinecraftServer s = hosting;
		if (s != null) {
			try {
				for (ServerLevel level : loaded) ServerApi.INSTANCE.serverLevelUnloadEvent(ServerLevelWrapper.getWrapper(level));
				ServerApi.INSTANCE.serverUnloadEvent();
			} catch (RuntimeException | LinkageError e) {
				InfiniTerraMod.LOG.warn("Could not take the worldgen server from Distant Horizons", e);
			}
			hosting = null;
			loaded.clear();
			// back to DH's own world for the server (if still connected)
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null && mc.getConnection() != null && mc.getSingleplayerServer() == null) ClientApi.INSTANCE.onClientOnlyConnected();
		}
		if (server != null) server.release();
		server = null;
		key = null;
	}

	/** Leaving the server: DH closes its world itself. */
	public static void disconnected() {
		hosting = null;
		loaded.clear();
		if (server != null) server.release();
		server = null;
		key = null;
	}
}
