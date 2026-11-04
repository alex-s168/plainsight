package dev.plainsight.editor.selection;

/**
 * Selections as text to paste into chat or commands: the centre block "X Y Z", and the blocks covered
 * "min X Y Z, max X Y Z" (corners inclusive). Bounds are {minX, minY, minZ, maxX, maxY, maxZ} in world
 * coordinates.
 */
public final class SelectionText {
	private SelectionText() {
	}

	/** The block at the centre of the bounds. */
	public static String center(double[] b) {
		return floor((b[0] + b[3]) / 2) + " " + floor((b[1] + b[4]) / 2) + " " + floor((b[2] + b[5]) / 2);
	}

	/** The first and last block inside the bounds. */
	public static String specs(double[] b) {
		return "min " + floor(b[0] + 1e-6) + " " + floor(b[1] + 1e-6) + " " + floor(b[2] + 1e-6)
				+ ", max " + last(b[3]) + " " + last(b[4]) + " " + last(b[5]);
	}

	/** The last block before {@code max} (a box edge on a block boundary does not reach into the next block). */
	private static long last(double max) {
		return (long) Math.ceil(max - 1e-6) - 1;
	}

	private static long floor(double v) {
		return (long) Math.floor(v);
	}

	/** Union of bounds (null entries are skipped; null if none). */
	public static double[] union(Iterable<double[]> all) {
		double[] acc = null;
		for (double[] b : all) {
			if (b == null) continue;
			if (acc == null) acc = b.clone();
			else for (int i = 0; i < 3; i++) {
				acc[i] = Math.min(acc[i], b[i]);
				acc[i + 3] = Math.max(acc[i + 3], b[i + 3]);
			}
		}
		return acc;
	}
}
