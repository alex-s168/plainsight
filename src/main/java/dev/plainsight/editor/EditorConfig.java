package dev.plainsight.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plainsight.Plainsight;
import dev.plainsight.module.ModuleConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The editor's own settings (not a module's): {@code config/plainsight/editor.json}, e.g. the widths of the
 * side panels. Read on first use, written when a value changes.
 */
public final class EditorConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static JsonObject values;

	private EditorConfig() {
	}

	static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(Plainsight.ID).resolve("editor.json");
	}

	private static JsonObject values() {
		if (values == null) {
			values = new JsonObject();
			Path f = file();
			if (Files.exists(f)) {
				try {
					values = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
				} catch (IOException | RuntimeException e) {
					Plainsight.LOG.warn("Could not read editor settings {}; using defaults", f, e);
				}
			}
		}
		return values;
	}

	public static float getFloat(String key, float fallback) {
		return ModuleConfig.getFloat(values(), key, fallback);
	}

	public static void setFloat(String key, float value) {
		JsonObject v = values();
		if (v.has(key) && ModuleConfig.getFloat(v, key, Float.NaN) == value) return;
		v.addProperty(key, value);
		save();
	}

	private static void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(values) + "\n");
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not write editor settings {}", f, e);
		}
	}

	/** Forgets the values read (tests: the file is read again on next use). */
	public static void reload() {
		values = null;
	}
}
