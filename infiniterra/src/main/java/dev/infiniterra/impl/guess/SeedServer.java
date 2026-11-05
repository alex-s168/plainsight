package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The private worldgen server of a seed ({@link GuessServer}), shared by what needs it (guessed terrain,
 * {@code /plainsight locate}): started on the first {@link #acquire}, stopped when the last user
 * {@link #release}s it. Acquire and release on the render thread; {@link #ready()} completes on a
 * background thread.
 */
public final class SeedServer {
	/** How seeds are written in the log (see {@link dev.infiniterra.api.WorldgenServer#setSeedFormat}). */
	public static volatile java.util.function.LongFunction<String> seedFormat = Long::toString;
	private static final Map<Long, SeedServer> BY_SEED = new HashMap<>();
	/** Servers still shutting down, by world folder: a new one on the same folder waits for them. */
	private static final Map<Path, Thread> STOPPING = new ConcurrentHashMap<>();

	public final long seed;
	private final Path dir;
	private final CompletableFuture<MinecraftServer> ready = new CompletableFuture<>();
	private int users;
	private volatile boolean closed;

	private SeedServer(long seed) {
		this.seed = seed;
		this.dir = dev.infiniterra.impl.DataDirs.worldgen(seed);
		Thread starter = new Thread(this::start, "InfiniTerra worldgen startup");
		starter.setDaemon(true);
		starter.start();
	}

	/** Whether {@code server} is one of InfiniTerra's worldgen servers (other mods' server hooks should skip it). */
	public static boolean isWorldgenServer(Object server) {
		return server instanceof GuessServer;
	}

	/** The name of a worldgen server's thread (set before it starts). */
	public static final String THREAD_NAME = "InfiniTerra worldgen server";

	/**
	 * Whether this thread is a worldgen server's: the game's server events (Fabric's lifecycle, level and
	 * chunk events) fire on it for these servers too, and other mods must not take them for the game's.
	 */
	public static boolean onWorldgenThread() {
		return THREAD_NAME.equals(Thread.currentThread().getName());
	}

	/** The seed's server (started if needed); a failed one is started anew. */
	public static SeedServer acquire(long seed) {
		SeedServer s = BY_SEED.get(seed);
		if (s == null || s.ready.isCompletedExceptionally()) {
			s = new SeedServer(seed);
			BY_SEED.put(seed, s);
		}
		s.users++;
		return s;
	}

	public void release() {
		if (--users > 0) return;
		if (BY_SEED.get(seed) == this) BY_SEED.remove(seed);
		closed = true;
		ready.thenAccept(this::stop);
	}

	/** Completes with the running server, or exceptionally if it could not be started. */
	public CompletableFuture<MinecraftServer> ready() {
		return ready;
	}

	/** The running server, or null while it starts (or if it failed). */
	public MinecraftServer serverNow() {
		return ready.isDone() && !ready.isCompletedExceptionally() ? ready.join() : null;
	}

	public boolean failed() {
		return ready.isCompletedExceptionally();
	}

	private void start() {
		try {
			Thread previous = STOPPING.get(dir);
			if (previous != null) previous.join(60_000);
			GuessServer s = startWithRetries();
			long deadline = System.currentTimeMillis() + 120_000;
			while (!s.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(50);
			if (!s.isReady()) throw new IllegalStateException("worldgen server did not start");
			InfiniTerraMod.LOG.info("Worldgen server for seed {} ready", seedFormat.apply(seed));
			ready.complete(s);
		} catch (Exception | LinkageError e) {
			InfiniTerraMod.LOG.error("Could not start the worldgen server for seed {}", seedFormat.apply(seed), e);
			ready.completeExceptionally(e);
		}
	}

	/**
	 * Starts the server, retrying a few times: its data pack load can collide with the game's own (when
	 * joining a world), and some mods' tag hooks fail when both run at once.
	 */
	private GuessServer startWithRetries() throws Exception {
		Exception last = null;
		for (int attempt = 0; attempt < 5 && !closed; attempt++) {
			if (attempt > 0) Thread.sleep(3000L * attempt);
			try {
				return GuessServer.start(dir, seed);
			} catch (Exception e) {
				last = e;
				InfiniTerraMod.LOG.warn("Worldgen server start failed (attempt {}), retrying: {}", attempt + 1, e.toString());
			}
		}
		throw last != null ? last : new IllegalStateException("closed while starting");
	}

	private void stop(MinecraftServer s) {
		Thread stopper = new Thread(() -> {
			try {
				s.halt(true);
			} finally {
				STOPPING.remove(dir, Thread.currentThread());
			}
		}, "InfiniTerra worldgen shutdown");
		stopper.setDaemon(true);
		STOPPING.put(dir, stopper);
		stopper.start();
	}
}
