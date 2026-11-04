package dev.infiniterra.api;

import dev.infiniterra.impl.guess.SeedServer;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongFunction;

/**
 * A private server that generates terrain for a world seed (no players; it is never ticked), shared by
 * everyone who holds it: guessed terrain, and hosts that need the seed's world (locating structures). It
 * stops when the last holder releases it.
 */
public final class WorldgenServer {
	private final SeedServer server;

	private WorldgenServer(SeedServer server) {
		this.server = server;
	}

	/** Holds the server for {@code seed} (starting it if needed); call {@link #release()} when done. */
	public static WorldgenServer acquire(long seed) {
		return new WorldgenServer(SeedServer.acquire(seed));
	}

	public long seed() {
		return server.seed;
	}

	/** Completes when the server is ready (or exceptionally when it could not start). */
	public CompletableFuture<MinecraftServer> ready() {
		return server.ready();
	}

	/** The server if it is ready now, else null. */
	public @Nullable MinecraftServer serverNow() {
		return server.serverNow();
	}

	public boolean failed() {
		return server.failed();
	}

	public void release() {
		server.release();
	}

	/** Whether {@code server} is one of these private servers (other mods' server hooks should leave it alone). */
	public static boolean isWorldgenServer(Object server) {
		return SeedServer.isWorldgenServer(server);
	}

	/** How seeds are written in InfiniTerra's log (e.g. masked when the host hides seeds). */
	public static void setSeedFormat(LongFunction<String> format) {
		SeedServer.seedFormat = format;
	}
}
