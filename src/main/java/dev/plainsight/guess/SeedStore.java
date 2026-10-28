package dev.plainsight.guess;

import dev.plainsight.Plainsight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.biome.BiomeManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * World seeds known per server (or singleplayer world), from SeedcrackerX or {@code /plainsight seed}.
 * Kept in {@code config/plainsight/seeds.properties}.
 */
public final class SeedStore {
	private static final Properties SEEDS = new Properties();
	private static final Properties SOURCES = new Properties();
	private static boolean loaded;

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

	/** Key of the world the player is in: a server address or a singleplayer world name; null outside a world. */
	public static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		var sp = mc.getSingleplayerServer();
		if (sp != null) return "singleplayer:" + sp.getWorldData().getLevelName();
		ServerData server = mc.getCurrentServer();
		return server != null ? "server:" + server.ip.toLowerCase(java.util.Locale.ROOT) : "unknown";
	}

	public static synchronized OptionalLong seed() {
		load();
		String key = worldKey();
		String v = key == null ? null : SEEDS.getProperty(key);
		if (v == null) return OptionalLong.empty();
		try {
			return OptionalLong.of(Long.parseLong(v));
		} catch (NumberFormatException e) {
			return OptionalLong.empty();
		}
	}

	/** Where the seed came from ("SeedcrackerX", "command"), or null. */
	public static synchronized String source() {
		load();
		String key = worldKey();
		return key == null ? null : SOURCES.getProperty(key);
	}

	public static synchronized void set(long seed, String source) {
		load();
		String key = worldKey();
		if (key == null) return;
		SEEDS.setProperty(key, Long.toString(seed));
		SOURCES.setProperty(key, source);
		save();
		Plainsight.LOG.info("World seed for {} set to {} ({})", key, seed, source);
	}

	public static synchronized void clear() {
		load();
		String key = worldKey();
		if (key == null) return;
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
