package dev.plainsight.editor.gizmo;

import dev.plainsight.render.Overlay;
import org.jspecify.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Translate/rotate gizmo: three axis arrows (red x, green y, blue z) that point to the camera's side of
 * the origin, three plane squares for moving in two axes, and optionally three rotation rings. The
 * gizmo keeps a constant size on screen. Pure geometry: picking and drag maths take rays, drawing goes
 * to an {@link Overlay}.
 */
public final class TransformGizmo {
	public enum Part {
		AXIS_X, AXIS_Y, AXIS_Z, PLANE_YZ, PLANE_XZ, PLANE_XY, RING_X, RING_Y, RING_Z;

		public boolean isAxis() {
			return ordinal() <= 2;
		}

		public boolean isPlane() {
			return ordinal() >= 3 && ordinal() <= 5;
		}

		public boolean isRing() {
			return ordinal() >= 6;
		}

		/** The axis an arrow moves along, the normal of a plane, or the axis a ring rotates about. */
		public int axis() {
			return ordinal() % 3;
		}
	}

	public static final int[] COLORS = {0xFFE03030, 0xFF30D040, 0xFF3050E8};
	/** Size of the gizmo relative to its distance from the camera. */
	public static double screenScale = 0.16;

	public final Vector3d origin = new Vector3d();
	public final Quaterniond orientation = new Quaterniond();
	public boolean planes = true, rings = false, arrows = true;
	public @Nullable Part hovered, active;

	private final Vector3d[] dirs = {new Vector3d(), new Vector3d(), new Vector3d()};
	private double size = 1;

	/** Recomputes the screen-constant size and the camera-facing arrow directions. */
	public void update(Vector3dc cameraPos) {
		size = Math.max(0.05, origin.distance(cameraPos) * screenScale);
		Vector3d toCam = new Vector3d(cameraPos).sub(origin);
		for (int i = 0; i < 3; i++) {
			dirs[i].set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0);
			orientation.transform(dirs[i]);
			// while dragging keep the direction fixed so the arrow does not flip under the cursor
			if (active == null && dirs[i].dot(toCam) < 0) dirs[i].negate();
		}
	}

	public double size() {
		return size;
	}

	public Vector3d dir(int axis) {
		return new Vector3d(dirs[axis]);
	}

	/** Unsigned local axis (follows the orientation, does not flip). */
	public Vector3d axis(int axis) {
		Vector3d v = new Vector3d(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
		return orientation.transform(v);
	}

	// -- picking ----------------------------------------------------------------------------------------------

	public @Nullable Part pick(Vector3dc rayOrigin, Vector3dc rayDir) {
		Part best = null;
		double bestT = Double.POSITIVE_INFINITY;
		if (arrows) {
			for (int i = 0; i < 3; i++) {
				Vector3d a = new Vector3d(dirs[i]).mul(size * 0.12).add(origin);
				Vector3d b = new Vector3d(dirs[i]).mul(size * 1.12).add(origin);
				double[] r = raySegment(rayOrigin, rayDir, a, b);
				if (r != null && r[1] < size * 0.075 && r[0] < bestT) {
					bestT = r[0];
					best = Part.values()[i];
				}
			}
		}
		if (planes) {
			for (int n = 0; n < 3; n++) {
				int u = (n + 1) % 3, v = (n + 2) % 3;
				double t = rayPlane(rayOrigin, rayDir, origin, dirs[n]);
				if (t <= 0 || t >= bestT) continue;
				Vector3d p = new Vector3d(rayDir).mul(t).add(rayOrigin).sub(origin);
				double pu = p.dot(dirs[u]) / size, pv = p.dot(dirs[v]) / size;
				if (pu >= 0.25 && pu <= 0.5 && pv >= 0.25 && pv <= 0.5) {
					bestT = t;
					best = Part.values()[3 + n];
				}
			}
		}
		if (rings) {
			for (int i = 0; i < 3; i++) {
				Vector3d n = axis(i);
				double t = rayPlane(rayOrigin, rayDir, origin, n);
				double radius = size * 0.85, tolerance = size * 0.07;
				boolean hit = false;
				if (t > 0) {
					Vector3d p = new Vector3d(rayDir).mul(t).add(rayOrigin);
					hit = Math.abs(p.distance(origin) - radius) < tolerance;
				}
				if (!hit) {
					// ring seen edge-on: accept rays passing close to the circle
					double d = rayCircleDistance(rayOrigin, rayDir, origin, n, radius);
					hit = d < tolerance;
					if (hit) t = new Vector3d(origin).sub(rayOrigin).dot(rayDir);
				}
				if (hit && t > 0 && t < bestT + size * 0.05) {
					bestT = t;
					best = Part.values()[6 + i];
				}
			}
		}
		return best;
	}

	/** {t along ray, distance} for the closest approach of a ray to a segment, or null if behind. */
	static double @Nullable [] raySegment(Vector3dc o, Vector3dc d, Vector3dc a, Vector3dc b) {
		Vector3d u = new Vector3d(b).sub(a);
		Vector3d w = new Vector3d(o).sub(a);
		double aa = d.dot(d), bb = d.dot(u), cc = u.dot(u), dd = d.dot(w), ee = u.dot(w);
		double den = aa * cc - bb * bb;
		double s, t;
		if (den < 1e-12) {
			s = 0;
			t = ee / cc;
		} else {
			s = (bb * ee - cc * dd) / den;
			t = (aa * ee - bb * dd) / den;
		}
		t = Math.max(0, Math.min(1, t));
		Vector3d onSeg = new Vector3d(u).mul(t).add(a);
		s = Math.max(0, new Vector3d(onSeg).sub(o).dot(d) / aa);
		if (s <= 0) return null;
		Vector3d onRay = new Vector3d(d).mul(s).add(o);
		return new double[]{s, onRay.distance(onSeg)};
	}

	static double rayPlane(Vector3dc o, Vector3dc d, Vector3dc p, Vector3dc n) {
		double den = d.dot(n);
		if (Math.abs(den) < 1e-9) return -1;
		return new Vector3d(p).sub(o).dot(n) / den;
	}

	private static double rayCircleDistance(Vector3dc o, Vector3dc d, Vector3dc c, Vector3dc n, double radius) {
		double best = Double.POSITIVE_INFINITY;
		Vector3d u = Overlay.perpendicular(n, new Vector3d()), v = new Vector3d(n).cross(u).normalize();
		for (int i = 0; i < 64; i++) {
			double a = Math.PI * 2 * i / 64;
			Vector3d p = new Vector3d(u).mul(Math.cos(a) * radius).add(new Vector3d(v).mul(Math.sin(a) * radius)).add(c);
			Vector3d w = new Vector3d(p).sub(o);
			double t = w.dot(d);
			if (t <= 0) continue;
			best = Math.min(best, new Vector3d(d).mul(t).add(o).distance(p));
		}
		return best;
	}

	// -- dragging ---------------------------------------------------------------------------------------------

	private final Vector3d dragStartOrigin = new Vector3d();
	private final Vector3d dragStartPoint = new Vector3d();
	private double dragStartParam;
	private final Vector3d ringStartVector = new Vector3d();

	public void beginDrag(Part part, Vector3dc rayOrigin, Vector3dc rayDir) {
		active = part;
		dragStartOrigin.set(origin);
		if (part.isAxis()) {
			dragStartParam = axisParam(rayOrigin, rayDir, dirs[part.axis()]);
		} else if (part.isPlane()) {
			planePoint(rayOrigin, rayDir, dirs[part.axis()], dragStartPoint);
		} else {
			ringVector(rayOrigin, rayDir, axis(part.axis()), ringStartVector);
		}
	}

	public void endDrag() {
		active = null;
	}

	/** World translation since the drag began (axis and plane parts), unsnapped. */
	public Vector3d dragTranslation(Vector3dc rayOrigin, Vector3dc rayDir) {
		if (active == null) return new Vector3d();
		if (active.isAxis()) {
			Vector3d dir = dirs[active.axis()];
			double t = axisParam(rayOrigin, rayDir, dir);
			return new Vector3d(dir).mul(t - dragStartParam);
		}
		if (active.isPlane()) {
			Vector3d p = planePoint(rayOrigin, rayDir, dirs[active.axis()], new Vector3d());
			return p.sub(dragStartPoint);
		}
		return new Vector3d();
	}

	/** Rotation angle (radians, right-handed about the ring's axis) since the drag began. */
	public double dragAngle(Vector3dc rayOrigin, Vector3dc rayDir) {
		if (active == null || !active.isRing()) return 0;
		Vector3d n = axis(active.axis());
		Vector3d now = ringVector(rayOrigin, rayDir, n, new Vector3d());
		double angle = Math.atan2(new Vector3d(ringStartVector).cross(now).dot(n), ringStartVector.dot(now));
		return angle;
	}

	/** Parameter along the axis line through the drag start origin closest to the ray. */
	private double axisParam(Vector3dc o, Vector3dc d, Vector3dc axis) {
		Vector3d w = new Vector3d(dragStartOrigin).sub(o);
		double a = axis.dot(axis), b = axis.dot(d), c = d.dot(d), dd = axis.dot(w), e = d.dot(w);
		double den = a * c - b * b;
		if (Math.abs(den) < 1e-9) return 0;
		return (b * e - c * dd) / den;
	}

	private Vector3d planePoint(Vector3dc o, Vector3dc d, Vector3dc n, Vector3d dest) {
		double t = rayPlane(o, d, dragStartOrigin, n);
		if (t < 0) t = 0;
		return dest.set(d).mul(t).add(o);
	}

	private Vector3d ringVector(Vector3dc o, Vector3dc d, Vector3dc n, Vector3d dest) {
		double t = rayPlane(o, d, dragStartOrigin, n);
		Vector3d p;
		if (t <= 0) {
			// plane edge-on or behind: use the closest point to the origin projected into the plane
			p = new Vector3d(d).mul(Math.max(0, new Vector3d(dragStartOrigin).sub(o).dot(d))).add(o);
		} else {
			p = new Vector3d(d).mul(t).add(o);
		}
		p.sub(dragStartOrigin);
		p.fma(-p.dot(n), n);
		if (p.lengthSquared() < 1e-12) p.set(Overlay.perpendicular(n, new Vector3d()));
		return dest.set(p).normalize();
	}

	public static double snap(double value, double step) {
		return step <= 0 ? value : Math.round(value / step) * step;
	}

	// -- drawing ----------------------------------------------------------------------------------------------

	public void draw(Overlay o) {
		Overlay.Layer L = Overlay.Layer.ON_TOP;
		if (rings) {
			for (int i = 0; i < 3; i++) {
				Part p = Part.values()[6 + i];
				if (active != null && active != p) continue;
				int c = color(p, i);
				o.ring(L, origin, axis(i), size * 0.85, c, highlighted(p) ? 5f : 3f, 72);
			}
		}
		if (arrows) {
			for (int i = 0; i < 3; i++) {
				Part p = Part.values()[i];
				if (active != null && active != p && !(active.isPlane() && active.axis() != i)) continue;
				int c = color(p, i);
				Vector3d a = new Vector3d(dirs[i]).mul(size * 0.12).add(origin);
				Vector3d b = new Vector3d(dirs[i]).mul(size * 0.9).add(origin);
				o.line(L, a, b, c, highlighted(p) ? 6f : 4f);
				o.cone(L, b, dirs[i], size * 0.22, size * 0.07, c, 16);
			}
		}
		if (planes) {
			for (int n = 0; n < 3; n++) {
				Part p = Part.values()[3 + n];
				if (active != null && active != p) continue;
				int u = (n + 1) % 3, v = (n + 2) % 3;
				Vector3d du = new Vector3d(dirs[u]).mul(size), dv = new Vector3d(dirs[v]).mul(size);
				Vector3d a = new Vector3d(origin).fma(0.25, du).fma(0.25, dv);
				Vector3d b = new Vector3d(origin).fma(0.5, du).fma(0.25, dv);
				Vector3d c = new Vector3d(origin).fma(0.5, du).fma(0.5, dv);
				Vector3d d = new Vector3d(origin).fma(0.25, du).fma(0.5, dv);
				int col = color(p, n);
				o.quad(L, a, b, c, d, Overlay.withAlpha(col, highlighted(p) ? 230 : 170));
			}
		}
	}

	private boolean highlighted(Part p) {
		return p == active || (active == null && p == hovered);
	}

	private int color(Part p, int axis) {
		int c = COLORS[axis];
		return highlighted(p) ? Overlay.lighten(c, 0.55f) : c;
	}
}
