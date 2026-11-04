package dev.plainsight.editor;

import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The editor's map (M): the camera looks down at the minimap's angle, north up, through a narrow field
 * of view (a slightly 3D map, like the minimap's); the wheel zooms towards the point under the cursor
 * and dragging moves the map. With Voxy the view is drawn like the minimap (the player's layer, daylight;
 * see Minimap's editor map pass). The 3D camera comes back when the map is left. Zoom and drag work on a
 * horizontal plane at the player's height.
 */
public final class MapMode {
	/** Field of view of the map camera (degrees): narrow, so it looks almost like a flat map. */
	public static final float FOV = 40;
	/** Degrees down from the horizon, as the minimap. */
	public static final float PITCH = dev.plainsight.minimap.Minimap.PITCH;
	public static final double MIN_HEIGHT = 12, MAX_HEIGHT = 6000;
	private boolean active;
	private final Vector3d saved = new Vector3d();
	private float savedYaw, savedPitch;
	/** Height of the plane zoom and drag work on (the player's feet), and the camera's height above it. */
	private double planeY, height = 160;
	private boolean dragging;
	private final Vector3d dragPoint = new Vector3d();

	public boolean active() {
		return active;
	}

	public boolean dragging() {
		return dragging;
	}

	/** Height of the camera above the player's level (tests). */
	public double height() {
		return height;
	}

	public void toggle(Editor editor) {
		if (active) exit(editor);
		else enter(editor);
	}

	public void enter(Editor editor) {
		if (active) return;
		var c = editor.camera;
		saved.set(c.pos);
		savedYaw = c.yaw;
		savedPitch = c.pitch;
		editor.follow(null);
		var player = Minecraft.getInstance().player;
		planeY = player != null ? player.getY() : c.pos.y - 64;
		// looking at what the 3D camera looked at, from the south and above, north up
		Vector3d at = editor.pointUnderCursor(64);
		active = true;
		c.stopMovement();
		c.set(new Vector3d(at.x, planeY + height, at.z + height / Math.tan(Math.toRadians(PITCH))), 180, PITCH);
		editor.setStatus("Map: scroll to zoom, drag to move, M for the 3D view");
	}

	public void exit(Editor editor) {
		if (!active) return;
		active = false;
		dragging = false;
		editor.camera.stopMovement();
		editor.camera.set(saved, savedYaw, savedPitch);
	}

	/** Where the cursor ray meets the map's plane (null if it does not point down). */
	private Vector3d onPlane(Vector3dc from, Vector3dc dir) {
		if (dir.y() > -1e-4) return null;
		double t = (planeY - from.y()) / dir.y();
		return new Vector3d(from).fma(t, dir);
	}

	/** Zooms towards the point under the cursor (it stays under the cursor). */
	public void zoom(Editor editor, double notches, Vector3dc dir) {
		var c = editor.camera;
		Vector3d p = onPlane(c.pos, dir);
		height = Math.clamp(height * Math.pow(1.25, -notches), MIN_HEIGHT, MAX_HEIGHT);
		double y = planeY + height;
		if (p == null) {
			c.pos.y = y;
			return;
		}
		double t = (planeY - y) / dir.y();
		c.pos.set(p.x - dir.x() * t, y, p.z - dir.z() * t);
	}

	public void beginDrag(Editor editor, Vector3dc dir) {
		Vector3d p = onPlane(editor.camera.pos, dir);
		if (p == null) return;
		dragPoint.set(p);
		dragging = true;
	}

	/** Moves the map so the point grabbed stays under the cursor. */
	public void dragTo(Editor editor, Vector3dc dir) {
		if (!dragging) return;
		Vector3d p = onPlane(editor.camera.pos, dir);
		if (p == null) return;
		editor.camera.pos.add(dragPoint.x - p.x, 0, dragPoint.z - p.z);
	}

	public void endDrag() {
		dragging = false;
	}

	/** Leaving the editor or the world: the next editor starts in 3D. */
	public void reset() {
		active = false;
		dragging = false;
	}
}
