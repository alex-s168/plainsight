package dev.plainsight.editor.math;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * A box with arbitrary orientation: a centre, half extents along its local axes and a rotation.
 * A block belongs to the box when the block's centre lies inside it (inclusive), so an axis-aligned
 * box spanning whole blocks contains exactly those blocks.
 */
public final class OrientedBox {
	/** Block counts are exact up to this many candidate blocks, estimated from the volume beyond. */
	public static final long EXACT_COUNT_LIMIT = 8_000_000L;
	private static final double EPS = 1e-7;

	public final Vector3d center = new Vector3d();
	public final Vector3d half = new Vector3d(0.5, 0.5, 0.5);
	public final Quaterniond rotation = new Quaterniond();

	public OrientedBox() {
	}

	public OrientedBox(OrientedBox o) {
		set(o);
	}

	public OrientedBox set(OrientedBox o) {
		center.set(o.center);
		half.set(o.half);
		rotation.set(o.rotation);
		return this;
	}

	/** Axis-aligned box covering both blocks and everything between them. */
	public static OrientedBox ofBlocks(int x1, int y1, int z1, int x2, int y2, int z2) {
		int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
		int maxX = Math.max(x1, x2) + 1, maxY = Math.max(y1, y2) + 1, maxZ = Math.max(z1, z2) + 1;
		OrientedBox b = new OrientedBox();
		b.center.set((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
		b.half.set((maxX - minX) / 2.0, (maxY - minY) / 2.0, (maxZ - minZ) / 2.0);
		return b;
	}

	/** Local axis {@code i} (0 = x, 1 = y, 2 = z) in world space, unit length. */
	public Vector3d axis(int i, Vector3d dest) {
		dest.set(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0);
		return rotation.transform(dest);
	}

	public double halfExtent(int i) {
		return i == 0 ? half.x : i == 1 ? half.y : half.z;
	}

	/**
	 * Corner by sign bits: bit 0 = +x, bit 1 = +y, bit 2 = +z (local). Corner 0 is the local minimum,
	 * corner 7 the local maximum.
	 */
	public Vector3d corner(int bits, Vector3d dest) {
		dest.set((bits & 1) != 0 ? half.x : -half.x, (bits & 2) != 0 ? half.y : -half.y, (bits & 4) != 0 ? half.z : -half.z);
		return rotation.transform(dest).add(center);
	}

	/** World point to box-local coordinates (relative to the centre, unrotated). */
	public Vector3d toLocal(Vector3dc world, Vector3d dest) {
		dest.set(world).sub(center);
		return rotation.transformInverse(dest);
	}

	public boolean contains(double x, double y, double z) {
		Vector3d p = toLocal(new Vector3d(x, y, z), new Vector3d());
		return Math.abs(p.x) <= half.x + EPS && Math.abs(p.y) <= half.y + EPS && Math.abs(p.z) <= half.z + EPS;
	}

	public boolean containsBlock(int x, int y, int z) {
		return contains(x + 0.5, y + 0.5, z + 0.5);
	}

	/** World-space bounds as {minX, minY, minZ, maxX, maxY, maxZ}. */
	public double[] bounds() {
		Matrix3d m = rotation.get(new Matrix3d());
		double ex = Math.abs(m.m00) * half.x + Math.abs(m.m10) * half.y + Math.abs(m.m20) * half.z;
		double ey = Math.abs(m.m01) * half.x + Math.abs(m.m11) * half.y + Math.abs(m.m21) * half.z;
		double ez = Math.abs(m.m02) * half.x + Math.abs(m.m12) * half.y + Math.abs(m.m22) * half.z;
		return new double[]{center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez};
	}

	/** Range of block coordinates whose centres could be inside: {minX, minY, minZ, maxX, maxY, maxZ}, inclusive. */
	public int[] blockBounds() {
		double[] b = bounds();
		return new int[]{
				(int) Math.ceil(b[0] - 0.5 - EPS), (int) Math.ceil(b[1] - 0.5 - EPS), (int) Math.ceil(b[2] - 0.5 - EPS),
				(int) Math.floor(b[3] - 0.5 + EPS), (int) Math.floor(b[4] - 0.5 + EPS), (int) Math.floor(b[5] - 0.5 + EPS)};
	}

	public boolean isAxisAligned() {
		Matrix3d m = rotation.get(new Matrix3d());
		double[] v = {m.m00, m.m01, m.m02, m.m10, m.m11, m.m12, m.m20, m.m21, m.m22};
		for (double d : v) {
			double a = Math.abs(d);
			if (a > EPS && Math.abs(a - 1) > EPS) return false;
		}
		return true;
	}

	public double volume() {
		return 8 * half.x * half.y * half.z;
	}

	/** Number of blocks whose centres are inside; exact unless the candidate range is huge (then estimated). */
	public long blockCount() {
		int[] r = blockBounds();
		long nx = Math.max(0, r[3] - r[0] + 1L), ny = Math.max(0, r[4] - r[1] + 1L), nz = Math.max(0, r[5] - r[2] + 1L);
		long candidates = nx * ny * nz;
		if (candidates == 0) return 0;
		if (isAxisAligned()) return candidates;
		if (candidates > EXACT_COUNT_LIMIT) return Math.round(volume());
		long[] count = {0};
		forEachBlock(r, (x, y, z) -> count[0]++);
		return count[0];
	}

	public boolean isCountExact() {
		int[] r = blockBounds();
		long candidates = Math.max(0, r[3] - r[0] + 1L) * Math.max(0, r[4] - r[1] + 1L) * Math.max(0, r[5] - r[2] + 1L);
		return isAxisAligned() || candidates <= EXACT_COUNT_LIMIT;
	}

	public interface BlockVisitor {
		void accept(int x, int y, int z);
	}

	public void forEachBlock(BlockVisitor visitor) {
		forEachBlock(blockBounds(), visitor);
	}

	/** Visits blocks inside, scanning each x-row only between its entry and exit points. */
	private void forEachBlock(int[] r, BlockVisitor visitor) {
		Vector3d ax = rotation.transformInverse(new Vector3d(1, 0, 0));
		Vector3d p = new Vector3d();
		double[] h = {half.x + EPS, half.y + EPS, half.z + EPS};
		for (int y = r[1]; y <= r[4]; y++) {
			for (int z = r[2]; z <= r[5]; z++) {
				// local position of block centre at x = 0, then the row is p0 + t * ax
				p.set(0.5 - center.x, y + 0.5 - center.y, z + 0.5 - center.z);
				rotation.transformInverse(p);
				double tMin = Double.NEGATIVE_INFINITY, tMax = Double.POSITIVE_INFINITY;
				double[] p0 = {p.x, p.y, p.z}, d = {ax.x, ax.y, ax.z};
				boolean empty = false;
				for (int i = 0; i < 3 && !empty; i++) {
					if (Math.abs(d[i]) < 1e-12) {
						if (Math.abs(p0[i]) > h[i]) empty = true;
					} else {
						double t1 = (-h[i] - p0[i]) / d[i], t2 = (h[i] - p0[i]) / d[i];
						tMin = Math.max(tMin, Math.min(t1, t2));
						tMax = Math.min(tMax, Math.max(t1, t2));
					}
				}
				if (empty || tMin > tMax) continue;
				int x0 = Math.max(r[0], (int) Math.ceil(tMin)), x1 = Math.min(r[3], (int) Math.floor(tMax));
				for (int x = x0; x <= x1; x++) visitor.accept(x, y, z);
			}
		}
	}

	/**
	 * Moves corner {@code bits} to {@code target} (world) while the opposite corner stays fixed, keeping the
	 * rotation. Extents never go below {@code minSize}; a corner dragged past the opposite one stops there.
	 */
	public void moveCorner(int bits, Vector3dc target, double minSize) {
		Vector3d fixed = corner(bits ^ 7, new Vector3d());
		Vector3d local = rotation.transformInverse(new Vector3d(target).sub(fixed));
		double sx = (bits & 1) != 0 ? 1 : -1, sy = (bits & 2) != 0 ? 1 : -1, sz = (bits & 4) != 0 ? 1 : -1;
		double lx = Math.max(minSize, local.x * sx), ly = Math.max(minSize, local.y * sy), lz = Math.max(minSize, local.z * sz);
		half.set(lx / 2, ly / 2, lz / 2);
		Vector3d offset = rotation.transform(new Vector3d(sx * lx / 2, sy * ly / 2, sz * lz / 2));
		center.set(fixed).add(offset);
	}

	@Override
	public String toString() {
		return "OrientedBox{center=" + center + ", half=" + half + ", rotation=" + rotation + "}";
	}
}
