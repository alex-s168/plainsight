package dev.plainsight.editor;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Free editor camera. Angles follow Minecraft's convention (yaw 0 looks towards +Z, positive pitch looks
 * down). Controls (driven by {@link Editor}):
 * <ul>
 *   <li>LMB drag: look around; Ctrl+LMB drag: arcball around the point (or entity) under the cursor,
 *   keeping the horizon level (no tilt);</li>
 *   <li>Ctrl+RMB drag: pan, keeping the grabbed point under the cursor;</li>
 *   <li>scroll: move towards/away from the point under the cursor;</li>
 *   <li>WASD horizontal flight, Space/Shift up/down, Ctrl faster; the speed slider scales all of it.</li>
 * </ul>
 */
public final class EditorCamera {
	public static final double BASE_SPEED = 10.0;
	public static final float LOOK_DEGREES_PER_PIXEL = 0.2f;

	public enum Drag {NONE, LOOK, ORBIT, PAN}

	public final Vector3d pos = new Vector3d();
	public float yaw, pitch;
	public float speed = 1f;
	public boolean forward, back, left, right, up, down, fast;

	private final Vector3d velocity = new Vector3d();
	private Drag drag = Drag.NONE;
	private final Vector3d pivot = new Vector3d();
	private double panDistance;

	public Drag drag() {
		return drag;
	}

	public void set(Vector3dc p, float yaw, float pitch) {
		pos.set(p);
		this.yaw = yaw;
		this.pitch = clampPitch(pitch);
		velocity.zero();
	}

	public Vector3d pivot() {
		return new Vector3d(pivot);
	}

	public static float clampPitch(float p) {
		return Math.max(-90f, Math.min(90f, p));
	}

	/** Orientation in Minecraft's camera convention: local -z looks forward, +y is up, +x is right. */
	public Quaterniond orientation(Quaterniond dest) {
		return dest.rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), 0);
	}

	/** Moves the orbit pivot (e.g. following an entity) and the camera with it. */
	public void movePivot(Vector3dc to) {
		pos.add(to.x() - pivot.x, to.y() - pivot.y, to.z() - pivot.z);
		pivot.set(to);
	}

	public void setPivot(Vector3dc p) {
		pivot.set(p);
	}

	public Vector3d forward(Vector3d dest) {
		return orientation(new Quaterniond()).transform(dest.set(0, 0, -1));
	}

	public static Vector3d direction(float yaw, float pitch, Vector3d dest) {
		double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
		return dest.set(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/** Screen-right direction. */
	public Vector3d right(Vector3d dest) {
		return orientation(new Quaterniond()).transform(dest.set(1, 0, 0));
	}

	/** Screen-up direction. */
	public Vector3d up(Vector3d dest) {
		return orientation(new Quaterniond()).transform(dest.set(0, 1, 0));
	}

	public void stopMovement() {
		forward = back = left = right = up = down = fast = false;
		velocity.zero();
	}

	/** Integrates keyboard flight; {@code dt} in seconds. */
	public void update(double dt) {
		dt = Math.min(dt, 0.1);
		Vector3d wish = new Vector3d();
		double yr = Math.toRadians(yaw);
		Vector3d flatForward = new Vector3d(-Math.sin(yr), 0, Math.cos(yr));
		Vector3d flatRight = new Vector3d(-Math.cos(yr), 0, -Math.sin(yr));
		if (forward) wish.add(flatForward);
		if (back) wish.sub(flatForward);
		if (right) wish.add(flatRight);
		if (left) wish.sub(flatRight);
		if (up) wish.y += 1;
		if (down) wish.y -= 1;
		if (wish.lengthSquared() > 0) wish.normalize().mul(BASE_SPEED * speed * (fast ? 3 : 1));
		// exponential approach gives a short, smooth acceleration and stop
		double k = 1 - Math.exp(-dt * 14);
		velocity.lerp(wish, k);
		if (velocity.lengthSquared() < 1e-6 && wish.lengthSquared() == 0) velocity.zero();
		pos.fma(dt, velocity);
	}

	// -- mouse drags ----------------------------------------------------------------------------------------

	/** @param pivotPoint point under the cursor (for orbit and pan); may be null for look */
	public void beginDrag(Drag mode, Vector3dc pivotPoint) {
		drag = mode;
		if (pivotPoint != null) pivot.set(pivotPoint);
		else forward(pivot).mul(10).add(pos);
		panDistance = Math.max(0.5, pivot.distance(pos));
	}

	public void endDrag() {
		drag = Drag.NONE;
	}

	/**
	 * Applies a mouse movement of (dx, dy) pixels. {@code worldPerPixel} is the size of one pixel at unit
	 * distance (used for panning).
	 */
	public void dragBy(double dx, double dy, double worldPerPixel) {
		switch (drag) {
			case LOOK -> {
				yaw += (float) (dx * LOOK_DEGREES_PER_PIXEL);
				pitch = clampPitch(pitch + (float) (dy * LOOK_DEGREES_PER_PIXEL));
			}
			case ORBIT -> orbit(dx * LOOK_DEGREES_PER_PIXEL, dy * LOOK_DEGREES_PER_PIXEL);
			case PAN -> {
				double s = panDistance * worldPerPixel;
				Vector3d r = right(new Vector3d()), u = up(new Vector3d());
				pos.fma(-dx * s, r).fma(dy * s, u);
			}
			case NONE -> {
			}
		}
	}

	/** Rotates the camera around the pivot, keeping the pivot at the same place on screen. */
	public void orbit(double dYaw, double dPitch) {
		float newPitch = clampPitch(pitch + (float) dPitch);
		float newYaw = yaw + (float) dYaw;
		// express the pivot offset in the camera frame, then rebuild it with the new angles
		Vector3d off = new Vector3d(pos).sub(pivot);
		Vector3d f = forward(new Vector3d()), r = right(new Vector3d()), u = up(new Vector3d());
		double a = off.dot(r), b = off.dot(u), c = off.dot(f);
		yaw = newYaw;
		pitch = newPitch;
		Vector3d f2 = forward(new Vector3d()), r2 = right(new Vector3d()), u2 = up(new Vector3d());
		pos.set(pivot).fma(a, r2).fma(b, u2).fma(c, f2);
	}

	/**
	 * Scroll towards ({@code notches > 0}) or away from {@code target} along {@code dir}, the ray through
	 * the cursor. Moves 20% of the distance per notch but at least one block; never passes the target.
	 */
	public void dolly(double notches, Vector3dc dir, double targetDistance) {
		double dist = Math.max(0, targetDistance);
		double step = Math.max(1.0, dist * 0.2) * notches * Math.max(0.25, speed);
		if (notches > 0 && dist > 0) step = Math.min(step, Math.max(0, dist - 1.0));
		pos.fma(step, dir);
	}
}
