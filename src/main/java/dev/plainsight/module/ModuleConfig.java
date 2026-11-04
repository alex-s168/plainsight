package dev.plainsight.module;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plainsight.Plainsight;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's module settings, one JSON file per module in {@code config/plainsight/modules/<id>.json}:
 * whether it is on and its settings. Loaded once the game runs; a module's file is written when its
 * settings changed (checked about once a second, so every way of changing them is covered).
 */
public final class ModuleConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Map<String, String> written = new HashMap<>();
	private static List<Module> modules = List.of();
	private static long lastCheck;

	private ModuleConfig() {
	}

	private static Path dir() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config/plainsight/modules");
	}

	/** Applies the saved settings to {@code all}; from then on their changes are saved. */
	public static void load(List<Module> all) {
		modules = all;
		for (Module m : all) {
			Path f = dir().resolve(m.id() + ".json");
			if (!Files.exists(f)) continue;
			try {
				JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				JsonElement on = o.get("enabled");
				if (on != null && on.isJsonPrimitive() && m.persistEnabled()) m.setEnabled(on.getAsBoolean());
				m.loadSettings(o);
			} catch (IOException | RuntimeException e) {
				Plainsight.LOG.warn("Could not read module settings {}; using defaults", f, e);
			}
		}
		// what is in effect now counts as written (no rewrite of untouched files)
		for (Module m : all) if (!rewrite.remove(m.id())) written.put(m.id(), serialize(m));
	}

	private static final java.util.Set<String> rewrite = new java.util.HashSet<>();

	/** A module's settings file is to be written again (its settings were migrated while loading). */
	public static void markChanged(String id) {
		rewrite.add(id);
		written.remove(id);
	}

	/** Saves modules whose settings changed; call regularly (client tick). */
	public static void tick() {
		long now = System.currentTimeMillis();
		if (now - lastCheck < 1000) return;
		lastCheck = now;
		save(false);
	}

	/** Writes changed modules now (e.g. when the game stops); {@code all} writes every module. */
	public static void save(boolean all) {
		for (Module m : modules) {
			String json = serialize(m);
			if (!all && json.equals(written.get(m.id()))) continue;
			Path f = dir().resolve(m.id() + ".json");
			try {
				Files.createDirectories(f.getParent());
				Files.writeString(f, json + "\n");
				written.put(m.id(), json);
			} catch (IOException e) {
				Plainsight.LOG.warn("Could not write module settings {}", f, e);
			}
		}
	}

	private static String serialize(Module m) {
		JsonObject o = new JsonObject();
		if (m.persistEnabled()) o.addProperty("enabled", m.enabled());
		try {
			m.saveSettings(o);
		} catch (RuntimeException e) {
			Plainsight.LOG.warn("Module {} settings could not be saved", m.id(), e);
		}
		return GSON.toJson(o);
	}

	// helpers for modules: a value of the right type from o, else the fallback

	public static float getFloat(JsonObject o, String key, float fallback) {
		try {
			return o.has(key) ? o.get(key).getAsFloat() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static int getInt(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static boolean getBool(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	public static String getString(JsonObject o, String key, String fallback) {
		try {
			return o.has(key) ? o.get(key).getAsString() : fallback;
		} catch (RuntimeException e) {
			return fallback;
		}
	}
}
