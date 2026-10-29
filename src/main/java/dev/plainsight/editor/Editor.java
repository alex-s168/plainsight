package dev.plainsight.editor;

import com.mojang.blaze3d.platform.InputConstants;
import dev.plainsight.Plainsight;
import dev.plainsight.editor.selection.BoxNode;
import dev.plainsight.editor.selection.GroupNode;
import dev.plainsight.editor.selection.SelectionNode;
import dev.plainsight.editor.selection.SelectionStore;
import dev.plainsight.editor.selection.SelectionTree;
import dev.plainsight.editor.tool.BoxSelectTool;
import dev.plainsight.editor.tool.EntitySelectTool;
import dev.plainsight.editor.tool.Tool;
import dev.plainsight.editor.ui.EditorUI;
import dev.plainsight.imgui.ImGuiLayer;
import dev.plainsight.module.BlockOverlayDemo;
import dev.plainsight.module.FluidTransparency;
import dev.plainsight.module.Module;
import dev.plainsight.module.PathOverlayDemo;
import dev.plainsight.module.SeeThrough;
import dev.plainsight.render.Overlay;
import dev.plainsight.render.SelectionRenderer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The editor: a free camera over the world with ImGui panels around a viewport. Purely client-side;
 * it sends nothing to the server, so it works in every game mode and the player simply stands still.
 */
public final class Editor {
	private static final Editor INSTANCE = new Editor();
	public static final double REACH = 512;

	public static Editor get() {
		return INSTANCE;
	}

	/** Camera inside solid blocks renders without cave culling, like spectator mode (switchable for tests). */
	public static volatile boolean spectatorCulling = true;
	/**
	 * Set every frame: the editor camera is inside solid blocks or outside the loaded chunks, so cave
	 * (occlusion) culling must be off (vanilla and Sodium).
	 */
	public static volatile boolean disableCaveCulling;
	/**
	 * Horizontal distance from the editor camera to the player (0 outside the editor): renderers that
	 * only draw sections within render distance of the camera must look this much further, or the
	 * chunks loaded around the player are not drawn while the camera is away.
	 */
	public static volatile float cameraToPlayer;
	/** Whether the editor camera is outside the loaded chunks (false outside the editor). */
	public static volatile boolean cameraOutsideLoaded;

	public static boolean isActive() {
		return INSTANCE.active;
	}

	private volatile boolean active;
	public final EditorCamera camera = new EditorCamera();
	public final Viewport viewport = new Viewport();
	public final SelectionTree tree = new SelectionTree();
	public final Overlay overlay = new Overlay();
	public final EditorUI ui = new EditorUI(this);

	public final BoxSelectTool boxTool = new BoxSelectTool(this);
	public final EntitySelectTool entityTool = new EntitySelectTool(this);
	public final List<Tool> tools = List.of(boxTool, entityTool);
	private Tool tool = dev.plainsight.editor.tool.NoTool.INSTANCE;
	/** The tool to return to after editing a box that was picked in the tree (null otherwise). */
	private @Nullable Tool toolBeforeTreeEdit;
	private boolean switchingTool;

	public final FluidTransparency fluids = new FluidTransparency();
	public final SeeThrough seeThrough = new SeeThrough(this);
	public final BlockOverlayDemo blockOverlayDemo = new BlockOverlayDemo(this);
	public final PathOverlayDemo pathDemo = new PathOverlayDemo(this);
	public final dev.plainsight.module.Brightness editorBrightness = new dev.plainsight.module.Brightness(true);
	public final dev.plainsight.module.Brightness ingameBrightness = new dev.plainsight.module.Brightness(false);
	/** Toggleable modules shown in the tool bar (right-click for settings). */
	public final dev.plainsight.module.LightOverlay lightOverlay = new dev.plainsight.module.LightOverlay();
	public final List<Module> modules = List.of(lightOverlay, editorBrightness, ingameBrightness);
	/** The viewport's buttons: view options and overlay demos. */
	public final dev.plainsight.module.GuessedTerrain guessedTerrain = new dev.plainsight.module.GuessedTerrain();
	public final List<Module> viewModules = List.of(fluids, seeThrough, blockOverlayDemo, pathDemo, guessedTerrain);

	/** Mouse position in window coordinates (ImGui's coordinate space). */
	public double mouseX, mouseY;
	private double pressX, pressY;
	private boolean movedSincePress;
	private @Nullable Entity orbitEntity;
	private java.util.@Nullable UUID follow;
	private String followName = "";
	private @Nullable Vector3d followLast;
	private double lastDragX, lastDragY;
	public boolean ctrl, shift, alt;
	private long lastFrameNanos;
	/** This frame's position between the last two game ticks (0..1), for drawing moving entities smoothly. */
	private static float partialTick = 1;
	/** The camera's offset from where the followed entity is drawn, as of the last frame (tests). */
	public final Vector3d lastFollowOffset = new Vector3d(Double.NaN, 0, 0);
	private int savedVersion = -1;
	private @Nullable Path storeFile;
	private long lastSaveCheck;
	/** Draw the editor's overlays (selections) in the normal game too, not only in the editor. */
	private boolean overlaysInGame;

	public boolean overlaysInGame() {
		return overlaysInGame;
	}

	/** Toggles the editor overlays outside the editor (keybind "Show Editor Overlays In Game"). */
	public void toggleOverlaysInGame() {
		overlaysInGame = !overlaysInGame;
		Minecraft mc = Minecraft.getInstance();
		if (overlaysInGame && mc.level != null) loadSelections();
		if (mc.player != null) {
			mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(
					"Editor overlays in game: " + (overlaysInGame ? "on" : "off")), false);
		}
	}

	/** Status line message (e.g. the picked block) and when it expires. */
	public String status = "";
	private long statusUntil;

	private Editor() {
	}

	public Tool tool() {
		return tool;
	}

	public void setTool(Tool t) {
		if (tool != t) {
			switchingTool = true;
			try {
				tool.deactivate();
			} finally {
				switchingTool = false;
			}
			tool = t;
			// choosing a tool by hand ends "editing a box picked in the tree"
			toolBeforeTreeEdit = null;
		}
	}

	/** Tool bar click: selects the tool, or unselects it when it is already the active one. */
	public void toggleTool(Tool t) {
		setTool(tool == t ? dev.plainsight.editor.tool.NoTool.INSTANCE : t);
	}

	// -- lifecycle ------------------------------------------------------------------------------------------

	public void toggle() {
		if (active) disable();
		else enable();
	}

	public void enable() {
		Minecraft mc = Minecraft.getInstance();
		if (active || mc.player == null || mc.level == null) return;
		ImGuiLayer.get();
		var cam = mc.gameRenderer.mainCamera();
		// start behind and slightly above the player so the (third person) camera shows them
		Vec3 eye = mc.player.getEyePosition();
		float yaw = mc.player.getYRot(), pitch = Math.max(15f, mc.player.getXRot());
		Vector3d back = EditorCamera.direction(yaw, pitch, new Vector3d()).mul(-5);
		Vec3 start = new Vec3(eye.x + back.x, eye.y + back.y, eye.z + back.z);
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, start, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() != HitResult.Type.MISS) {
			Vec3 l = hit.getLocation();
			start = l.add(eye.subtract(l).normalize().scale(0.3));
		}
		camera.set(new Vector3d(start.x, start.y, start.z), yaw, pitch);
		camera.stopMovement();
		loadSelections();
		active = true;
		KeyMapping.releaseAll();
		mc.mouseHandler.releaseMouse();
		Plainsight.LOG.info("Editor enabled at {} (camera was {})", start, cam.position());
	}

	public void disable() {
		if (!active) return;
		saveSelections();
		active = false;
		camera.stopMovement();
		camera.endDrag();
		tool.deactivate();
		Minecraft mc = Minecraft.getInstance();
		mc.textInputManager().stopTextInput(this);
		if (mc.gui.screen() == null) mc.mouseHandler.grabMouse();
	}

	/** Called every frame before the camera is set up. */
	public void frame(float partialTick) {
		Editor.partialTick = partialTick;
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9;
		lastFrameNanos = now;
		Minecraft mc = Minecraft.getInstance();
		if (!active) {
			// the see-through cut is an editor view option: never in the normal game
			seeThrough.frame();
			return;
		}
		if (mc.player == null || mc.level == null) {
			disable();
			return;
		}
		if (mc.gui.screen() != null) camera.stopMovement();
		camera.update(dt);
		trackEntities();
		Entity f = followed();
		if (f != null) {
			Vec3 at = f.getPosition(partialTick);
			lastFollowOffset.set(camera.pos.x - at.x, camera.pos.y - at.y, camera.pos.z - at.z);
		} else lastFollowOffset.set(Double.NaN, 0, 0);
		seeThrough.frame();
		if (now - lastSaveCheck > 2_000_000_000L) {
			lastSaveCheck = now;
			if (tree.version() != savedVersion) saveSelections();
		}
		if (System.currentTimeMillis() > statusUntil) status = "";
	}

	/** Builds this frame's world overlay; called during level rendering. */
	public void buildOverlay() {
		overlay.clear();
		Minecraft mc = Minecraft.getInstance();
		if (!active) {
			// outside the editor: only what the player asked to see (selections, light levels), no tools
			if (overlaysInGame && mc.level != null && mc.player != null) {
				SelectionRenderer.render(this, overlay);
				lightOverlay.render(overlay, new Vector3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
			}
			return;
		}
		lightOverlay.render(overlay, camera.pos);
		SelectionRenderer.render(this, overlay);
		tool.render(overlay);
		if (camera.drag() == EditorCamera.Drag.ORBIT) {
			// arcball pivot marker
			Vector3d p = camera.pivot();
			double s = Math.max(0.15, p.distance(camera.pos) * 0.012);
			overlay.cube(Overlay.Layer.ON_TOP, p, s, 0xE0FFFFFF, 0xFF000000, 1.5f);
			for (int i = 0; i < 3; i++) {
				Vector3d d = new Vector3d(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0).mul(s * 4);
				overlay.line(Overlay.Layer.ON_TOP, new Vector3d(p).sub(d), new Vector3d(p).add(d), dev.plainsight.editor.gizmo.TransformGizmo.COLORS[i], 2f);
			}
		}
		pathDemo.render(overlay);
	}

	public void setStatus(String s) {
		status = s;
		statusUntil = System.currentTimeMillis() + 4000;
	}

	// -- persistence ----------------------------------------------------------------------------------------

	private Path selectionsFile() {
		Minecraft mc = Minecraft.getInstance();
		String key;
		if (mc.getSingleplayerServer() != null) {
			key = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getCurrentServer() != null) {
			key = "mp_" + mc.getCurrentServer().ip;
		} else {
			key = "unknown";
		}
		key = key.replaceAll("[^A-Za-z0-9._-]", "_");
		return FabricLoader.getInstance().getConfigDir().resolve(Plainsight.ID).resolve("selections").resolve(key + ".json");
	}

	private void loadSelections() {
		Path f = selectionsFile();
		if (f.equals(storeFile)) return;
		storeFile = f;
		try {
			SelectionStore.load(tree, f);
		} catch (Exception e) {
			Plainsight.LOG.warn("Could not load selections from {}", f, e);
			tree.clear();
		}
		boxTool.stopEditing();
		savedVersion = tree.version();
	}

	public void saveSelections() {
		if (storeFile == null) return;
		try {
			SelectionStore.save(tree, storeFile);
			savedVersion = tree.version();
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not save selections to {}", storeFile, e);
		}
	}

	/** Called when leaving a world: forget which file the tree belongs to. */
	public void onDisconnect() {
		if (active) disable();
		dev.plainsight.guess.GuessWorld.shutdown();
		saveSelections();
		storeFile = null;
		tree.clear();
		boxTool.stopEditing();
	}

	// -- tree helpers ---------------------------------------------------------------------------------------

	/** The group new selections go into: the selected group, or the parent of the selected node. */
	public @Nullable GroupNode targetGroup() {
		SelectionNode last = null;
		for (SelectionNode n : tree.selected()) last = n;
		if (last instanceof GroupNode g) return g;
		if (last != null) return last.parent() == tree.root() ? null : last.parent();
		return null;
	}

	public void deleteSelected() {
		for (SelectionNode n : tree.topLevel(List.copyOf(tree.selected()))) {
			if (boxTool.editing() != null && (n == boxTool.editing() || n.isAncestorOf(boxTool.editing()))) boxTool.stopEditing();
			tree.remove(n);
		}
	}

	/** Moves the camera so the node is in front of it. */
	public void focus(SelectionNode node) {
		double[] b = SelectionRenderer.bounds(this, node);
		if (b == null) return;
		Vector3d c = new Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
		double r = Math.max(2, new Vector3d(b[3] - b[0], b[4] - b[1], b[5] - b[2]).length() / 2);
		double dist = r / Math.tan(Math.toRadians(fov()) / 2) + 1;
		Vector3d f = camera.forward(new Vector3d());
		camera.pos.set(c).fma(-dist, f);
	}

	// -- world queries ----------------------------------------------------------------------------------------

	public double fov() {
		return Minecraft.getInstance().gameRenderer.mainCamera().getFov();
	}

	public Vector3d rayAt(double px, double py) {
		return viewport.ray(camera, px, py, fov(), new Vector3d());
	}

	public Vector3d mouseRay() {
		return rayAt(mouseX, mouseY);
	}

	public @Nullable BlockHitResult raycastBlock(Vector3dc dir, boolean fluids) {
		return raycastBlock(camera.pos, dir, fluids);
	}

	public @Nullable BlockHitResult raycastBlock(Vector3dc origin, Vector3dc dir, boolean fluids) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3 from = new Vec3(origin.x(), origin.y(), origin.z());
		Vec3 to = from.add(dir.x() * REACH, dir.y() * REACH, dir.z() * REACH);
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				fluids ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? null : hit;
	}

	/**
	 * What the cursor is on: a block in the loaded chunks, or else (or when it is clearly nearer) the
	 * surface drawn there, from the depth buffer, so Voxy's LOD and guessed terrain can be aimed at too.
	 * {@code dir} must be the ray through the cursor. The block of a surface hit is the one just behind it.
	 */
	public @Nullable BlockHitResult raycastUnderCursor(Vector3dc dir) {
		return raycastUnderCursor(camera.pos, dir);
	}

	/** As {@link #raycastUnderCursor(Vector3dc)}, from {@code from} on the cursor ray (surfaces before it are skipped). */
	public @Nullable BlockHitResult raycastUnderCursor(Vector3dc from, Vector3dc dir) {
		BlockPos start = BlockPos.containing(from.x(), from.y(), from.z());
		// in the solid blocks of distant (LOD or guessed) terrain: they are not drawn from inside, so the
		// depth buffer would look straight through them; hit them here like loaded rock
		if (!isLoaded(start) && solidAt(start)) return new BlockHitResult(new Vec3(from.x(), from.y(), from.z()), net.minecraft.core.Direction.UP, start, true);
		BlockHitResult block = raycastBlock(from, dir, false);
		Vector3d surface = dev.plainsight.render.DepthPick.at(mouseX, mouseY);
		double skip = new Vector3d(from).distance(camera.pos);
		if (surface == null || surface.distance(camera.pos) < skip - 0.5) return block;
		double surfaceDistance = surface.distance(camera.pos);
		if (block != null && block.getLocation().distanceTo(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z)) <= surfaceDistance + 1.5) return block;
		Vector3d inside = new Vector3d(surface).fma(0.05, dir);
		BlockPos pos = BlockPos.containing(inside.x, inside.y, inside.z);
		double ax = Math.abs(dir.x()), ay = Math.abs(dir.y()), az = Math.abs(dir.z());
		net.minecraft.core.Direction face = ax >= ay && ax >= az ? (dir.x() > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST)
				: ay >= az ? (dir.y() > 0 ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP)
				: (dir.z() > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH);
		return new BlockHitResult(new Vec3(surface.x, surface.y, surface.z), face, pos, false);
	}

	/**
	 * A pivot when nothing drawn is under the cursor (beyond the farthest LOD): where the ray meets sea
	 * level if it points down, else a point 10 blocks ahead.
	 */
	public Vector3d beyondEverything(Vector3dc from, Vector3dc dir) {
		Minecraft mc = Minecraft.getInstance();
		double sea = mc.level != null ? mc.level.getSeaLevel() : 63;
		if (dir.y() < -1e-3 && from.y() > sea) {
			// near the horizon the sea is very far away: no further than 8192 blocks
			double t = Math.min((sea - from.y()) / dir.y(), 8192);
			return new Vector3d(from).fma(t, dir);
		}
		return new Vector3d(from).fma(10, dir);
	}

	public static boolean isLoaded(BlockPos p) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getChunkSource().hasChunk(p.getX() >> 4, p.getZ() >> 4);
	}

	/** Whether the block at {@code p} is solid: from the world where it is loaded, else from Voxy's LOD data. */
	public static boolean solidAt(BlockPos p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		if (isLoaded(p)) return !mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
		var state = dev.plainsight.guess.VoxyGuess.lodBlock(p.getX(), p.getY(), p.getZ());
		return state != null && state.isSolid();
	}

	public record EntityHit(Entity entity, double distance) {}

	public @Nullable EntityHit raycastEntity(Vector3dc dir, double maxDistance) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3 from = new Vec3(camera.pos.x, camera.pos.y, camera.pos.z);
		Vec3 to = from.add(dir.x() * maxDistance, dir.y() * maxDistance, dir.z() * maxDistance);
		EntityHit best = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			AABB box = renderBox(e).inflate(Math.max(0.1, e.getPickRadius()));
			Optional<Vec3> p = box.contains(from) ? Optional.of(from) : box.clip(from, to);
			if (p.isEmpty()) continue;
			double d = p.get().distanceTo(from);
			if (best == null || d < best.distance()) best = new EntityHit(e, d);
		}
		return best;
	}

	/**
	 * Starts an arcball drag. The pivot is the followed entity if there is one, else the entity or block
	 * under the cursor. An entity pivot moves with the entity.
	 */
	private void beginOrbit() {
		Entity target = followed();
		Vector3d pivot;
		if (target == null) {
			Vector3d dir = rayAt(mouseX, mouseY);
			// Ctrl+Shift: look past the rock the camera is buried in (e.g. orbit a cave from underground)
			Vector3d from = shift ? outOfSolid(camera.pos, dir) : new Vector3d(camera.pos);
			BlockHitResult block = raycastUnderCursor(from, dir);
			double max = block != null ? block.getLocation().distanceTo(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z)) : REACH;
			EntityHit hit = raycastEntity(dir, max);
			if (hit != null) {
				target = hit.entity();
			}
			if (target != null) pivot = centre(target);
			else if (block != null) pivot = new Vector3d(block.getLocation().x, block.getLocation().y, block.getLocation().z);
			else pivot = beyondEverything(from, dir);
		} else {
			pivot = centre(target);
		}
		orbitEntity = target;
		camera.beginDrag(EditorCamera.Drag.ORBIT, pivot);
		// an entity pivot keeps the view on the entity afterwards too (stop with the "Following" chip)
		if (target != null && !target.getUUID().equals(follow)) follow(target);
		else if (target == null && follow != null) follow(null);
	}

	/**
	 * The first point along the ray (from {@code from}) that is outside solid blocks: the camera's own
	 * surroundings are skipped when it is inside rock. Returns {@code from} when it is not in a block.
	 */
	public Vector3d outOfSolid(Vector3dc from, Vector3dc dir) {
		Minecraft mc = Minecraft.getInstance();
		Vector3d p = new Vector3d(from);
		if (mc.level == null) return p;
		BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
		for (double t = 0; t < 256; t += 0.1) {
			p.set(from).fma(t, dir);
			bp.set(p.x, p.y, p.z);
			if (!solidAt(bp)) return p;
		}
		return new Vector3d(from);
	}

	/** Centre of the entity where it is drawn this frame. */
	public static Vector3d centre(Entity e) {
		AABB b = renderBox(e);
		return new Vector3d((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2);
	}

	/**
	 * The entity's box where it is drawn this frame: entities move once per game tick and are drawn
	 * interpolated between ticks, so the tick box would step while the entity glides.
	 */
	public static AABB renderBox(Entity e) {
		Vec3 at = e.getPosition(partialTick);
		return e.getBoundingBox().move(at.x - e.getX(), at.y - e.getY(), at.z - e.getZ());
	}

	/** The entity being orbited right now (during a Ctrl+LMB drag), if any. */
	public @Nullable Entity orbitEntity() {
		return camera.drag() == EditorCamera.Drag.ORBIT ? orbitEntity : null;
	}

	/** The entity the camera follows (keeps its offset to), or null. */
	public @Nullable Entity followed() {
		Minecraft mc = Minecraft.getInstance();
		if (follow == null || mc.level == null) return null;
		for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(follow)) return e;
		return null;
	}

	public void follow(@Nullable Entity e) {
		follow = e == null ? null : e.getUUID();
		followName = e == null ? "" : dev.plainsight.editor.tool.EntitySelectTool.of(e).label();
		followLast = e == null ? null : centre(e);
	}

	public String followName() {
		return followName;
	}

	/** Keeps the camera's offset to a followed or orbited entity while it moves. */
	private void trackEntities() {
		Entity f = followed();
		if (follow != null && f == null) {
			// gone (unloaded or dead): stop following
			follow(null);
		}
		Entity moving = orbitEntity() != null ? orbitEntity : f;
		if (moving == null || moving.isRemoved()) return;
		Vector3d c = centre(moving);
		if (camera.drag() == EditorCamera.Drag.ORBIT && moving == orbitEntity) {
			camera.movePivot(c);
		} else if (followLast != null) {
			camera.pos.add(c.x - followLast.x, c.y - followLast.y, c.z - followLast.z);
		}
		followLast = c;
	}

	/** Point under the cursor (block hit), or a point at {@code fallback} distance along the ray. */
	public Vector3d pointUnderCursor(double fallback) {
		Vector3d dir = mouseRay();
		BlockHitResult hit = raycastUnderCursor(dir);
		if (hit != null) {
			Vec3 l = hit.getLocation();
			return new Vector3d(l.x, l.y, l.z);
		}
		return new Vector3d(camera.pos).fma(fallback, dir);
	}

	// -- input (from the mixins; only while active and no screen is open) ----------------------------------

	public void onMouseMove(double x, double y) {
		mouseX = x;
		mouseY = y;
		if (camera.drag() != EditorCamera.Drag.NONE) {
			camera.dragBy(x - lastDragX, y - lastDragY, viewport.worldPerPixel(fov()));
			lastDragX = x;
			lastDragY = y;
		}
		if (Math.abs(x - pressX) + Math.abs(y - pressY) > 4) movedSincePress = true;
		tool.mouseMoved(x, y);
	}

	/** SDL buttons: 1 left, 2 middle, 3 right. */
	public void onMouseButton(int button, boolean down) {
		if (!down) {
			boolean lookClick = button == 1 && camera.drag() == EditorCamera.Drag.LOOK && !movedSincePress;
			if (camera.drag() != EditorCamera.Drag.NONE && (button == 1 || button == 3)) camera.endDrag();
			tool.mouseReleased(button);
			// a plain click on nothing (no handle, no drag) deselects, like clicking empty space elsewhere
			if (lookClick) tool.clickedNothing();
			return;
		}
		if (ImGuiLayer.get().wantsMouse() || !viewport.contains(mouseX, mouseY)) return;
		lastDragX = mouseX;
		lastDragY = mouseY;
		pressX = mouseX;
		pressY = mouseY;
		movedSincePress = false;
		if (button == 1) {
			if (ctrl) {
				camera.down = false;
				beginOrbit();
			} else if (!tool.leftPressed()) camera.beginDrag(EditorCamera.Drag.LOOK, null);
		} else if (button == 3) {
			if (ctrl) camera.beginDrag(EditorCamera.Drag.PAN, pointUnderCursor(10));
			else tool.rightPressed();
		} else if (button == 2) {
			pickBlock();
		}
	}

	public void onScroll(double dy) {
		if (ImGuiLayer.get().wantsMouse() || !viewport.contains(mouseX, mouseY) || dy == 0) return;
		// arcball (after an orbit, or following an entity): zoom along the view to the pivot
		Entity f = followed();
		if (f != null) {
			camera.zoomTowards(dy, centre(f), 1.5);
			return;
		}
		if (camera.hasActivePivot()) {
			camera.zoomTowards(dy, camera.pivot(), 1.0);
			return;
		}
		Vector3d dir = mouseRay();
		BlockHitResult hit = raycastUnderCursor(dir);
		double dist = hit != null ? hit.getLocation().distanceTo(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z)) : 0;
		camera.dolly(dy, dir, dist);
	}

	private void pickBlock() {
		BlockHitResult hit = raycastUnderCursor(mouseRay());
		Minecraft mc = Minecraft.getInstance();
		if (hit == null || mc.level == null) return;
		BlockPos p = hit.getBlockPos();
		String what = mc.level.getChunkSource().hasChunk(p.getX() >> 4, p.getZ() >> 4) ? mc.level.getBlockState(p).getBlock().getName().getString()
				: dev.plainsight.guess.GuessWorld.current() != null && dev.plainsight.guess.GuessWorld.current().isGuessed(p.getX() >> 4, p.getZ() >> 4)
				? "Guessed terrain" : "Distant terrain (LOD)";
		setStatus(what + " at " + p.getX() + ", " + p.getY() + ", " + p.getZ());
	}

	/**
	 * A key event. Returns true when the editor consumed it; false lets Minecraft handle it (chat, the
	 * pause menu, debug keys).
	 */
	public boolean onKey(int scancode, boolean down) {
		switch (scancode) {
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> {
				ctrl = down;
				if (down) camera.down = false;
			}
			case InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT -> shift = down;
			case InputConstants.KEY_LALT, InputConstants.KEY_RALT -> alt = down;
			default -> {
			}
		}
		// only text fields keep keys from the viewport; a focused panel alone does not
		boolean typing = ImGuiLayer.get().wantsTextInput();
		if (!down) {
			setMovementKey(scancode, false);
			return true;
		}
		if (typing) return true;
		if (setMovementKey(scancode, true)) return true;
		if (tool.keyPressed(scancode)) return true;
		switch (scancode) {
			case InputConstants.KEY_DELETE -> {
				deleteSelected();
				return true;
			}
			case InputConstants.KEY_ESCAPE -> {
				// Escape closes ImGui popups first; only an idle editor lets the game open its menu
				if (imgui.ImGui.isPopupOpen("", imgui.flag.ImGuiPopupFlags.AnyPopupId | imgui.flag.ImGuiPopupFlags.AnyPopupLevel)) return true;
				return tool.cancel();
			}
			default -> {
			}
		}
		Minecraft mc = Minecraft.getInstance();
		// chat, commands, debug and function keys stay with the game
		if (mc.options.keyChat.matches(new net.minecraft.client.input.KeyEvent(scancode, 0, 0))
				|| mc.options.keyCommand.matches(new net.minecraft.client.input.KeyEvent(scancode, 0, 0))) return false;
		return !(scancode >= InputConstants.KEY_F1 && scancode <= InputConstants.KEY_F12);
	}

	private boolean setMovementKey(int sc, boolean down) {
		switch (sc) {
			case InputConstants.KEY_W -> camera.forward = down;
			case InputConstants.KEY_S -> camera.back = down;
			case InputConstants.KEY_A -> camera.left = down;
			case InputConstants.KEY_D -> camera.right = down;
			case InputConstants.KEY_SPACE -> camera.up = down;
			// Shift is the gizmo's "free move" modifier while dragging, not "fly down"
			// Shift is the gizmo's "free move" modifier while dragging and the "through rock" modifier with
			// Ctrl (arcball), not "fly down" then
			case InputConstants.KEY_LSHIFT -> camera.down = down && !tool.isDragging() && !ctrl && camera.drag() == EditorCamera.Drag.NONE;
			case InputConstants.KEY_LCONTROL -> camera.fast = down;
			default -> {
				return false;
			}
		}
		return true;
	}

	/**
	 * A box clicked in the selection tree: switches to the box tool to show its gizmo. Putting it down
	 * again deselects it and restores the tool that was active before (see {@link #onBoxPutDown}).
	 */
	public void editBox(BoxNode box) {
		Tool before = toolBeforeTreeEdit != null ? toolBeforeTreeEdit : tool;
		setTool(boxTool);
		toolBeforeTreeEdit = before;
		boxTool.edit(box);
	}

	/** The box tool stopped editing {@code box}: deselect it in the tree, give back the earlier tool. */
	public void onBoxPutDown(BoxNode box) {
		tree.deselect(box);
		if (switchingTool || toolBeforeTreeEdit == null) return;
		Tool restore = toolBeforeTreeEdit;
		toolBeforeTreeEdit = null;
		setTool(restore);
	}
}
