package dev.plainsight.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.infiniterra.api.LodTerrain;
import dev.infiniterra.api.TerrainGuess;
import dev.plainsight.guess.SeedStore;
import dev.plainsight.module.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Vector3d;

import java.util.Base64;
import java.util.BitSet;

/**
 * "Copy visuals repro": everything that decides what the editor's view shows, as JSON for a bug report
 * (camera, map, seed, render and LOD distance, modules and their settings, mods), with bitsets of the chunks
 * around the camera (loaded, real and guessed). {@link #apply} puts a view back (camera, map, render
 * distance, modules) to reproduce it.
 */
public final class VisualsRepro {
	/** Chunks around the camera chunk in the bitsets: a (2R+1)^2 square, row by row from the north-west. */
	public static final int RADIUS = 48;
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private VisualsRepro() {
	}

	public static String capture(Editor editor) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		o.addProperty("plainsightRepro", 1);
		o.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
		JsonObject mods = new JsonObject();
		for (String id : new String[]{"plainsight", "infiniterra", "sodium", "voxy", "iris", "distanthorizons", "xaerominimap", "axiom", "seedcrackerx"})
			FabricLoader.getInstance().getModContainer(id).ifPresent(m -> mods.addProperty(id, m.getMetadata().getVersion().getFriendlyString()));
		o.add("mods", mods);
		if (mc.level != null) o.addProperty("dimension", mc.level.dimension().identifier().toString());
		o.addProperty("server", mc.getSingleplayerServer() != null ? "singleplayer" : "multiplayer");
		var seed = SeedStore.seed();
		if (seed.isPresent()) o.addProperty("seed", Long.toString(seed.getAsLong()));
		o.addProperty("seedSource", String.valueOf(SeedStore.source()));

		var cam = editor.camera;
		JsonObject c = new JsonObject();
		c.add("pos", vec(cam.pos.x, cam.pos.y, cam.pos.z));
		c.addProperty("yaw", cam.yaw);
		c.addProperty("pitch", cam.pitch);
		c.addProperty("fov", editor.fov());
		c.addProperty("map", editor.map.active());
		if (editor.map.active()) {
			c.addProperty("mapHeight", editor.map.height());
			c.addProperty("mapYaw", MapMode.yaw);
		}
		o.add("camera", c);
		if (mc.player != null) o.add("player", vec(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
		var win = mc.getWindow();
		o.addProperty("window", win.getWidth() + "x" + win.getHeight());
		o.addProperty("viewport", (int) editor.viewport.width + "x" + (int) editor.viewport.height);

		JsonObject r = new JsonObject();
		r.addProperty("renderDistance", mc.options.renderDistance().get());
		r.addProperty("effectiveRenderDistance", mc.options.getEffectiveRenderDistance());
		r.addProperty("lodMod", LodTerrain.present());
		voxyDistance(r);
		r.addProperty("guessRadius", TerrainGuess.radius());
		r.addProperty("guessShowing", TerrainGuess.showing());
		o.add("render", r);

		JsonObject modules = new JsonObject();
		for (Module m : editor.allModules()) {
			JsonObject mo = new JsonObject();
			mo.addProperty("enabled", m.enabled());
			JsonObject settings = new JsonObject();
			m.saveSettings(settings);
			if (!settings.isEmpty()) mo.add("settings", settings);
			modules.add(m.id(), mo);
		}
		o.add("modules", modules);

		o.add("chunks", chunks(mc, (int) Math.floor(cam.pos.x) >> 4, (int) Math.floor(cam.pos.z) >> 4));
		return GSON.toJson(o);
	}

	private static JsonObject chunks(Minecraft mc, int cx, int cz) {
		int n = 2 * RADIUS + 1;
		BitSet loaded = new BitSet(n * n), real = new BitSet(n * n), guessed = new BitSet(n * n);
		TerrainGuess.Status g = TerrainGuess.current();
		for (int dz = -RADIUS; dz <= RADIUS; dz++)
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				int i = (dz + RADIUS) * n + dx + RADIUS, x = cx + dx, z = cz + dz;
				if (mc.level != null && mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) loaded.set(i);
				if (g != null) {
					if (g.isReal(x, z)) real.set(i);
					if (g.isGuessed(x, z)) guessed.set(i);
				}
			}
		JsonObject o = new JsonObject();
		o.addProperty("format", "bitsets of a " + n + "x" + n + " chunk square, index (z - minZ) * " + n + " + (x - minX), little-endian bytes, base64");
		o.addProperty("minX", cx - RADIUS);
		o.addProperty("minZ", cz - RADIUS);
		o.addProperty("size", n);
		o.addProperty("loaded", Base64.getEncoder().encodeToString(loaded.toByteArray()));
		if (g != null) {
			o.addProperty("real", Base64.getEncoder().encodeToString(real.toByteArray()));
			o.addProperty("guessed", Base64.getEncoder().encodeToString(guessed.toByteArray()));
		}
		return o;
	}

	/** Decodes one of the bitsets (tests and tools). */
	public static BitSet bits(String base64) {
		return BitSet.valueOf(Base64.getDecoder().decode(base64));
	}

	/**
	 * Puts the view of a repro back: modules and their settings, render distance and LOD scale, map and camera.
	 * The seed, the world and the chunks are not changed (load the world and set the seed first).
	 */
	public static String apply(Editor editor, String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		if (!o.has("plainsightRepro")) return "not a plainsight repro";
		Minecraft mc = Minecraft.getInstance();
		if (o.has("modules")) {
			JsonObject modules = o.getAsJsonObject("modules");
			for (Module m : editor.allModules()) {
				if (!modules.has(m.id())) continue;
				JsonObject mo = modules.getAsJsonObject(m.id());
				if (mo.has("settings")) m.loadSettings(mo.getAsJsonObject("settings"));
				if (mo.has("enabled")) m.setEnabled(mo.get("enabled").getAsBoolean());
			}
		}
		if (o.has("render")) {
			JsonObject r = o.getAsJsonObject("render");
			if (r.has("renderDistance")) mc.options.renderDistance().set(r.get("renderDistance").getAsInt());
			if (r.has("guessRadius")) TerrainGuess.setRadius(r.get("guessRadius").getAsInt());
		}
		JsonObject c = o.getAsJsonObject("camera");
		JsonArray p = c.getAsJsonArray("pos");
		Vector3d pos = new Vector3d(p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble());
		boolean map = c.has("map") && c.get("map").getAsBoolean();
		if (map && c.has("mapYaw")) MapMode.yaw = c.get("mapYaw").getAsFloat();
		if (map && !editor.map.active()) editor.map.enter(editor);
		if (!map && editor.map.active()) editor.map.exit(editor);
		editor.camera.set(pos, c.get("yaw").getAsFloat(), c.get("pitch").getAsFloat());
		return "ok";
	}

	/** Voxy's configured LOD distance (by reflection: Voxy is optional). */
	private static void voxyDistance(JsonObject r) {
		try {
			Class<?> cfg = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
			Object config = cfg.getField("CONFIG").get(null);
			r.addProperty("voxySectionRenderDistance", cfg.getField("sectionRenderDistance").getFloat(config));
			r.addProperty("voxyEnabled", cfg.getField("enabled").getBoolean(config));
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			// no Voxy
		}
	}

	private static JsonArray vec(double x, double y, double z) {
		JsonArray a = new JsonArray();
		a.add(x);
		a.add(y);
		a.add(z);
		return a;
	}
}
