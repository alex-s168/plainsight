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
	/** Servers still shutting down, by world folder: a new one on the same folder waits for them. */
	private static final java.util.Map<Path, Thread> STOPPING = new java.util.concurrent.ConcurrentHashMap<>();
	private static String currentKey;

	final String worldKey;
	final ResourceKey<Level> dimension;
	final long seed;
	private final Path stateDir, worldDir;
	private volatile State state = State.STARTING;
	private volatile String error;
	private volatile GuessServer server;
	/** Chunks that show guessed terrain, and chunks the client has really seen (which never show guesses). */
	private final LongOpenHashSet guessed = new LongOpenHashSet(), real = new LongOpenHashSet(), inFlight = new LongOpenHashSet();
	/** 2x2-chunk LOD columns already looked up in the normal LOD world this session. */
	private final LongOpenHashSet lodChecked = new LongOpenHashSet();
	/** Copies real LOD in the background (one column at a time; reads Voxy storage). */
	private final java.util.concurrent.ExecutorService lodCopier = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Plainsight LOD copy");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private volatile int version;
	private long lastSave = System.currentTimeMillis();
	private ChunkPos searchCentre;
	private int searchRing;
	public volatile int generatedThisSession, copiedThisSession, failed;
	private volatile boolean closed;

	private GuessWorld(String worldKey, ResourceKey<Level> dimension, long seed) {
		this.worldKey = worldKey;
		this.dimension = dimension;
		this.seed = seed;
		Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("plainsight/guess");
		this.stateDir = root.resolve("state").resolve(sanitize(worldKey)).resolve(sanitize(dimension.identifier().toString()) + "-" + Long.toHexString(seed));
		load(guessed, stateDir.resolve("guessed.bin"));
		load(real, stateDir.resolve("real.bin"));
		this.worldDir = root.resolve("worlds").resolve(Long.toHexString(seed));
		Thread starter = new Thread(() -> {
			try {
				Thread previous = STOPPING.get(worldDir);
				if (previous != null) previous.join(60_000);
				GuessServer s = GuessServer.start(worldDir, seed);
				long deadline = System.currentTimeMillis() + 120_000;
				while (!s.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(50);
				if (!s.isReady()) throw new IllegalStateException("worldgen server did not start");
				if (s.getLevel(dimension) == null) throw new IllegalStateException("no vanilla generator for " + dimension.identifier());
				synchronized (this) {
					if (!closed) {
						server = s;
						state = State.READY;
					}
				}
				if (closed) {
					stop(s);
					return;
				}
				Plainsight.LOG.info("Worldgen server for seed {} ready", SeedHider.show(seed));
			} catch (Exception | LinkageError e) {
				error = e.toString();
				state = State.FAILED;
				Plainsight.LOG.error("Could not start the worldgen server for seed {}", SeedHider.show(seed), e);
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
						int cx = c.x() + dx, cz = c.z() + dz;
						long k = ChunkPos.pack(cx, cz);
						long column = ChunkPos.pack(cx >> 1, cz >> 1);
						// done: real, or guessed with the normal LOD world already looked at for it
						if (real.contains(k) || (guessed.contains(k) && lodChecked.contains(column))) continue;
						if (!inFlight.contains(k)) request(cx, cz, k);
						ringDone = false;
					}
				}
				if (ringDone) searchRing++;
				else break;
			}
		}
		if (System.currentTimeMillis() - lastSave > 30_000) save();
	}

	/** Real LOD first (the normal LOD world), generation only where there is none. */
	private void request(int cx, int cz, long key) {
		long column = ChunkPos.pack(cx >> 1, cz >> 1);
		if (!lodChecked.contains(column)) {
			lodChecked.add(column);
			// the whole 2x2 column is in flight until the lookup is done
			for (int i = 0; i < 4; i++) inFlight.add(ChunkPos.pack((cx & ~1) + (i & 1), (cz & ~1) + (i >> 1)));
			lodCopier.execute(() -> {
				boolean copied = VoxyGuess.copyReal(cx, cz);
				synchronized (this) {
					for (int i = 0; i < 4; i++) {
						long k = ChunkPos.pack((cx & ~1) + (i & 1), (cz & ~1) + (i >> 1));
						inFlight.remove(k);
						if (copied && real.add(k)) {
							version++;
							copiedThisSession++;
						}
					}
				}
			});
			return;
		}
		if (guessed.contains(key)) return;
		generate(cx, cz, key);
	}

	private void generate(int cx, int cz, long key) {
		GuessServer s = server;
		if (s == null) return;
		inFlight.add(key);
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
		GuessServer s;
		synchronized (this) {
			closed = true;
			s = server;
			server = null;
		}
		save();
		lodCopier.shutdownNow();
		VoxyGuess.detach();
		if (s != null) stop(s);
	}

	private void stop(GuessServer s) {
		Thread stopper = new Thread(() -> {
			try {
				s.halt(true);
			} finally {
				STOPPING.remove(worldDir, Thread.currentThread());
			}
		}, "Plainsight worldgen shutdown");
		stopper.setDaemon(true);
		STOPPING.put(worldDir, stopper);
		stopper.start();
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
