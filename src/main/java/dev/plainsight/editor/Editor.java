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
	private Tool tool = boxTool;

	public final FluidTransparency fluids = new FluidTransparency();
	public final SeeThrough seeThrough = new SeeThrough(this);
	public final BlockOverlayDemo blockOverlayDemo = new BlockOverlayDemo(this);
	public final PathOverlayDemo pathDemo = new PathOverlayDemo(this);
	/** Toggleable modules shown in the tool bar (the viewport buttons cover fluids and see-through). */
	public final List<Module> modules = List.of(blockOverlayDemo, pathDemo);

	/** Mouse position in window coordinates (ImGui's coordinate space). */
	public double mouseX, mouseY;
	private double pressX, pressY;
	private boolean movedSincePress;
	/** Arcball pivot: the point under the cursor, or (like Axiom's option) the point at the viewport centre. */
	public boolean arcballAtViewportCenter;
	private @Nullable Entity orbitEntity;
	private java.util.@Nullable UUID follow;
	private String followName = "";
	private @Nullable Vector3d followLast;
	private double lastDragX, lastDragY;
	public boolean ctrl, shift, alt;
	private long lastFrameNanos;
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
			tool.deactivate();
			tool = t;
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
	public void frame() {
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
		if (!active) {
			// outside the editor: only what the player asked to see (the selection tree), no tools
			if (overlaysInGame && Minecraft.getInstance().level != null) SelectionRenderer.render(this, overlay);
			return;
		}
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
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3 from = new Vec3(camera.pos.x, camera.pos.y, camera.pos.z);
		Vec3 to = from.add(dir.x() * REACH, dir.y() * REACH, dir.z() * REACH);
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				fluids ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? null : hit;
	}

	public record EntityHit(Entity entity, double distance) {}

	public @Nullable EntityHit raycastEntity(Vector3dc dir, double maxDistance) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return null;
		Vec3 from = new Vec3(camera.pos.x, camera.pos.y, camera.pos.z);
		Vec3 to = from.add(dir.x() * maxDistance, dir.y() * maxDistance, dir.z() * maxDistance);
		EntityHit best = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			AABB box = e.getBoundingBox().inflate(Math.max(0.1, e.getPickRadius()));
			Optional<Vec3> p = box.contains(from) ? Optional.of(from) : box.clip(from, to);
			if (p.isEmpty()) continue;
			double d = p.get().distanceTo(from);
			if (best == null || d < best.distance()) best = new EntityHit(e, d);
		}
		return best;
	}

	/**
	 * Starts an arcball drag. The pivot is the followed entity if there is one, else the entity or block
	 * under the cursor (or at the viewport centre, if configured). An entity pivot moves with the entity.
	 */
	private void beginOrbit() {
		Entity target = followed();
		Vector3d pivot;
		if (target == null) {
			double px = arcballAtViewportCenter ? viewport.x + viewport.width / 2 : mouseX;
			double py = arcballAtViewportCenter ? viewport.y + viewport.height / 2 : mouseY;
			Vector3d dir = rayAt(px, py);
			BlockHitResult block = raycastBlock(dir, false);
			double max = block != null ? block.getLocation().distanceTo(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z)) : REACH;
			EntityHit hit = raycastEntity(dir, max);
			if (hit != null) {
				target = hit.entity();
			}
			if (target != null) pivot = centre(target);
			else if (block != null) pivot = new Vector3d(block.getLocation().x, block.getLocation().y, block.getLocation().z);
			else pivot = new Vector3d(camera.pos).fma(10, dir);
		} else {
			pivot = centre(target);
		}
		orbitEntity = target;
		camera.beginDrag(EditorCamera.Drag.ORBIT, pivot);
		// an entity pivot keeps the view on the entity afterwards too (stop with the "Following" chip)
		if (target != null && !target.getUUID().equals(follow)) follow(target);
		else if (target == null && follow != null) follow(null);
	}

	public static Vector3d centre(Entity e) {
		AABB b = e.getBoundingBox();
		return new Vector3d((b.minX + b.maxX) / 2, (b.minY + b.maxY) / 2, (b.minZ + b.maxZ) / 2);
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
		BlockHitResult hit = raycastBlock(dir, false);
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
			if (ctrl) beginOrbit();
			else if (!tool.leftPressed()) camera.beginDrag(EditorCamera.Drag.LOOK, null);
		} else if (button == 3) {
			if (ctrl) camera.beginDrag(EditorCamera.Drag.PAN, pointUnderCursor(10));
			else tool.rightPressed();
		} else if (button == 2) {
			pickBlock();
		}
	}

	public void onScroll(double dy) {
		if (ImGuiLayer.get().wantsMouse() || !viewport.contains(mouseX, mouseY) || dy == 0) return;
		Vector3d dir = mouseRay();
		BlockHitResult hit = raycastBlock(dir, false);
		double dist = hit != null ? hit.getLocation().distanceTo(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z)) : 0;
		camera.dolly(dy, dir, dist);
	}

	private void pickBlock() {
		BlockHitResult hit = raycastBlock(mouseRay(), false);
		Minecraft mc = Minecraft.getInstance();
		if (hit == null || mc.level == null) return;
		BlockPos p = hit.getBlockPos();
		setStatus(mc.level.getBlockState(p).getBlock().getName().getString() + " at " + p.getX() + ", " + p.getY() + ", " + p.getZ());
	}

	/**
	 * A key event. Returns true when the editor consumed it; false lets Minecraft handle it (chat, the
	 * pause menu, debug keys).
	 */
	public boolean onKey(int scancode, boolean down) {
		switch (scancode) {
			case InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL -> ctrl = down;
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
			case InputConstants.KEY_LSHIFT -> camera.down = down && !tool.isDragging();
			case InputConstants.KEY_LCONTROL -> camera.fast = down;
			default -> {
				return false;
			}
		}
		return true;
	}

	public void editBox(BoxNode box) {
		setTool(boxTool);
		boxTool.edit(box);
	}
}
