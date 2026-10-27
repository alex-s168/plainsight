package dev.plainsight.module;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable description of the blocks hidden by {@link SeeThrough}. For each target (a player) it is a
 * cone from the camera to the target: thin at the camera ({@code cameraRadius}) and wide around the
 * target ({@code targetRadius}), so the target is seen with its surroundings, not through a keyhole.
 * Seen from above, the ground the target stands on stays; everything more than {@link #BEYOND} blocks beyond the target stay.
 */
public final class CutawayRegion {
	public static final CutawayRegion EMPTY = new CutawayRegion(List.of(), 0, 0, 0);

	/** Camera (a) to target (b); feet is the target's feet height. */
	public record Segment(double ax, double ay, double az, double bx, double by, double bz, double feetY) {
		double length() {
			return Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
		}
	}

	/** How far past the target (along the view) the cut still reaches, in blocks. */
	static final double BEYOND = 1.5;

	private final List<Segment> segments;
	private final double cameraRadius, targetRadius;
	/** Sky light (0..15) the opened space is lit with: what the camera sees around itself. */
	private final int skyLight;
	private final double[] bounds;

	public CutawayRegion(List<Segment> segments, double cameraRadius, double targetRadius, int skyLight) {
		this.segments = List.copyOf(segments);
		this.cameraRadius = cameraRadius;
		this.targetRadius = targetRadius;
		this.skyLight = skyLight;
		double r = Math.max(cameraRadius, targetRadius) + 1;
		double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Segment s : this.segments) {
			b[0] = Math.min(b[0], Math.min(s.ax, s.bx) - r);
			b[1] = Math.min(b[1], Math.min(s.ay, s.by) - r);
			b[2] = Math.min(b[2], Math.min(s.az, s.bz) - r);
			b[3] = Math.max(b[3], Math.max(s.ax, s.bx) + r);
			b[4] = Math.max(b[4], Math.max(s.ay, s.by) + r);
			b[5] = Math.max(b[5], Math.max(s.az, s.bz) + r);
		}
		bounds = b;
	}

	public List<Segment> segments() {
		return segments;
	}

	public double cameraRadius() {
		return cameraRadius;
	}

	public double targetRadius() {
		return targetRadius;
	}

	public int skyLight() {
		return skyLight;
	}

	public boolean isEmpty() {
		return segments.isEmpty();
	}

	/** Whether the block at (x, y, z) is cut away. Called from chunk compile threads. */
	public boolean contains(int x, int y, int z) {
		return test(x + 0.5, y + 0.5, z + 0.5, 0, true);
	}

	/**
	 * Whether the block is within {@code margin} of the cut (ignoring the kept ground): blocks there are
	 * meshed as if the cut blocks were air, so they get no shadows (ambient occlusion, darkness) from them.
	 */
	public boolean near(int x, int y, int z, double margin) {
		return test(x + 0.5, y + 0.5, z + 0.5, margin, false);
	}

	private boolean test(double px, double py, double pz, double margin, boolean keepGround) {
		if (px < bounds[0] - margin || py < bounds[1] - margin || pz < bounds[2] - margin
				|| px > bounds[3] + margin || py > bounds[4] + margin || pz > bounds[5] + margin) return false;
		for (Segment s : segments) {
			double dx = s.bx - s.ax, dy = s.by - s.ay, dz = s.bz - s.az;
			double len2 = dx * dx + dy * dy + dz * dz;
			if (len2 < 1e-6) continue;
			double t = ((px - s.ax) * dx + (py - s.ay) * dy + (pz - s.az) * dz) / len2;
			double len = Math.sqrt(len2), slack = margin / len;
			// the wide end reaches a little past the target, so blocks right beside it are open too
			if (t < -slack || t > 1 + (BEYOND + margin) / len) continue;
			double tc = Math.max(0, Math.min(1, t));
			double r = cameraRadius + (targetRadius - cameraRadius) * tc + margin;
			double cx = s.ax + dx * tc - px, cy = s.ay + dy * tc - py, cz = s.az + dz * tc - pz;
			if (cx * cx + cy * cy + cz * cz > r * r) continue;
			// seen from above, keep the ground under and around the target; seen from below the ground is
			// exactly what is in the way
			if (keepGround && s.ay > s.feetY && py < s.feetY && Math.hypot(px - s.bx, pz - s.bz) < targetRadius + 1.5) continue;
			return true;
		}
		return false;
	}

	/** Section coordinates {x, y, z} touched by the region (including the unshadowed margin). */
	public Set<List<Integer>> sections() {
		Set<List<Integer>> out = new HashSet<>();
		for (Segment s : segments) {
			int steps = Math.max(1, (int) Math.ceil(s.length() / 2));
			for (int i = 0; i <= steps; i++) {
				double t = (double) i / steps;
				double r = cameraRadius + (targetRadius - cameraRadius) * t + 3;
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
		if (other.segments.size() != segments.size() || other.cameraRadius != cameraRadius || other.targetRadius != targetRadius
				|| other.skyLight != skyLight) return true;
		for (int i = 0; i < segments.size(); i++) {
			Segment a = segments.get(i), b = other.segments.get(i);
			if (Math.abs(a.ax - b.ax) > tolerance || Math.abs(a.ay - b.ay) > tolerance || Math.abs(a.az - b.az) > tolerance
					|| Math.abs(a.bx - b.bx) > tolerance || Math.abs(a.by - b.by) > tolerance || Math.abs(a.bz - b.bz) > tolerance) return true;
		}
		return false;
	}
}
