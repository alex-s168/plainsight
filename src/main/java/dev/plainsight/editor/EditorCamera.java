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
	/** Scroll targets nearer than this (blocks) are ones the camera is in or against: zooming goes through them. */
	public static final double DOLLY_NEAR = 1.5;

	public enum Drag {NONE, LOOK, ORBIT, PAN}

	public final Vector3d pos = new Vector3d();
	public float yaw, pitch;
	public float speed = 1f;
	/** Scales keyboard movement on top of {@link #speed}: the editor map pans faster the higher it is (#52). */
	public double moveScale = 1;
	public boolean forward, back, left, right, up, down, fast;

	private final Vector3d velocity = new Vector3d();
	/** Scroll movement still to be made (applied smoothly over a fraction of a second). */
	private final Vector3d pendingZoom = new Vector3d();
	/** Rate of the smooth zoom (1/s): about 95% of a step in 0.15 s. */
	public static final double ZOOM_RATE = 20;
	private Drag drag = Drag.NONE;
	/** While true the view turns towards the orbit pivot until it is in the centre. */
	private boolean centering;
	/**
	 * Arcball mode: set by an orbit, kept until the view is moved some other way (look, pan, flying).
	 * While set, zooming moves towards/away from the pivot, so the view's centre stays where it is.
	 */
	private boolean pivotActive;
	/** Rate of the centring turn (1/s): about 95% of the way in 0.1 s. */
	public static final double CENTER_RATE = 30;
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
		pendingZoom.zero();
		// a jump ends arcball mode: later scrolling must not zoom towards an old (maybe far) pivot
		pivotActive = false;
		centering = false;
	}

	/** Finishes a zoom in progress at once. */
	public void settle() {
		pos.add(pendingZoom);
		pendingZoom.zero();
	}

	/** Where the camera ends up once the pending zoom is done. */
	public Vector3d target(Vector3d dest) {
		return dest.set(pos).add(pendingZoom);
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
		pos.add(pendingZoom);
		pendingZoom.zero();
	}

	/** Integrates keyboard flight; {@code dt} in seconds. */
	public void update(double dt) {
		dt = Math.min(dt, 0.1);
		center(dt);
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
		if (wish.lengthSquared() > 0) {
			wish.normalize().mul(BASE_SPEED * speed * moveScale * (fast ? 3 : 1));
			// flying turns no longer towards the pivot (that would circle around it)
			if (drag != Drag.ORBIT) centering = false;
		}
		// exponential approach gives a short, smooth acceleration and stop
		double k = 1 - Math.exp(-dt * 14);
		velocity.lerp(wish, k);
		if (velocity.lengthSquared() < 1e-6 && wish.lengthSquared() == 0) velocity.zero();
		pos.fma(dt, velocity);
		if (pendingZoom.lengthSquared() > 0) {
			double z = 1 - Math.exp(-dt * ZOOM_RATE);
			if (pendingZoom.lengthSquared() < 1e-6) z = 1;
			pos.fma(z, pendingZoom);
			pendingZoom.mul(1 - z);
		}
		if (velocity.lengthSquared() > 1e-4) pivotActive = false;
	}

	public boolean hasActivePivot() {
		return pivotActive;
	}

	/**
	 * Arcball zoom: moves along the line to {@code target} (20% of the distance per notch, at least one
	 * block), never closer than {@code minDistance}, so the target stays at the centre of the view.
	 */
	public void zoomTowards(double notches, Vector3dc target, double minDistance) {
		Vector3d from = target(new Vector3d());
		Vector3d d = new Vector3d(target).sub(from);
		double dist = d.length();
		if (dist < 1e-6) return;
		d.div(dist);
		double step = Math.max(1.0, dist * 0.2) * notches * Math.max(0.25, speed);
		if (notches > 0) step = Math.min(step, Math.max(0, dist - minDistance));
		pendingZoom.fma(step, d);
	}

	// -- mouse drags ----------------------------------------------------------------------------------------

	/** @param pivotPoint point under the cursor (for orbit and pan); may be null for look */
	public void beginDrag(Drag mode, Vector3dc pivotPoint) {
		// finish a zoom still in progress first: drags work from where the camera is
		pos.add(pendingZoom);
		pendingZoom.zero();
		drag = mode;
		centering = mode == Drag.ORBIT;
		pivotActive = mode == Drag.ORBIT;
		if (pivotPoint != null) pivot.set(pivotPoint);
		else forward(pivot).mul(10).add(pos);
		panDistance = Math.max(0.5, pivot.distance(pos));
	}

	public void endDrag() {
		drag = Drag.NONE;
		// the turn towards the pivot belongs to the drag: flying afterwards must not keep turning to it
		centering = false;
	}

	public boolean isCentering() {
		return centering;
	}

	/** Yaw/pitch that look from the camera at {@code target}. */
	public float[] lookAt(Vector3dc target) {
		Vector3d d = new Vector3d(target).sub(pos);
		double h = Math.sqrt(d.x * d.x + d.z * d.z);
		float y = h < 1e-9 && Math.abs(d.y) > 0 ? yaw : (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float p = (float) Math.toDegrees(Math.atan2(-d.y, h));
		return new float[]{y, clampPitch(p)};
	}

	/** Turns smoothly towards the pivot; done once it is (almost exactly) in the centre. */
	private void center(double dt) {
		if (!centering) return;
		if (pivot.distanceSquared(pos) < 1e-4) {
			centering = false;
			return;
		}
		float[] target = lookAt(pivot);
		float dYaw = wrapDegrees(target[0] - yaw), dPitch = target[1] - pitch;
		if (Math.abs(dYaw) < 0.05f && Math.abs(dPitch) < 0.05f) {
			yaw = target[0];
			pitch = target[1];
			centering = false;
			return;
		}
		double k = 1 - Math.exp(-dt * CENTER_RATE);
		yaw += (float) (dYaw * k);
		pitch = clampPitch(pitch + (float) (dPitch * k));
	}

	static float wrapDegrees(float d) {
		d %= 360;
		if (d >= 180) d -= 360;
		if (d < -180) d += 360;
		return d;
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
	 * A target closer than {@link #DOLLY_NEAR} (the camera is in or against it, often after zooming out
	 * into terrain) does not stop the zoom: it would allow no step at all (#28).
	 */
	public void dolly(double notches, Vector3dc dir, double targetDistance) {
		if (targetDistance < DOLLY_NEAR) targetDistance = 0;
		// the distance still to go after the pending part of earlier steps
		double dist = Math.max(0, targetDistance - pendingZoom.dot(dir));
		double step = Math.max(1.0, dist * 0.2) * notches * Math.max(0.25, speed);
		if (notches > 0 && targetDistance > 0) step = Math.min(step, Math.max(0, dist - 1.0));
		pendingZoom.fma(step, dir);
	}
}
