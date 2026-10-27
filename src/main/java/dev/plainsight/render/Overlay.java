package dev.plainsight.render;

import dev.plainsight.editor.math.OrientedBox;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Immediate-mode collector for world overlays (lines and triangles in world coordinates). Everything
 * submitted during a frame is drawn once after the level and then discarded. Layers decide depth
 * handling: {@link Layer#WORLD} is hidden behind terrain, {@link Layer#ON_TOP} is always visible and
 * {@link Layer#XRAY} is drawn behind terrain only (for faint see-through hints).
 */
public final class Overlay {
	public enum Layer {WORLD, XRAY, ON_TOP}

	/** Flat float storage per layer: lines x1 y1 z1 x2 y2 z2 color width; tris x y z color (x3). */
	static final class Batch {
		double[] lines = new double[1024];
		int lineCount;
		double[] tris = new double[1024];
		int triCount;

		void line(double ax, double ay, double az, double bx, double by, double bz, int color, float width) {
			if ((lineCount + 1) * 8 > lines.length) lines = java.util.Arrays.copyOf(lines, lines.length * 2);
			int o = lineCount++ * 8;
			lines[o] = ax;
			lines[o + 1] = ay;
			lines[o + 2] = az;
			lines[o + 3] = bx;
			lines[o + 4] = by;
			lines[o + 5] = bz;
			lines[o + 6] = color;
			lines[o + 7] = width;
		}

		void tri(Vector3dc a, Vector3dc b, Vector3dc c, int color) {
			if ((triCount + 1) * 10 > tris.length) tris = java.util.Arrays.copyOf(tris, tris.length * 2);
			int o = triCount++ * 10;
			tris[o] = a.x();
			tris[o + 1] = a.y();
			tris[o + 2] = a.z();
			tris[o + 3] = b.x();
			tris[o + 4] = b.y();
			tris[o + 5] = b.z();
			tris[o + 6] = c.x();
			tris[o + 7] = c.y();
			tris[o + 8] = c.z();
			tris[o + 9] = color;
		}

		void clear() {
			lineCount = 0;
			triCount = 0;
		}
	}

	final Batch[] batches = {new Batch(), new Batch(), new Batch()};

	public void clear() {
		for (Batch b : batches) b.clear();
	}

	public boolean isEmpty() {
		for (Batch b : batches) if (b.lineCount > 0 || b.triCount > 0) return false;
		return true;
	}

	public int lineCount(Layer layer) {
		return batches[layer.ordinal()].lineCount;
	}

	public int triangleCount(Layer layer) {
		return batches[layer.ordinal()].triCount;
	}

	// -- primitives ---------------------------------------------------------------------------------------

	public void line(Layer layer, Vector3dc a, Vector3dc b, int color, float width) {
		batches[layer.ordinal()].line(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), color, width);
	}

	public void line(Layer layer, double ax, double ay, double az, double bx, double by, double bz, int color, float width) {
		batches[layer.ordinal()].line(ax, ay, az, bx, by, bz, color, width);
	}

	public void triangle(Layer layer, Vector3dc a, Vector3dc b, Vector3dc c, int color) {
		batches[layer.ordinal()].tri(a, b, c, color);
	}

	/** Two-sided quad a-b-c-d. */
	public void quad(Layer layer, Vector3dc a, Vector3dc b, Vector3dc c, Vector3dc d, int color) {
		Batch batch = batches[layer.ordinal()];
		batch.tri(a, b, c, color);
		batch.tri(a, c, d, color);
	}

	// -- shapes ---------------------------------------------------------------------------------------------

	private static final int[][] EDGES = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
	private static final int[][] FACES = {{0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 6, 7, 5}};

	public void boxEdges(Layer layer, OrientedBox box, int color, float width) {
		Vector3d[] c = corners(box);
		for (int[] e : EDGES) line(layer, c[e[0]], c[e[1]], color, width);
	}

	public void boxFaces(Layer layer, OrientedBox box, int color) {
		Vector3d[] c = corners(box);
		for (int[] f : FACES) quad(layer, c[f[0]], c[f[1]], c[f[2]], c[f[3]], color);
	}

	public void aabbEdges(Layer layer, double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
		boxEdges(layer, aabb(x1, y1, z1, x2, y2, z2), color, width);
	}

	public void aabbFaces(Layer layer, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		boxFaces(layer, aabb(x1, y1, z1, x2, y2, z2), color);
	}

	/** Cube of edge {@code size} centred at {@code c}. */
	public void cube(Layer layer, Vector3dc c, double size, int faceColor, int edgeColor, float width) {
		double h = size / 2;
		OrientedBox b = aabb(c.x() - h, c.y() - h, c.z() - h, c.x() + h, c.y() + h, c.z() + h);
		if ((faceColor >>> 24) != 0) boxFaces(layer, b, faceColor);
		if ((edgeColor >>> 24) != 0) boxEdges(layer, b, edgeColor, width);
	}

	/** Cone from base centre {@code base} along unit {@code dir} with the given length and radius. */
	public void cone(Layer layer, Vector3dc base, Vector3dc dir, double length, double radius, int color, int segments) {
		Vector3d u = perpendicular(dir, new Vector3d()), v = new Vector3d(dir).cross(u).normalize();
		Vector3d tip = new Vector3d(dir).mul(length).add(base);
		Vector3d prev = null, first = null;
		for (int i = 0; i <= segments; i++) {
			double a = Math.PI * 2 * i / segments;
			Vector3d p = new Vector3d(u).mul(Math.cos(a) * radius).add(new Vector3d(v).mul(Math.sin(a) * radius)).add(base);
			if (prev != null) {
				triangle(layer, prev, p, tip, color);
				triangle(layer, prev, p, base, shade(color, 0.7f));
			} else {
				first = p;
			}
			prev = p;
		}
	}

	/** Circle of line segments around {@code center} in the plane with normal {@code axis}. */
	public void ring(Layer layer, Vector3dc center, Vector3dc axis, double radius, int color, float width, int segments) {
		Vector3d u = perpendicular(axis, new Vector3d()), v = new Vector3d(axis).cross(u).normalize();
		Vector3d prev = null;
		for (int i = 0; i <= segments; i++) {
			double a = Math.PI * 2 * i / segments;
			Vector3d p = new Vector3d(u).mul(Math.cos(a) * radius).add(new Vector3d(v).mul(Math.sin(a) * radius)).add(center);
			if (prev != null) line(layer, prev, p, color, width);
			prev = p;
		}
	}

	public static Vector3d[] corners(OrientedBox box) {
		Vector3d[] c = new Vector3d[8];
		for (int i = 0; i < 8; i++) c[i] = box.corner(i, new Vector3d());
		return c;
	}

	static OrientedBox aabb(double x1, double y1, double z1, double x2, double y2, double z2) {
		OrientedBox b = new OrientedBox();
		b.center.set((x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2);
		b.half.set(Math.abs(x2 - x1) / 2, Math.abs(y2 - y1) / 2, Math.abs(z2 - z1) / 2);
		return b;
	}

	public static Vector3d perpendicular(Vector3dc n, Vector3d dest) {
		if (Math.abs(n.x()) < 0.9) dest.set(1, 0, 0);
		else dest.set(0, 1, 0);
		return dest.cross(n).normalize();
	}

	public static int argb(int a, int r, int g, int b) {
		return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
	}

	public static int withAlpha(int color, int alpha) {
		return (color & 0xFFFFFF) | (alpha & 255) << 24;
	}

	public static int shade(int color, float f) {
		int r = (int) (((color >> 16) & 255) * f), g = (int) (((color >> 8) & 255) * f), b = (int) ((color & 255) * f);
		return (color & 0xFF000000) | r << 16 | g << 8 | b;
	}

	/** Mixes towards white by {@code f} (0..1). */
	public static int lighten(int color, float f) {
		int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
		r += (int) ((255 - r) * f);
		g += (int) ((255 - g) * f);
		b += (int) ((255 - b) * f);
		return (color & 0xFF000000) | r << 16 | g << 8 | b;
	}
}
