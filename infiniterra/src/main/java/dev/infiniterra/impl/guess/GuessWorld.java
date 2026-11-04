package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
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
	/** The seed's worldgen server (shared with /plainsight locate). */
	private final SeedServer seedServer;
	private volatile State state = State.STARTING;
	private volatile String error;
	private volatile net.minecraft.server.MinecraftServer server;
	/** Chunks that show guessed terrain, and chunks the client has really seen (which never show guesses). */
	private final LongOpenHashSet guessed = new LongOpenHashSet(), real = new LongOpenHashSet(), inFlight = new LongOpenHashSet();
	/** 2x2-chunk LOD columns already looked up in the normal LOD world. Kept between sessions. */
	private final LongOpenHashSet columns = new LongOpenHashSet();
	/** Per chunk, which of its 16-block sections real data was copied for (generation leaves those alone). Kept between sessions. */
	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap realSections = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	public volatile int importTotal, imported;
	/** Whether Voxy renders this world now (set from the render thread). */
	volatile boolean showing;
	/** Set when Voxy should rebuild its view of this world from storage (render thread does it). */
	public volatile boolean reloadView;
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
		// v5: which sections are real is known per chunk and 16-block section (v3 had it per 32-block LOD
		// section, which left holes where only part of one was real; v4 copied air Voxy had no data for on
		// top of terrain)
		load(guessed, stateDir.resolve("guessed-v5.bin"));
		load(real, stateDir.resolve("real-v5.bin"));
		load(columns, stateDir.resolve("columns-v5.bin"));
		loadMap(realSections, stateDir.resolve("sections-v5.bin"));
		this.seedServer = SeedServer.acquire(seed);
		seedServer.ready().whenComplete((s, e) -> {
			if (e != null) {
				error = e.toString();
				state = State.FAILED;
				return;
			}
			if (s.getLevel(dimension) == null) {
				error = "no vanilla generator for " + dimension.identifier();
				state = State.FAILED;
				InfiniTerraMod.LOG.warn("Guessed terrain: {}", error);
				return;
			}
			synchronized (this) {
				if (closed) return;
				server = s;
				state = State.READY;
			}
			var player = Minecraft.getInstance().player;
			importAll(player != null ? player.getX() : 0, player != null ? player.getZ() : 0);
		});
	}

	/** Starts over after a failed start (settings button). Render thread. */
	public static void retry() {
		if (current != null && current.state == State.FAILED) {
			current.close();
			current = null;
			currentKey = null;
		}
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

	/** Starts, switches or stops the guessed world to match the world, its current dimension and seed. Render thread. */
	public static void reconcile(String worldKey, OptionalLong seed) {
		Minecraft mc = Minecraft.getInstance();
		String key = worldKey == null || mc.level == null || seed.isEmpty() || !supported() || !VoxyGuess.running() ? null
				: worldKey + "|" + mc.level.dimension().identifier() + "|" + seed.getAsLong();
		if (java.util.Objects.equals(key, currentKey)) return;
		if (current != null) current.close();
		current = null;
		currentKey = key;
		if (key != null) {
			current = new GuessWorld(worldKey, mc.level.dimension(), seed.getAsLong());
			VoxyGuess.attach(current);
		}
	}

	/** This guess as the API sees it. */
	public dev.infiniterra.api.TerrainGuess.Status status() {
		GuessWorld w = this;
		return new dev.infiniterra.api.TerrainGuess.Status() {
			public dev.infiniterra.api.TerrainGuess.State state() {
				return dev.infiniterra.api.TerrainGuess.State.valueOf(w.state.name());
			}

			public String error() {
				return w.error;
			}

			public int guessedCount() {
				return w.guessedCount();
			}

			public int realCount() {
				return w.realCount();
			}

			public int pending() {
				return w.pending();
			}

			public int imported() {
				return w.imported;
			}

			public int importTotal() {
				return w.importTotal;
			}

			public int copiedThisSession() {
				return w.copiedThisSession;
			}

			public int failedCount() {
				return w.failed;
			}

			public boolean isGuessed(int cx, int cz) {
				return w.isGuessed(cx, cz);
			}

			public boolean isReal(int cx, int cz) {
				return w.isReal(cx, cz);
			}

			public void reloadView() {
				w.reloadView = true;
			}
		};
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

	public synchronized boolean isReal(int cx, int cz) {
		return real.contains(ChunkPos.pack(cx, cz));
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
						// done: generated (filled in around any real LOD), with the normal LOD world looked at for it
						if (guessed.contains(k) && columns.contains(column)) continue;
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

	/**
	 * Works per 2x2-chunk column (one 32-block LOD column): first the real LOD Voxy has there is copied,
	 * then the chunks are generated and fill in only the sections without real data. A chunk with any
	 * real data shows untinted.
	 */
	private void request(int cx, int cz, long key) {
		int x0 = cx & ~1, z0 = cz & ~1;
		long column = ChunkPos.pack(cx >> 1, cz >> 1);
		long[] keys = new long[4];
		for (int i = 0; i < 4; i++) {
			keys[i] = ChunkPos.pack(x0 + (i & 1), z0 + (i >> 1));
			if (inFlight.contains(keys[i])) return;
		}
		for (long k : keys) inFlight.add(k);
		boolean check = columns.add(column);
		long[] known = new long[4];
		for (int i = 0; i < 4; i++) known[i] = realSections.get(keys[i]);
		lodCopier.execute(() -> {
			long[] masks = check ? copyColumn(cx >> 1, cz >> 1) : known;
			for (int i = 0; i < 4; i++) {
				long k = keys[i];
				boolean skip;
				synchronized (this) {
					// generated before: nothing to fill in (real data copied now replaces what it covers)
					skip = guessed.contains(k);
					if (check && masks[i] != 0 && real.add(k)) {
						version++;
						copiedThisSession++;
					}
				}
				if (skip) {
					synchronized (this) {
						inFlight.remove(k);
					}
				} else {
					generate(x0 + (i & 1), z0 + (i >> 1), k, masks[i]);
				}
			}
		});
	}

	/** Copies one column's real LOD (if any) and remembers which sections of each chunk it covered. Worker threads. */
	private long[] copyColumn(int colX, int colZ) {
		long[] masks = VoxyGuess.copyReal(colX << 1, colZ << 1);
		synchronized (this) {
			columns.add(ChunkPos.pack(colX, colZ));
			for (int i = 0; i < 4; i++) {
				long k = ChunkPos.pack(colX * 2 + (i & 1), colZ * 2 + (i >> 1));
				if (masks[i] != 0) realSections.put(k, masks[i]);
				else realSections.remove(k);
			}
		}
		return masks;
	}

	/**
	 * Copies all of the normal LOD world (all LOD Voxy ever gathered here) into the guessed one in the
	 * background, nearest to (x, z) first; columns done in earlier sessions are skipped.
	 */
	private void importAll(double x, double z) {
		Thread t = new Thread(() -> {
			LongOpenHashSet found = new LongOpenHashSet();
			VoxyGuess.realColumns(found::add);
			long[] todo;
			synchronized (this) {
				todo = found.longStream().filter(c -> !columns.contains(c)).toArray();
			}
			int ox = (int) Math.floor(x) >> 5, oz = (int) Math.floor(z) >> 5;
			todo = java.util.Arrays.stream(todo).boxed()
					.sorted(java.util.Comparator.comparingLong(c -> {
						long dx = ChunkPos.getX(c) - ox, dz = ChunkPos.getZ(c) - oz;
						return dx * dx + dz * dz;
					})).mapToLong(Long::longValue).toArray();
			importTotal = todo.length;
			for (long c : todo) {
				if (closed) return;
				synchronized (this) {
					if (columns.contains(c)) {
						imported++;
						continue;
					}
				}
				// while Voxy renders the guessed world it must keep up with every change (it drops updates
				// it gets too fast, leaving holes): slowly then, at full speed while it shows the normal one
				if (showing) {
					try {
						Thread.sleep(25);
					} catch (InterruptedException e) {
						return;
					}
				}
				int colX = ChunkPos.getX(c), colZ = ChunkPos.getZ(c);
				long[] masks = copyColumn(colX, colZ);
				synchronized (this) {
					boolean any = false;
					for (int i = 0; i < 4; i++) {
						if (masks[i] == 0) continue;
						any = true;
						if (real.add(ChunkPos.pack(colX * 2 + (i & 1), colZ * 2 + (i >> 1)))) version++;
					}
					if (any) copiedThisSession++;
				}
				imported++;
			}
			InfiniTerraMod.LOG.info("Copied {} LOD columns into the guessed world", imported);
			save();
			// whatever Voxy missed meanwhile: it re-reads the world once
			if (todo.length > 0) reloadView = true;
		}, "Plainsight LOD import");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private void generate(int cx, int cz, long key, long realSections) {
		var s = server;
		if (s == null) {
			synchronized (this) {
				inFlight.remove(key);
			}
			return;
		}
		s.execute(() -> {
			ServerLevel level = s.getLevel(dimension);
			level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true).whenCompleteAsync((result, err) -> {
				boolean ok = false;
				if (err == null && result.orElse(null) instanceof LevelChunk chunk) {
					if (!VoxyGuess.ingestGuessed(chunk, realSections)) failed++;
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
		synchronized (this) {
			closed = true;
			server = null;
		}
		save();
		lodCopier.shutdownNow();
		try {
			// a copy in progress holds references to Voxy's worlds
			lodCopier.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		VoxyGuess.detach();
		seedServer.release();
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
			write(guessed, stateDir.resolve("guessed-v5.bin"));
			write(real, stateDir.resolve("real-v5.bin"));
			write(columns, stateDir.resolve("columns-v5.bin"));
			writeMap(realSections, stateDir.resolve("sections-v5.bin"));
		} catch (IOException e) {
			InfiniTerraMod.LOG.warn("Could not save guessed-terrain state to {}", stateDir, e);
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

	private static void writeMap(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map, Path file) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
			out.writeInt(map.size());
			for (var e : map.long2LongEntrySet()) {
				out.writeLong(e.getLongKey());
				out.writeLong(e.getLongValue());
			}
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
	}

	private static void loadMap(it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map, Path file) {
		if (!Files.exists(file)) return;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) map.put(in.readLong(), in.readLong());
		} catch (IOException e) {
			InfiniTerraMod.LOG.warn("Could not read {}", file, e);
		}
	}

	private static void load(LongOpenHashSet set, Path file) {
		if (!Files.exists(file)) return;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			int n = in.readInt();
			for (int i = 0; i < n; i++) set.add(in.readLong());
		} catch (IOException e) {
			InfiniTerraMod.LOG.warn("Could not read {}", file, e);
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
