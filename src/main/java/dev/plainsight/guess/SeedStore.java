package dev.plainsight.guess;

import dev.plainsight.Plainsight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.biome.BiomeManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * World seeds known per server or singleplayer world, from SeedcrackerX or {@code /plainsight seed}.
 * Server seeds are kept in {@code config/plainsight/seeds.properties}; a singleplayer world's seed in
 * {@code plainsight/seed.json} in the world's folder, so it follows the world when it is renamed or
 * copied (world names are not unique: new worlds are all called "New World").
 */
public final class SeedStore {
	private static final Properties SEEDS = new Properties();
	private static final Properties SOURCES = new Properties();
	private static boolean loaded;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The singleplayer world's seed file last read, and what it held (seed null: none). */
	private static Path worldFile;
	private static String worldSeed, worldSource;

	private SeedStore() {
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config/plainsight/seeds.properties");
	}

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		Path f = file();
		if (!Files.exists(f)) return;
		try (Reader r = Files.newBufferedReader(f)) {
			Properties all = new Properties();
			all.load(r);
			for (String k : all.stringPropertyNames()) {
				if (k.endsWith(".source")) SOURCES.setProperty(k.substring(0, k.length() - 7), all.getProperty(k));
				else SEEDS.setProperty(k, all.getProperty(k));
			}
		} catch (IOException | IllegalArgumentException e) {
			Plainsight.LOG.warn("Could not read {}", f, e);
		}
	}

	private static synchronized void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Properties all = new Properties();
			all.putAll(SEEDS);
			for (String k : SOURCES.stringPropertyNames()) all.setProperty(k + ".source", SOURCES.getProperty(k));
			try (Writer w = Files.newBufferedWriter(f)) {
				all.store(w, "plainsight: world seeds per server, for the editor's guessed terrain");
			}
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not write {}", f, e);
		}
	}

	/** The singleplayer world's seed file, or null outside singleplayer. */
	private static Path singleplayerFile() {
		Minecraft mc = Minecraft.getInstance();
		var sp = mc.level == null ? null : mc.getSingleplayerServer();
		if (sp == null) return null;
		return sp.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
				.resolve("plainsight/seed.json");
	}

	/** Reads the world's seed file (once per world), moving an entry of the old per-name store into it. */
	private static void loadWorld(Path f) {
		if (f.equals(worldFile)) return;
		worldFile = f;
		worldSeed = worldSource = null;
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f)) {
				JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("seed")) worldSeed = o.get("seed").getAsString();
				if (o.has("source")) worldSource = o.get("source").getAsString();
			} catch (IOException | RuntimeException e) {
				Plainsight.LOG.warn("Could not read {}", f, e);
			}
			return;
		}
		// older versions keyed singleplayer seeds by the world's name; only trust that entry for the
		// world whose folder has that name (the first world of that name), not for "New World (3)"
		var sp = Minecraft.getInstance().getSingleplayerServer();
		String name = sp.getWorldData().getLevelName();
		String legacy = "singleplayer:" + name;
		if (SEEDS.getProperty(legacy) != null && name.equals(f.getParent().getParent().getFileName().toString())) {
			worldSeed = SEEDS.getProperty(legacy);
			worldSource = SOURCES.getProperty(legacy);
			saveWorld();
			SEEDS.remove(legacy);
			SOURCES.remove(legacy);
			save();
		}
	}

	private static void saveWorld() {
		try {
			if (worldSeed == null) {
				Files.deleteIfExists(worldFile);
				return;
			}
			Files.createDirectories(worldFile.getParent());
			JsonObject o = new JsonObject();
			o.addProperty("seed", worldSeed);
			if (worldSource != null) o.addProperty("source", worldSource);
			Files.writeString(worldFile, GSON.toJson(o));
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not write {}", worldFile, e);
		}
	}

	/** Key of the world the player is in: a server address or a singleplayer world's folder; null outside a world. */
	public static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		var sp = mc.getSingleplayerServer();
		if (sp != null) return "singleplayer:" + singleplayerFile().getParent().getParent().getFileName();
		ServerData server = mc.getCurrentServer();
		return server != null ? "server:" + server.ip.toLowerCase(java.util.Locale.ROOT) : "unknown";
	}

	/** All seeds this store knows (any world), for {@link SeedHider}. */
	public static synchronized long[] knownSeeds() {
		load();
		var all = new java.util.ArrayList<Object>(SEEDS.values());
		Path f = singleplayerFile();
		if (f != null) {
			loadWorld(f);
			if (worldSeed != null) all.add(worldSeed);
		}
		return all.stream().mapToLong(v -> {
			try {
				return Long.parseLong((String) v);
			} catch (NumberFormatException e) {
				return Long.MIN_VALUE;
			}
		}).filter(v -> v != Long.MIN_VALUE).toArray();
	}

	public static synchronized OptionalLong seed() {
		String v = raw();
		if (v == null) return OptionalLong.empty();
		try {
			return OptionalLong.of(Long.parseLong(v));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	private static String raw() {
		load();
		Path f = singleplayerFile();
		if (f != null) {
			loadWorld(f);
			return worldSeed;
		}
		String key = worldKey();
		return key == null ? null : SEEDS.getProperty(key);
	}

	/** Where the seed came from ("SeedcrackerX", "command"), or null. */
	public static synchronized String source() {
		load();
		Path f = singleplayerFile();
		if (f != null) {
			loadWorld(f);
			return worldSource;
		}
		String key = worldKey();
		return key == null ? null : SOURCES.getProperty(key);
	}

	public static synchronized void set(long seed, String source) {
		load();
		String key = worldKey();
		if (key == null) return;
		Path f = singleplayerFile();
		if (f != null) {
			loadWorld(f);
			worldSeed = Long.toString(seed);
			worldSource = source;
			saveWorld();
		} else {
			SEEDS.setProperty(key, Long.toString(seed));
			SOURCES.setProperty(key, source);
			save();
		}
		Plainsight.LOG.info("World seed for {} set ({})", key, source);
	}

	/** A seed SeedcrackerX found: kept (if new) and announced in chat. Render thread. */
	public static void fromSeedCracker(long seed) {
		if (seed().orElse(Long.MIN_VALUE) == seed) return;
		set(seed, "SeedcrackerX");
		Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(
				"[Plainsight] Seed " + SeedHider.show(seed) + " from SeedcrackerX saved: the editor shows guessed terrain (with Voxy)."));
	}

	public static synchronized void clear() {
		load();
		String key = worldKey();
		if (key == null) return;
		Path f = singleplayerFile();
		if (f != null) {
			loadWorld(f);
			worldSeed = worldSource = null;
			saveWorld();
			return;
		}
		SEEDS.remove(key);
		SOURCES.remove(key);
		save();
	}

	/**
	 * Whether {@code seed} matches the hashed seed the server sent (biome zoom seed); empty when it can't
	 * be checked.
	 */
	public static java.util.Optional<Boolean> matchesServer(long seed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return java.util.Optional.empty();
		try {
			var f = BiomeManager.class.getDeclaredField("biomeZoomSeed");
			f.setAccessible(true);
			long hashed = f.getLong(mc.level.getBiomeManager());
			return java.util.Optional.of(hashed == BiomeManager.obfuscateSeed(seed));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return java.util.Optional.empty();
		}
	}
}
