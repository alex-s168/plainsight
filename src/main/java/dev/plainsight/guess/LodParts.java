package dev.plainsight.guess;

/** Which 16-block parts of a chunk's column in Voxy's real LOD are unknown (left to generation) instead of real. */
final class LodParts {
	private LodParts() {
	}

	/**
	 * Per part from the bottom up: whether Voxy has its LOD section ({@code present}), whether it has any
	 * block, and whether its top / bottom layer does. Returns which present parts are unknown.
	 * <p>
	 * A chunk the player has seen has blocks somewhere (bedrock at least); where Voxy has none, it never had
	 * that chunk, only others of its LOD section: all unknown. Parts of a seen chunk Voxy never got data for
	 * are air too (all-zero or lit, like real sky and caves), so a part without blocks is unknown where
	 * terrain runs into it, and so is every such part stacked on top of (or below) an unknown one. Else
	 * terrain could end in a flat cut with nothing above it.
	 */
	static boolean[] unknown(boolean[] present, boolean[] hasBlocks, boolean[] topBlocks, boolean[] bottomBlocks) {
		int n = present.length;
		boolean anyBlocks = false;
		for (int s = 0; s < n; s++) anyBlocks |= present[s] && hasBlocks[s];
		boolean[] unknown = new boolean[n];
		for (int s = 0; s < n; s++) {
			if (!present[s] || hasBlocks[s]) continue;
			unknown[s] = !anyBlocks || (s > 0 && (unknown[s - 1] || (present[s - 1] && topBlocks[s - 1])));
		}
		for (int s = n - 1; s >= 0; s--) {
			if (!present[s] || hasBlocks[s]) continue;
			unknown[s] |= s + 1 < n && (unknown[s + 1] || (present[s + 1] && bottomBlocks[s + 1]));
		}
		return unknown;
	}
}
