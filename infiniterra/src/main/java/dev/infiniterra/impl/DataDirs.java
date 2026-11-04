package dev.infiniterra.impl;

import dev.infiniterra.InfiniTerraMod;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where InfiniTerra keeps its data: {@code <game>/infiniterra/}. Everything generated from a seed is keyed
 * by the seed and the Minecraft version, so another seed or a game update (whose worldgen may differ)
 * never shows old guesses.
 */
public final class DataDirs {
	private DataDirs() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(InfiniTerraMod.ID);
	}

	/** The Minecraft version as a file name and identifier part ("26.3"). */
	public static String version() {
		return SharedConstants.getCurrentVersion().name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	/** "seed-version" (the seed in hex), the key of everything generated from a seed. */
	public static String seedKey(long seed) {
		return Long.toHexString(seed) + "-" + version();
	}

	/** The world of the worldgen server for a seed. */
	public static Path worldgen(long seed) {
		Path dir = root().resolve("worldgen").resolve(version()).resolve(Long.toHexString(seed));
		migrateWorldgen(seed, dir);
		return dir;
	}

	/** Guessed-terrain state of one world (server or save), dimension and seed. */
	public static Path guessState(String worldKey, String dimension, long seed) {
		return root().resolve("guess").resolve(sanitize(worldKey)).resolve(sanitize(dimension)).resolve(seedKey(seed));
	}

	public static String sanitize(String s) {
		return s.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	/**
	 * Worldgen worlds from before InfiniTerra (plainsight's {@code plainsight/guess/worlds/<seed>}, made by
	 * this Minecraft version as far as we know) are moved over once instead of being generated again.
	 */
	private static void migrateWorldgen(long seed, Path dir) {
		Path old = Minecraft.getInstance().gameDirectory.toPath().resolve("plainsight/guess/worlds").resolve(Long.toHexString(seed));
		if (Files.exists(dir) || !Files.isDirectory(old)) return;
		try {
			Files.createDirectories(dir.getParent());
			Files.move(old, dir);
			InfiniTerraMod.LOG.info("Moved the worldgen world for a seed from {} to {}", old, dir);
		} catch (IOException e) {
			InfiniTerraMod.LOG.warn("Could not move {} to {}", old, dir, e);
		}
	}
}
