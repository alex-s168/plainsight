package dev.plainsight.editor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.editor.tool.BoxSelectTool;
import dev.plainsight.module.Module;
import dev.plainsight.module.SeeThrough;
import dev.plainsight.render.BlockOverlayRenderer;
import dev.plainsight.render.EditorRendering;
import dev.plainsight.render.Overlay;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

import java.util.Map;

/**
 * Read-only state dumps and a few setters for automated tests (called through the dev bridge's
 * {@code /static} endpoint). Not used by the mod itself.
 */
public final class EditorDebug {
	private EditorDebug() {
	}

	public static String dump() {
		Editor e = Editor.get();
		JsonObject o = new JsonObject();
		o.addProperty("active", Editor.isActive());
		o.addProperty("frames", EditorRendering.frames);
		o.addProperty("imgui", dev.plainsight.imgui.ImGuiRenderer.lastFrameInfo);
		JsonObject cam = new JsonObject();
		cam.add("pos", vec(e.camera.pos));
		cam.addProperty("yaw", e.camera.yaw);
		cam.addProperty("pitch", e.camera.pitch);
		cam.addProperty("orbitEntity", e.orbitEntity() == null ? "" : e.orbitEntity().getName().getString());
		cam.addProperty("following", e.followName());
		cam.add("pivot", vec(e.camera.pivot()));
		cam.addProperty("drag", e.camera.drag().name());
		cam.addProperty("speed", e.camera.speed);
		var mc = Minecraft.getInstance();
		var mcCam = mc.gameRenderer.mainCamera();
		cam.add("mcCamera", vec(new Vector3d(mcCam.position().x, mcCam.position().y, mcCam.position().z)));
		cam.addProperty("mcDetached", mcCam.isDetached());
		o.add("camera", cam);
		if (mc.player != null) o.add("player", vec(new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ())));
		JsonArray vp = new JsonArray();
		vp.add(e.viewport.x);
		vp.add(e.viewport.y);
		vp.add(e.viewport.width);
		vp.add(e.viewport.height);
		o.add("viewport", vp);
		o.addProperty("mouse", e.mouseX + "," + e.mouseY);
		o.addProperty("tool", e.tool().id());
		o.addProperty("map", e.map.active());
		o.addProperty("mapBlits", dev.plainsight.render.EditorRendering.mapBlits);
		o.addProperty("mapHeld", dev.plainsight.minimap.Minimap.holdingEditorMap());
		o.addProperty("mapHeight", e.map.height());
		o.addProperty("hints", e.tool().hints());
		o.addProperty("statusHints", e.ui.statusHintsText);
		o.addProperty("ctrl", e.ctrl);
		JsonObject mods = new JsonObject();
		for (Module m : e.viewModules) mods.addProperty(m.id(), m.enabled());
		for (Module m : e.modules) mods.addProperty(m.id(), m.enabled());
		o.add("modules", mods);
		o.addProperty("cutawaySegments", SeeThrough.region().segments().size());
		o.addProperty("cutawayRebuilds", e.seeThrough.rebuilds());
		o.addProperty("overlayBlocksSubmitted", BlockOverlayRenderer.submitted);
		o.addProperty("overlayOutlineEdges", BlockOverlayRenderer.outlineEdges);
		o.addProperty("lightOverlayFaces", e.lightOverlay.markedFaces());
		o.addProperty("lodMod", dev.plainsight.LodSupport.present());
		o.addProperty("cameraToPlayer", dev.infiniterra.api.DetachedCamera.toPlayer());
		o.addProperty("disableCaveCulling", dev.infiniterra.api.DetachedCamera.caveCullingOff());
		o.addProperty("cameraOutsideLoaded", dev.infiniterra.api.DetachedCamera.outsideLoaded());
		var guess = dev.infiniterra.api.TerrainGuess.current();
		var seed = dev.plainsight.guess.SeedStore.seed();
		o.addProperty("seed", seed.isPresent() ? Long.toString(seed.getAsLong()) : "");
		var check = dev.infiniterra.api.SeedCheck.result();
		o.addProperty("seedCheck", check == null ? "" : check.verdict() + " " + check.matched() + " " + check.mismatched() + " " + check.uncomparable());
		var rejected = dev.plainsight.guess.SeedStore.rejected();
		o.addProperty("seedRejected", rejected.isPresent() ? Long.toString(rejected.getAsLong()) : "");
		o.addProperty("guessState", guess == null ? "" : guess.state().name() + (guess.error() != null ? ": " + guess.error() : ""));
		o.addProperty("guessed", guess == null ? 0 : guess.guessedCount());
		o.addProperty("guessReal", guess == null ? 0 : guess.realCount());
		o.addProperty("guessPending", guess == null ? 0 : guess.pending());
		o.addProperty("guessFailed", guess == null ? 0 : guess.failedCount());
		o.addProperty("guessCopied", guess == null ? 0 : guess.copiedThisSession());
		o.addProperty("guessImported", guess == null ? 0 : guess.imported());
		o.addProperty("guessImportTotal", guess == null ? 0 : guess.importTotal());
		o.addProperty("guessShowing", dev.infiniterra.api.TerrainGuess.showing());
		o.addProperty("guessTintDrawn", dev.infiniterra.api.TerrainGuess.tintDrawn());
		var surface = dev.plainsight.render.DepthPick.at(Editor.get().mouseX, Editor.get().mouseY);
		o.addProperty("surfaceUnderCursor", surface == null ? "" : surface.x + "," + surface.y + "," + surface.z);
		try {
			Object r = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer").getMethod("instanceNullable").invoke(null);
			if (r != null) o.addProperty("sodiumChunks", String.valueOf(r.getClass().getMethod("getChunksDebugString").invoke(r)));
		} catch (ReflectiveOperationException ignored) {
		}
		o.addProperty("sodium", Editor.class.getClassLoader().getResource("net/caffeinemc/mods/sodium/client/world/LevelSlice.class") != null);
		JsonObject ov = new JsonObject();
		for (Overlay.Layer l : Overlay.Layer.values()) ov.addProperty(l.name(), e.overlay.lineCount(l) + " lines, " + e.overlay.triangleCount(l) + " tris");
		o.add("overlay", ov);
		o.add("tree", tree(e.tree.root()));
		JsonArray sel = new JsonArray();
		for (SelectionNode n : e.tree.selected()) sel.add(n.label());
		o.add("selected", sel);
		BoxSelectTool bt = e.boxTool;
		if (bt.editing() != null) {
			JsonObject b = new JsonObject();
			b.addProperty("label", bt.editing().label());
			b.add("center", vec(bt.editing().box().center));
			b.add("half", vec(bt.editing().box().half));
			b.addProperty("rotation", bt.editing().box().rotation.toString());
			b.addProperty("handle", bt.activeHandle().name());
			b.addProperty("hoveredHandle", String.valueOf(bt.hoveredHandle()));
			b.addProperty("gizmoHovered", String.valueOf(bt.gizmo.hovered));
			b.addProperty("dragging", bt.isDragging());
			o.add("editing", b);
		}
		o.addProperty("creating", bt.isCreating());
		o.addProperty("status", e.status);
		JsonObject rects = new JsonObject();
		for (Map.Entry<String, float[]> r : e.ui.widgetRects.entrySet()) {
			float[] v = r.getValue();
			rects.addProperty(r.getKey(), v[0] + "," + v[1] + "," + v[2] + "," + v[3]);
		}
		o.add("widgets", rects);
		o.addProperty("nodeTooltip", e.ui.nodeTooltip);
		o.addProperty("leftWidth", e.ui.leftWidth());
		o.addProperty("rightWidth", e.ui.rightWidth());
		o.addProperty("dimension", e.dimension());
		o.addProperty("viewDimension", e.ui.viewDimension());
		o.add("viewTree", tree(e.ui.viewTree().root()));
		o.addProperty("positionText", e.ui.positionText);
		o.addProperty("selectionsDir", e.trees().dir().toString());
		return new GsonBuilder().setPrettyPrinting().create().toJson(o);
	}

	private static JsonArray tree(GroupNode g) {
		JsonArray a = new JsonArray();
		for (SelectionNode c : g.children()) {
			if (c instanceof GroupNode cg) {
				JsonObject o = new JsonObject();
				o.add(cg.label(), tree(cg));
				a.add(o);
			} else {
				a.add(c.label());
			}
		}
		return a;
	}

	private static JsonArray vec(Vector3d v) {
		JsonArray a = new JsonArray();
		a.add(Math.round(v.x * 1000) / 1000.0);
		a.add(Math.round(v.y * 1000) / 1000.0);
		a.add(Math.round(v.z * 1000) / 1000.0);
		return a;
	}

	/** Window pixel position of a world point, or "behind". */
	public static String project(double x, double y, double z) {
		Editor e = Editor.get();
		double[] out = new double[2];
		if (!e.viewport.project(e.camera, new Vector3d(x, y, z), e.fov(), out)) return "behind";
		return Math.round(out[0] * 10) / 10.0 + "," + Math.round(out[1] * 10) / 10.0;
	}

	/** Screen positions of the editing box's handles and of points on the gizmo's arrows and rings. */
	public static String handles() {
		Editor e = Editor.get();
		BoxSelectTool bt = e.boxTool;
		if (bt.editing() == null) return "{}";
		JsonObject o = new JsonObject();
		for (BoxSelectTool.Handle h : BoxSelectTool.Handle.values()) {
			Vector3d p = bt.handlePos(h, new Vector3d());
			o.addProperty(h.name(), project(p.x, p.y, p.z));
		}
		var g = bt.gizmo;
		for (int i = 0; i < 3; i++) {
			Vector3d tip = new Vector3d(g.dir(i)).mul(g.size() * 0.7).add(g.origin);
			o.addProperty("arrow" + "XYZ".charAt(i), project(tip.x, tip.y, tip.z));
		}
		for (int i = 0; i < 3; i++) {
			Vector3d u = g.axis((i + 1) % 3), v = g.axis((i + 2) % 3);
			Vector3d ring = new Vector3d(g.origin).fma(g.size() * 0.85 * Math.cos(0.6), u).fma(g.size() * 0.85 * Math.sin(0.6), v);
			o.addProperty("ring" + "XYZ".charAt(i), project(ring.x, ring.y, ring.z));
		}
		o.addProperty("gizmoSize", g.size());
		return o.toString();
	}

	public static String setCamera(double x, double y, double z, double yaw, double pitch) {
		Editor.get().camera.set(new Vector3d(x, y, z), (float) yaw, (float) pitch);
		return "ok";
	}

	public static String selectTool(String id) {
		Editor e = Editor.get();
		if (id.equals("none")) {
			e.setTool(dev.plainsight.editor.tool.NoTool.INSTANCE);
			return "ok";
		}
		for (var t : e.tools) {
			if (t.id().equals(id)) {
				e.setTool(t);
				return "ok";
			}
		}
		return "no tool " + id;
	}

	public static String setModule(String id, String on) {
		Editor e = Editor.get();
		boolean v = Boolean.parseBoolean(on);
		for (Module m : e.viewModules) if (m.id().equals(id)) m.setEnabled(v);
		for (Module m : e.modules) if (m.id().equals(id)) m.setEnabled(v);
		return "ok";
	}

	public static String solidAt(int x, int y, int z) {
		return String.valueOf(Editor.solidAt(new net.minecraft.core.BlockPos(x, y, z)));
	}

	/** The last {@code n} lines in the chat window (as shown, after the seed hider). */
	public static String chatShown(int n) throws ReflectiveOperationException {
		var chat = net.minecraft.client.Minecraft.getInstance().gui.hud.getChat();
		var f = chat.getClass().getDeclaredField("allMessages");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		var all = (java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage>) f.get(chat);
		StringBuilder sb = new StringBuilder();
		for (int i = Math.min(n, all.size()) - 1; i >= 0; i--) sb.append(all.get(i).content().getString()).append('\n');
		return sb.toString();
	}

	/**
	 * The open chat screen's input, then the suggestions it offers and the usage hint, one per line
	 * ("input: ...", "suggestion: ...", "usage: ..."); "no chat" when the chat is not open (tests).
	 */
	public static String chatSuggestions() throws ReflectiveOperationException {
		if (!(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen chat)) return "no chat";
		StringBuilder sb = new StringBuilder();
		var inputField = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("input");
		inputField.setAccessible(true);
		sb.append("input: ").append(((net.minecraft.client.gui.components.EditBox) inputField.get(chat)).getValue()).append('\n');
		var cs = net.minecraft.client.gui.screens.ChatScreen.class.getDeclaredField("commandSuggestions");
		cs.setAccessible(true);
		Object suggestions = cs.get(chat);
		var pending = net.minecraft.client.gui.components.CommandSuggestions.class.getDeclaredField("pendingSuggestions");
		pending.setAccessible(true);
		var future = (java.util.concurrent.CompletableFuture<?>) pending.get(suggestions);
		if (future != null && future.isDone()) {
			for (var s : ((com.mojang.brigadier.suggestion.Suggestions) future.join()).getList()) sb.append("suggestion: ").append(s.getText()).append('\n');
		}
		var usage = net.minecraft.client.gui.components.CommandSuggestions.class.getDeclaredField("commandUsage");
		usage.setAccessible(true);
		for (Object line : (java.util.List<?>) usage.get(suggestions)) {
			StringBuilder text = new StringBuilder();
			((net.minecraft.util.FormattedCharSequence) line).accept((i, style, cp) -> {
				text.appendCodePoint(cp);
				return true;
			});
			sb.append("usage: ").append(text).append('\n');
		}
		return sb.toString();
	}

	/** The singleplayer world's seed (tests). */
	public static String worldSeed() {
		return Long.toString(net.minecraft.client.Minecraft.getInstance().getSingleplayerServer().overworld().getSeed());
	}

	/** Degrees between the editor's ray through the cursor and the direction to the surface drawn there (tests). */
	public static String rayError() {
		Editor e = Editor.get();
		var surface = dev.plainsight.render.DepthPick.at(e.mouseX, e.mouseY);
		if (surface == null) return "none";
		var toSurface = new org.joml.Vector3d(surface).sub(e.camera.pos).normalize();
		return Double.toString(Math.toDegrees(e.mouseRay().angle(toSurface)));
	}

	/** Leaves the world like the pause menu's button (tests). */
	public static String leaveWorld() {
		net.minecraft.client.Minecraft.getInstance().disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		return "ok";
	}

	public static String guessChunkIsReal(int cx, int cz) {
		var g = dev.infiniterra.api.TerrainGuess.current();
		return g == null ? "none" : String.valueOf(g.isReal(cx, cz));
	}

	/** Chunks within r of (cx, cz) whose LOD column (at the chunk centre) is air all the way down in the rendered LOD world, as "count x,z;x,z...". */
	public static String guessHoles(int cx, int cz, int r) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		StringBuilder at = new StringBuilder();
		int holes = 0;
		for (int x = cx - r; x <= cx + r; x++)
			for (int z = cz - r; z <= cz + r; z++) {
				boolean empty = true;
				for (int y = level.getMinY(); y < level.getMaxY() && empty; y++) {
					var b = dev.infiniterra.api.LodTerrain.block(x * 16 + 8, y, z * 16 + 8);
					empty = b == null || b.isAir();
				}
				if (empty) {
					holes++;
					if (holes <= 20) at.append(at.isEmpty() ? "" : ";").append(x).append(',').append(z);
				}
			}
		return holes + " " + at;
	}

	public static String fluidOpacity(float opacity) {
		Editor.get().fluids.setOpacity(opacity);
		return "ok";
	}

	public static String lodDistanceScale() {
		return Float.toString(Editor.lodDistanceScale());
	}

	public static String setLodDistanceScale(float scale) {
		Editor.setLodDistanceScale(scale, false);
		return "ok";
	}

	public static String guessTintOpacity(float opacity) {
		dev.plainsight.module.GuessedTerrain.tintOpacity = opacity;
		return "ok";
	}

	/** Which of the blocks in the given column range are cut away by the see-through module. */
	public static String cutColumn(int x, int z, int y1, int y2) {
		StringBuilder sb = new StringBuilder();
		for (int y = y1; y <= y2; y++) sb.append(SeeThrough.region().contains(x, y, z) ? '#' : '.');
		return sb + " segments=" + SeeThrough.region().segments();
	}

	public static String seeThroughOpacity(float o) {
		Editor.get().seeThrough.setOpacity(o);
		return "ok";
	}

	public static String pathInfo() {
		var p = Editor.get().pathDemo.path;
		return "nodes=" + p.nodes.size() + " break=" + p.toBreak.size() + " place=" + p.toPlace.size() + " goal=" + p.goal;
	}

	/** Switches the paused editor's savings (held map image, frame limit) on or off, to measure them (#53). */
	public static String pausedThrottle(String on) {
		boolean b = Boolean.parseBoolean(on);
		dev.plainsight.minimap.Minimap.holdPausedMap = b;
		Editor.pausedFps = b ? 60 : 0;
		return "ok";
	}

	public static String overlayOutline(String on) {
		Editor.get().blockOverlayDemo.overlay.outlineColor = Boolean.parseBoolean(on) ? 0xFF60C0FF : 0;
		return "ok";
	}

	public static String overlayOpacity(float o) {
		Editor.get().blockOverlayDemo.overlay.opacity = o;
		return "ok";
	}

	/** Follows the first loaded entity whose name matches (or stops with an empty name). */
	public static String follow(String name) {
		Editor e = Editor.get();
		if (name.isEmpty()) {
			e.follow(null);
			return "ok";
		}
		var level = Minecraft.getInstance().level;
		for (var ent : level.entitiesForRendering()) {
			if (ent.getName().getString().equals(name)) {
				e.follow(ent);
				return "ok";
			}
		}
		return "no entity " + name;
	}

	public static String overlaysInGame() {
		Editor e = Editor.get();
		return e.overlaysInGame() + " " + e.overlay.lineCount(dev.plainsight.render.Overlay.Layer.WORLD)
				+ " " + e.overlay.triangleCount(dev.plainsight.render.Overlay.Layer.WORLD);
	}

	public static String spectatorCulling(String on) {
		dev.infiniterra.api.DetachedCamera.setSpectatorCulling(Boolean.parseBoolean(on));
		return "ok";
	}

	/** Window position of the first point outside solid blocks along the view direction, or "behind". */
	public static String outOfSolidAhead() {
		Editor e = Editor.get();
		Vector3d p = e.outOfSolid(e.camera.pos, e.camera.forward(new Vector3d()));
		return p.x + "," + p.y + "," + p.z;
	}

	public static String lightOverlay(int threshold, int radius) {
		Editor.get().lightOverlay.threshold = threshold;
		Editor.get().lightOverlay.radius = radius;
		return "ok";
	}

	public static String setOverlaysInGame(String on) {
		if (Editor.get().overlaysInGame() != Boolean.parseBoolean(on)) Editor.get().toggleOverlaysInGame();
		return "ok";
	}

	public static String renderDistance(int chunks) {
		Minecraft.getInstance().options.renderDistance().set(chunks);
		return "ok";
	}

	/** Which sections of column (x, z) Sodium has built (meshed), by section y: # built, . not (debugging). */
	public static String sodiumBuilt(int x, int z) {
		try {
			Class<?> c = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			Object r = c.getMethod("instanceNullable").invoke(null);
			var f = c.getDeclaredField("renderSectionManager");
			f.setAccessible(true);
			Object m = f.get(r);
			var built = m.getClass().getMethod("isSectionBuilt", int.class, int.class, int.class);
			var level = Minecraft.getInstance().level;
			StringBuilder sb = new StringBuilder();
			for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++)
				sb.append((boolean) built.invoke(m, x >> 4, sy, z >> 4) ? '#' : '.');
			return sb.toString();
		} catch (ReflectiveOperationException e) {
			return "failed: " + e;
		}
	}

	/** Sodium's render section state of column (x, z): per section y "flags/pendingUpdate", from y min (debugging). */
	public static String sodiumSections(int x, int z) {
		try {
			Class<?> c = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
			Object r = c.getMethod("instanceNullable").invoke(null);
			var f = c.getDeclaredField("renderSectionManager");
			f.setAccessible(true);
			Object m = f.get(r);
			var get = m.getClass().getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
			get.setAccessible(true);
			var level = Minecraft.getInstance().level;
			StringBuilder sb = new StringBuilder();
			for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
				Object sec = get.invoke(m, x >> 4, sy, z >> 4);
				if (sec == null) { sb.append(sy).append(":null "); continue; }
				boolean invisible = (boolean) sec.getClass().getMethod("isInvisible").invoke(sec);
				boolean built = (boolean) sec.getClass().getMethod("isBuilt").invoke(sec);
				int pending = (int) sec.getClass().getMethod("getPendingUpdate").invoke(sec);
				String flags = (built ? "b" : "") + (invisible ? "i" : "v");
				boolean air = level.getChunk(x >> 4, z >> 4).getSections()[level.getSectionIndexFromSectionY(sy)].hasOnlyAir();
				sb.append(sy).append(':').append(flags).append('/').append(pending).append(air ? "a " : " ");
			}
			return sb.toString();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "failed: " + e;
		}
	}

	public static String clouds(String on) {
		Minecraft.getInstance().options.cloudStatus().set(Boolean.parseBoolean(on) ? net.minecraft.client.CloudStatus.FANCY : net.minecraft.client.CloudStatus.OFF);
		return "ok";
	}

	public static String brightnessLevel(float editorLevel, float ingameLevel) {
		Editor.get().editorBrightness.level = editorLevel;
		Editor.get().ingameBrightness.level = ingameLevel;
		return "ok";
	}

	public static String resetLodWarning() {
		dev.plainsight.LodSupport.undismiss();
		return "ok";
	}

	/** The camera's offset from where the followed entity was drawn in the last frame, "x,y,z" ("none" without one). */
	public static String followOffset() {
		var o = Editor.get().lastFollowOffset;
		return Double.isNaN(o.x) ? "none" : o.x + "," + o.y + "," + o.z;
	}

	/** Minimap state: "enabled visible rendered targetDistance error". */
	public static String minimap() {
		return dev.plainsight.minimap.Minimap.enabled + " " + dev.plainsight.minimap.Minimap.visible() + " " + dev.infiniterra.api.CameraPass.rendered()
				+ " " + Math.round(dev.plainsight.minimap.Minimap.targetDistance) + " " + (dev.infiniterra.api.CameraPass.error().isEmpty() ? "-" : dev.infiniterra.api.CameraPass.error());
	}

	/** Whether the minimap renders through the shader pack (Iris with Voxy). */
	public static String minimapThroughPack() {
		return String.valueOf(dev.infiniterra.api.CameraPass.throughPack());
	}

	/** Saves the minimap's render target (what the HUD shows) to a PNG; render thread. */
	public static String minimapShot(String path) {
		var target = dev.infiniterra.api.CameraPass.target();
		if (target == null) return "no minimap target";
		net.minecraft.client.Screenshot.takeScreenshot(target, image -> {
			try (image) {
				image.writeToFile(java.nio.file.Path.of(path));
			} catch (java.io.IOException e) {
				dev.plainsight.Plainsight.LOG.warn("minimap shot", e);
			}
		});
		return "ok";
	}

	public static String unfollow() {
		Editor.get().follow(null);
		return "ok";
	}

	public static String toggle() {
		Editor.get().toggle();
		return String.valueOf(Editor.isActive());
	}

	/** Minimap options: "playerLayer showSelections showPlayers showMobs". */
	public static String minimapOptions() {
		return (dev.plainsight.minimap.Minimap.layer == dev.plainsight.minimap.Minimap.Layer.PLAYER) + " " + dev.plainsight.minimap.Minimap.showSelections + " "
				+ dev.plainsight.minimap.Minimap.showPlayers + " " + dev.plainsight.minimap.Minimap.showMobs;
	}

	public static String setMinimapOptions(String playerLayer, String selections, String players, String mobs) {
		dev.plainsight.minimap.Minimap.layer = Boolean.parseBoolean(playerLayer) ? dev.plainsight.minimap.Minimap.Layer.PLAYER : dev.plainsight.minimap.Minimap.Layer.TOP;
		dev.plainsight.minimap.Minimap.showSelections = Boolean.parseBoolean(selections);
		dev.plainsight.minimap.Minimap.showPlayers = Boolean.parseBoolean(players);
		dev.plainsight.minimap.Minimap.showMobs = Boolean.parseBoolean(mobs);
		return minimapOptions();
	}

	/** Adds a point selection at the block (x, y, z). */
	public static String addPoint(int x, int y, int z) {
		var p = Editor.get().tree.add(new dev.plainsight.editor.selection.PointNode(x, y, z));
		Editor.get().tree.touch();
		return p.name();
	}

	/** Adds a box of whole blocks (corners inclusive) to the selection tree; "hidden" adds it hidden. */
	public static String addBox(int x1, int y1, int z1, int x2, int y2, int z2, String hidden) {
		var b = Editor.get().tree.add(new dev.plainsight.editor.selection.BoxNode(dev.plainsight.editor.math.OrientedBox.ofBlocks(x1, y1, z1, x2, y2, z2)));
		b.visible = !"hidden".equals(hidden);
		Editor.get().tree.touch();
		return b.name();
	}

	/** Switches the chunk borders (F3+G) on or off. */
	public static String chunkBorders(String on) {
		var entries = net.minecraft.client.Minecraft.getInstance().debugEntries;
		var id = net.minecraft.client.gui.components.debug.DebugScreenEntries.CHUNK_BORDERS;
		if (entries.isCurrentlyEnabled(id) != Boolean.parseBoolean(on)) entries.toggleStatus(id);
		return String.valueOf(entries.isCurrentlyEnabled(id));
	}

	/** The block the chunk borders are drawn around in the editor, "x,y,z". */
	public static String chunkBorderAnchor() {
		var p = Editor.get().chunkBorderAnchor();
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}

	/** Quits the game as the Quit button does (shutdown tests). */
	public static String quit() {
		Minecraft.getInstance().stop();
		return "ok";
	}

	/** Reads the module settings files again (as at game start). */
	public static String reloadModuleConfig() {
		dev.plainsight.module.ModuleConfig.load(Editor.get().allModules());
		return "ok";
	}

	/** Xaero's waypoints in this dimension, "name@x,y,z;..." ("none" without Xaero), and how many the minimap drew last. */
	public static String xaeroWaypoints() {
		if (!dev.plainsight.compat.XaeroWaypoints.PRESENT) return "none";
		StringBuilder sb = new StringBuilder();
		for (var w : dev.plainsight.compat.XaeroWaypoints.current()) sb.append(w.name()).append('@').append(w.x()).append(',').append(w.y()).append(',').append(w.z()).append(';');
		return sb + " drawn=" + dev.plainsight.minimap.Minimap.waypointsDrawn;
	}

	public static String removeXaeroWaypoint(String name) {
		return String.valueOf(dev.plainsight.compat.XaeroWaypoints.removeNamed(name));
	}

	public static String minimapWaypoints(String on) {
		dev.plainsight.minimap.Minimap.showWaypoints = Boolean.parseBoolean(on);
		return "ok";
	}

	/** Waypoint markers in the last frame: "drawn nameAlpha nameShown". */
	public static String waypointMarkers() {
		return dev.plainsight.render.WaypointMarkers.drawn + " " + dev.plainsight.render.WaypointMarkers.nameAlpha + " " + dev.plainsight.render.WaypointMarkers.nameShown;
	}

	/** The minimap's tilt (degrees down from the horizon), set by the editor map. */
	public static String minimapTilt() {
		return Float.toString(dev.plainsight.minimap.Minimap.pitch);
	}

	/** Whether the minimap's zoom-out key is held (key mapping state) and Ctrl (tests). */
	public static String zoomOutDown() {
		return dev.plainsight.Plainsight.minimapZoomOut().isDown() + " " + dev.plainsight.Plainsight.ctrlDown();
	}

	/** Key codes bound to the minimap's zoom in, zoom out and layer keys (tests). */
	public static String minimapKeys() {
		var h = (java.util.function.Function<net.minecraft.client.KeyMapping, Integer>) k -> net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(k).getValue();
		return h.apply(dev.plainsight.Plainsight.minimapZoomIn()) + " " + h.apply(dev.plainsight.Plainsight.minimapZoomOut()) + " " + h.apply(dev.plainsight.Plainsight.minimapLayer());
	}

	public static String axiomOpen() {
		return Boolean.toString(dev.plainsight.compat.Axiom.tracked());
	}

	/** Binds Axiom's editor key mapping to {@code key} (-1: unbinds it); "none" without that mapping (tests). */
	public static String setAxiomEditorKey(int key) {
		for (var k : Minecraft.getInstance().options.keyMappings) {
			if (!k.getName().equals("axiom.keybind.toggle_editor_ui")) continue;
			k.setKey(key < 0 ? com.mojang.blaze3d.platform.InputConstants.UNKNOWN : com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(key));
			net.minecraft.client.KeyMapping.resetMapping();
			return "ok";
		}
		return "none";
	}

	/** The minimap's layer text ("Y: 64", "Y: top"). */
	public static String minimapLayer() {
		return dev.plainsight.minimap.Minimap.layerText();
	}

	public static String setMapYaw(float yaw) {
		dev.plainsight.editor.MapMode.yaw = yaw;
		return "ok";
	}

	public static String setMinimapTilt(float degrees) {
		dev.plainsight.minimap.Minimap.pitch = degrees;
		return "ok";
	}

	/** Sets the waypoint diamonds' size (the Waypoints module's slider). */
	public static String waypointScale(float scale) {
		dev.plainsight.render.WaypointMarkers.scale = scale;
		return "ok";
	}

	/** Marks the selection called {@code name} as a waypoint (or not). */
	public static String setWaypoint(String name, String on) {
		for (var n : dev.plainsight.editor.selection.SelectionTree.leaves(Editor.get().tree.root())) {
			if (n.name().equals(name)) {
				n.waypoint = Boolean.parseBoolean(on);
				Editor.get().tree.touch();
				return "ok";
			}
		}
		return "no such selection";
	}

	public static String captureRepro() {
		return VisualsRepro.capture(Editor.get());
	}

	/** Puts a repro's view back: "clipboard", "file:<path>" (the bridge splits arguments at commas), or the JSON. */
	public static String applyRepro(String json) {
		try {
			String text = json.equals("clipboard") ? clipboard()
					: json.startsWith("file:") ? java.nio.file.Files.readString(java.nio.file.Path.of(json.substring(5))) : json;
			return VisualsRepro.apply(Editor.get(), text);
		} catch (java.io.IOException e) {
			return "could not read " + json + ": " + e;
		}
	}

	public static String clipboard() {
		return net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
	}

	/** On the singleplayer server: what the structure {@code id} that starts in the chunk of (x, z) is (as /plainsight locate tells). */
	public static String realStructureDetails(String id, int x, int z) {
		var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (server == null || mc.level == null) return "no singleplayer server";
		var dim = mc.level.dimension();
		return server.submit(() -> {
			var level = server.getLevel(dim);
			var structure = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
					.getValue(net.minecraft.resources.Identifier.parse(id));
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					var chunk = level.getChunk((x >> 4) + dx, (z >> 4) + dz, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS, true);
					var start = chunk.getStartForStructure(structure);
					if (start != null && start.isValid())
						return String.valueOf(dev.plainsight.guess.StructureDetails.describe(id, start)) + " " + start.getBoundingBox();
				}
			}
			return "none";
		}).join();
	}

	/** Shows a dimension's selections in the tree view ("minecraft:the_nether"). */
	public static String showDimension(String dimension) {
		Editor.get().ui.showDimension(dimension);
		return "ok";
	}

	/** Adds a point to a dimension's tree (it need not be the player's). */
	public static String addPointIn(String dimension, int x, int y, int z) {
		var t = Editor.get().trees().tree(dimension);
		var p = t.add(new dev.plainsight.editor.selection.PointNode(x, y, z));
		t.touch();
		return p.name();
	}

	/** Empties a dimension's tree. */
	public static String clearTreeIn(String dimension) {
		Editor.get().trees().tree(dimension).clear();
		Editor.get().trees().tree(dimension).touch();
		return "ok";
	}

	private static GroupNode groupTitled(String title) {
		if (title.isEmpty()) return null;
		GroupNode[] found = {null};
		Editor.get().tree.root().forEach(n -> {
			if (found[0] == null && n instanceof GroupNode g && n.title().equals(title)) found[0] = g;
		});
		if (found[0] == null) throw new IllegalArgumentException("no group " + title);
		return found[0];
	}

	/** Adds a group (in the group titled {@code parent}, "" for the top level), "open" or "closed". */
	public static String addGroup(String parent, String name, String open) {
		var t = Editor.get().tree;
		GroupNode g = t.add(new GroupNode(), groupTitled(parent), -1);
		t.rename(g, name);
		g.expanded = "open".equals(open);
		t.touch();
		return g.title();
	}

	/** Adds a named point to the group titled {@code parent} ("" for the top level). */
	public static String addPointTo(String parent, String name, int x, int y, int z) {
		var t = Editor.get().tree;
		var p = t.add(new dev.plainsight.editor.selection.PointNode(x, y, z), groupTitled(parent), -1);
		t.rename(p, name);
		t.touch();
		return p.title();
	}

	/** Reads the panel widths from the editor config again. */
	public static String reloadPanelWidths() {
		EditorConfig.reload();
		Editor.get().ui.setPanelWidths(Float.NaN, Float.NaN);
		return "ok";
	}

	/** Sets the default panel widths, also in the editor config. */
	public static String resetPanelWidths() {
		EditorConfig.setFloat("leftPanelWidth", dev.plainsight.editor.ui.EditorUI.DEFAULT_LEFT_WIDTH);
		EditorConfig.setFloat("rightPanelWidth", dev.plainsight.editor.ui.EditorUI.DEFAULT_RIGHT_WIDTH);
		return reloadPanelWidths();
	}

	/** Renames the first node whose tree title is {@code title}. */
	public static String renameNode(String title, String name) {
		SelectionNode[] found = {null};
		Editor.get().tree.root().forEach(n -> {
			if (found[0] == null && n != Editor.get().tree.root() && n.title().equals(title)) found[0] = n;
		});
		if (found[0] == null) return "error: no node " + title;
		Editor.get().tree.rename(found[0], name);
		return found[0].title();
	}

	public static String clearTree() {
		Editor.get().boxTool.stopEditing();
		Editor.get().tree.clear();
		return "ok";
	}
}
