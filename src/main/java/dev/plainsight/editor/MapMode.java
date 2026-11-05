package dev.plainsight.editor;

import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The editor's map (M): the camera looks down at the minimap's angle, north up, through a narrow field
 * of view (a slightly 3D map, like the minimap's); the wheel zooms towards the point under the cursor
 * and dragging moves the map. Ctrl + LMB drag turns the map and tilts it (the minimap takes the tilt); both are kept. With Voxy the view is drawn like the minimap (the player's layer, daylight;
 * see Minimap's editor map pass). The 3D camera comes back when the map is left. Zoom and drag work on a
 * horizontal plane at the player's height.
 */
public final class MapMode {
	/** Field of view of the map camera (degrees): narrow, so it looks almost like a flat map. */
	public static final float FOV = 40;
	/** Tilt limits (degrees down from the horizon): fully top-down, and 10 degrees above the side view. */
	public static final float MIN_PITCH = 10, MAX_PITCH = 90;
	/** Degrees of turn and of tilt per pixel of mouse movement with Ctrl; the tilt as fast as the arcball camera's (#51). */
	static final double TURN_PER_PIXEL = 0.3, TILT_PER_PIXEL = EditorCamera.LOOK_DEGREES_PER_PIXEL;
	/** The map's direction: north up (yaw 180) until it is turned; kept (settings of the minimap module). */
	public static volatile float yaw = 180;
	private boolean rotating;
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

	/** Distance from the camera to the map's plane along the view (the "zoom", in metres, as the minimap's). */
	public double viewDistance() {
		return height / Math.sin(Math.toRadians(tilt()));
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
		// looking at what the 3D camera looked at
		enter(editor, editor.pointUnderCursor(64));
	}

	/** Enters the map looking at {@code at}, from the south and above, north up. */
	public void enter(Editor editor, Vector3d at) {
		if (active) return;
		var c = editor.camera;
		saved.set(c.pos);
		savedYaw = c.yaw;
		savedPitch = c.pitch;
		editor.follow(null);
		var player = Minecraft.getInstance().player;
		planeY = player != null ? player.getY() : c.pos.y - 64;
		active = true;
		c.stopMovement();
		float pitch = tilt();
		Vector3d dir = dev.plainsight.editor.EditorCamera.direction(yaw, pitch, new Vector3d());
		// from above (at the map's height) and behind the point, in the map's direction
		c.set(new Vector3d(at.x, planeY, at.z).fma(-height / Math.sin(Math.toRadians(pitch)), dir), yaw, pitch);
		editor.setStatus("Map: scroll to zoom, drag to move, Ctrl + drag to turn and tilt, " + dev.plainsight.Plainsight.mapKeyName() + " for the 3D view");
	}

	public void exit(Editor editor) {
		if (!active) return;
		active = false;
		dragging = false;
		editor.camera.stopMovement();
		editor.camera.set(saved, savedYaw, savedPitch);
	}

	/** The map's tilt, shared with the minimap. */
	private static float tilt() {
		return Math.clamp(dev.plainsight.minimap.Minimap.pitch, MIN_PITCH, MAX_PITCH);
	}

	public boolean rotating() {
		return rotating;
	}

	/** Ctrl + LMB drag: turning and tilting the map (see {@link #rotate}). */
	public void beginRotate() {
		rotating = true;
	}

	public void endRotate() {
		rotating = false;
	}

	/**
	 * Ctrl + LMB drag: turns the map around the up axis through the point at the view's centre (left/right)
	 * and tilts it (down: towards top-down, as fast as dragging the arcball camera). The minimap takes the tilt.
	 */
	public void rotate(Editor editor, double dx, double dy) {
		var c = editor.camera;
		Vector3d centre = onPlane(c.pos, c.forward(new Vector3d()));
		if (centre == null) return;
		double dist = c.pos.distance(centre);
		yaw = (float) (c.yaw + dx * TURN_PER_PIXEL);
		float pitch = (float) Math.clamp(c.pitch + dy * TILT_PER_PIXEL, MIN_PITCH, MAX_PITCH);
		dev.plainsight.minimap.Minimap.pitch = pitch;
		Vector3d dir = dev.plainsight.editor.EditorCamera.direction(yaw, pitch, new Vector3d());
		Vector3d pos = new Vector3d(centre).fma(-dist, dir);
		c.set(pos, yaw, pitch);
		height = pos.y - planeY;
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

	/** Leaving the editor or the world: the next editor starts in 3D (the map keeps its rotation and tilt). */
	public void reset() {
		rotating = false;
		active = false;
		dragging = false;
	}
}
