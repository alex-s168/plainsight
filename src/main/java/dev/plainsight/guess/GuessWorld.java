package dev.plainsight.guess;

import dev.plainsight.Plainsight;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Terrain guessed from the world seed: a private {@link GuessServer} generates chunks around the editor
 * camera with vanilla worldgen and hands them to Voxy, into a separate LOD world that the editor shows
 * instead of the normal one (see {@link VoxyGuess}). Real chunks the client receives are copied into
 * that LOD world too and always win, so it is real wherever the player has been and guessed elsewhere;
 * {@link #isGuessed} tells which chunks still show guesses (marked on screen by the guess tint).
 * <p>
 * Everything here runs on the render thread except the chunk callbacks, which run on the server thread.
 */
public final class GuessWorld {
	public enum State {STARTING, READY, FAILED}

	/** How far around the editor camera chunks are generated, in chunks. */
	public static volatile int radius = 32;
	/** Chunks requested from the server at the same time. */
	private static final int IN_FLIGHT = 24;

	private static GuessWorld current;
	private static String currentKey;

	final String worldKey;
	final ResourceKey<Level> dimension;
	final long seed;
	private final Path stateDir;
	private volatile State state = State.STARTING;
	private volatile String error;
	private volatile GuessServer server;
	/** Chunks that show guessed terrain, and chunks the client has really seen (which never show guesses). */
	private final LongOpenHashSet guessed = new LongOpenHashSet(), real = new LongOpenHashSet(), inFlight = new LongOpenHashSet();
	private volatile int version;
	private long lastSave = System.currentTimeMillis();
	private ChunkPos searchCentre;
	private int searchRing;
	public volatile int generatedThisSession, failed;

	private GuessWorld(String worldKey, ResourceKey<Level> dimension, long seed) {
		this.worldKey = worldKey;
		this.dimension = dimension;
		this.seed = seed;
		Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("plainsight/guess");
		this.stateDir = root.resolve("state").resolve(sanitize(worldKey)).resolve(sanitize(dimension.identifier().toString()) + "-" + Long.toHexString(seed));
		load(guessed, stateDir.resolve("guessed.bin"));
		load(real, stateDir.resolve("real.bin"));
		Path worldDir = root.resolve("worlds").resolve(Long.toHexString(seed));
		Thread starter = new Thread(() -> {
			try {
				GuessServer s = GuessServer.start(worldDir, seed);
				long deadline = System.currentTimeMillis() + 120_000;
				while (!s.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(50);
				if (!s.isReady()) throw new IllegalStateException("worldgen server did not start");
				if (s.getLevel(dimension) == null) throw new IllegalStateException("no vanilla generator for " + dimension.identifier());
				server = s;
				state = State.READY;
				Plainsight.LOG.info("Worldgen server for seed {} ready ({})", seed, worldDir);
			} catch (Exception | LinkageError e) {
				error = e.toString();
				state = State.FAILED;
				Plainsight.LOG.error("Could not start the worldgen server for seed {}", seed, e);
			}
		}, "Plainsight worldgen startup");
		starter.setDaemon(true);
		starter.start();
	}

	private static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	public static GuessWorld current() {
		return current;
	}

	/** Whether guessed terrain can be shown at all (Voxy renders it). */
	public static boolean supported() {
		return VoxyGuess.PRESENT;
	}

	/** Starts, switches or stops the guessed world to match the current world, dimension and seed. Render thread. */
	public static void reconcile() {
		Minecraft mc = Minecraft.getInstance();
		OptionalLong seed = mc.level == null ? OptionalLong.empty() : SeedStore.seed();
		String key = seed.isEmpty() || !supported() ? null
				: SeedStore.worldKey() + "|" + mc.level.dimension().identifier() + "|" + seed.getAsLong();
		if (java.util.Objects.equals(key, currentKey)) return;
		if (current != null) current.close();
		current = null;
		currentKey = key;
		if (key != null) {
			current = new GuessWorld(SeedStore.worldKey(), mc.level.dimension(), seed.getAsLong());
			VoxyGuess.attach(current);
		}
	}

	public State state() {
		return state;
	}

	public String error() {
		return error;
	}

	public synchronized int guessedCount() {
		return guessed.size();
	}

	public synchronized int realCount() {
		return real.size();
	}

	public synchronized int pending() {
		return inFlight.size();
	}

	public int version() {
		return version;
	}

	public synchronized boolean isGuessed(int cx, int cz) {
		long k = ChunkPos.pack(cx, cz);
		return guessed.contains(k) && !real.contains(k);
	}

	/** A real chunk reached the LOD world: it never shows guesses again. Any thread. */
	synchronized void markReal(int cx, int cz) {
		if (real.add(ChunkPos.pack(cx, cz))) version++;
	}

	/** Requests chunks around {@code centre} (block coordinates) while the editor shows guessed terrain. */
	public void tick(double x, double z) {
		if (state != State.READY) return;
		ChunkPos c = new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
		if (!c.equals(searchCentre)) {
			searchCentre = c;
			searchRing = 0;
		}
		int r = radius;
		synchronized (this) {
			// nearest first: walk rings outwards, remembering the first one that still has work
			while (inFlight.size() < IN_FLIGHT && searchRing <= r) {
				boolean ringDone = true;
				int ring = searchRing;
				for (int dx = -ring; dx <= ring && inFlight.size() < IN_FLIGHT; dx++) {
					for (int dz = -ring; dz <= ring && inFlight.size() < IN_FLIGHT; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
						long k = ChunkPos.pack(c.x() + dx, c.z() + dz);
						if (guessed.contains(k) || real.contains(k)) continue;
						if (!inFlight.contains(k)) request(c.x() + dx, c.z() + dz, k);
						ringDone = false;
					}
				}
				if (ringDone) searchRing++;
				else break;
			}
		}
		if (System.currentTimeMillis() - lastSave > 30_000) save();
	}

	private void request(int cx, int cz, long key) {
		inFlight.add(key);
		GuessServer s = server;
		s.execute(() -> {
			ServerLevel level = s.getLevel(dimension);
			CompletableFuture<?> f = level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true).whenCompleteAsync((result, err) -> {
				boolean ok = false;
				if (err == null && result.orElse(null) instanceof LevelChunk chunk) {
					boolean isReal;
					synchronized (this) {
						isReal = real.contains(key);
					}
					if (!isReal && !VoxyGuess.ingestGuessed(chunk)) failed++;
					// done either way: a chunk Voxy refused is not asked for again
					ok = true;
				} else if (err != null) {
					failed++;
				}
				synchronized (this) {
					inFlight.remove(key);
					if (ok && guessed.add(key)) {
						version++;
						generatedThisSession++;
					}
				}
			}, s);
		});
	}

	private void close() {
		save();
		VoxyGuess.detach();
		GuessServer s = server;
		if (s != null) {
			Thread stopper = new Thread(() -> s.halt(true), "Plainsight worldgen shutdown");
			stopper.setDaemon(true);
			stopper.start();
		}
		server = null;
	}

	/** Stops everything (leaving a world, quitting). */
	public static void shutdown() {
		if (current != null) current.close();
		current = null;
		currentKey = null;
	}

	private synchronized void save() {
		lastSave = System.currentTimeMillis();
		try {
			Files.createDirectories(stateDir);
			write(guessed, stateDir.resolve("guessed.bin"));
			write(real, stateDir.resolve("real.bin"));
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not save guessed-terrain state to {}", stateDir, e);
		}
	}

	private static void write(LongOpenHashSet set, Path file) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(set.size());
			for (LongIterator it = set.iterator(); it.hasNext(); ) out.writeLong(it.nextLong());
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}

	private static void load(LongOpenHashSet set, Path file) {
		if (!Files.exists(file)) return;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) set.add(in.readLong());
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not read {}", file, e);
		}
	}

	/** Fills {@code out} (size×size, row-major by z then x) with 1 where chunks around (originX, originZ) are guessed. */
	public synchronized void fillMask(int originX, int originZ, int size, byte[] out) {
		java.util.Arrays.fill(out, (byte) 0);
		for (LongIterator it = guessed.iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			if (real.contains(k)) continue;
			int x = ChunkPos.getX(k) - originX, z = ChunkPos.getZ(k) - originZ;
			if (x >= 0 && z >= 0 && x < size && z < size) out[z * size + x] = (byte) 255;
		}
	}
}
