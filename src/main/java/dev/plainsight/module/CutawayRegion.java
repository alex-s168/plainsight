package dev.plainsight.module;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable description of the blocks hidden by {@link SeeThrough}: for each target (a player) the
 * blocks whose centres lie within {@code radius} of the line from the camera to the target, except
 * the last block before the target and the ground the target stands on.
 */
public final class CutawayRegion {
	public static final CutawayRegion EMPTY = new CutawayRegion(List.of(), 0);
	/** Number of positive {@link #contains} answers (debug statistics). */
	public static final java.util.concurrent.atomic.AtomicLong HITS = new java.util.concurrent.atomic.AtomicLong();

	/** Camera (a) to target (b); feet is the target's feet height. */
	public record Segment(double ax, double ay, double az, double bx, double by, double bz, double feetY) {
		double length() {
			return Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		}
	}

	private final List<Segment> segments;
	private final double radius;
	private final double[] bounds;

	public CutawayRegion(List<Segment> segments, double radius) {
		this.segments = List.copyOf(segments);
		this.radius = radius;
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Segment s : this.segments) {
			b[0] = Math.min(b[0], Math.min(s.ax, s.bx) - radius);
			b[1] = Math.min(b[1], Math.min(s.ay, s.by) - radius);
			b[2] = Math.min(b[2], Math.min(s.az, s.bz) - radius);
			b[3] = Math.max(b[3], Math.max(s.ax, s.bx) + radius);
			b[4] = Math.max(b[4], Math.max(s.ay, s.by) + radius);
			b[5] = Math.max(b[5], Math.max(s.az, s.bz) + radius);
		}
		bounds = b;
	}

	public List<Segment> segments() {
		return segments;
	}

	public double radius() {
		return radius;
	}

	public boolean isEmpty() {
		return segments.isEmpty();
	}

	/** Whether the block at (x, y, z) is cut away. Called from chunk compile threads. */
	public boolean contains(int x, int y, int z) {
		double px = x + 0.5, py = y + 0.5, pz = z + 0.5;
		if (px < bounds[0] || py < bounds[1] || pz < bounds[2] || px > bounds[3] || py > bounds[4] || pz > bounds[5]) return false;
		for (Segment s : segments) {
			double dx = s.bx - s.ax, dy = s.by - s.ay, dz = s.bz - s.az;
			double len2 = dx * dx + dy * dy + dz * dz;
			if (len2 < 1e-6) continue;
			double t = ((px - s.ax) * dx + (py - s.ay) * dy + (pz - s.az) * dz) / len2;
			if (t < 0) continue;
			double len = Math.sqrt(len2);
			// stop a block short of the target so it keeps the blocks right around it
			if (t * len > len - 1.0) continue;
			double cx = s.ax + dx * t - px, cy = s.ay + dy * t - py, cz = s.az + dz * t - pz;
			if (cx * cx + cy * cy + cz * cz > radius * radius) continue;
			// keep the ground under the target
			if (py < s.feetY && Math.hypot(px - s.bx, pz - s.bz) < radius + 1) continue;
			HITS.incrementAndGet();
			return true;
		}
		return false;
	}

	/** Section coordinates {x, y, z} touched by the region. */
	public Set<List<Integer>> sections() {
		Set<List<Integer>> out = new HashSet<>();
		double r = radius + 1;
		for (Segment s : segments) {
			double len = s.length();
			int steps = Math.max(1, (int) Math.ceil(len / 4));
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				double x = s.ax + (s.bx - s.ax) * t, y = s.ay + (s.by - s.ay) * t, z = s.az + (s.bz - s.az) * t;
				for (int sx = (int) Math.floor((x - r) / 16); sx <= (int) Math.floor((x + r) / 16); sx++)
					for (int sy = (int) Math.floor((y - r) / 16); sy <= (int) Math.floor((y + r) / 16); sy++)
						for (int sz = (int) Math.floor((z - r) / 16); sz <= (int) Math.floor((z + r) / 16); sz++)
							out.add(List.of(sx, sy, sz));
			}
		}
		return out;
	}

	/** Whether any endpoint moved more than {@code tolerance} compared to {@code other}. */
	public boolean differs(CutawayRegion other, double tolerance) {
		if (other.segments.size() != segments.size() || other.radius != radius) return true;
		for (int i = 0; i < segments.size(); i++) {
			Segment a = segments.get(i), b = other.segments.get(i);
			if (Math.abs(a.ax - b.ax) > tolerance || Math.abs(a.ay - b.ay) > tolerance || Math.abs(a.az - b.az) > tolerance
					|| Math.abs(a.bx - b.bx) > tolerance || Math.abs(a.by - b.by) > tolerance || Math.abs(a.bz - b.bz) > tolerance) return true;
		}
		return false;
	}
}
