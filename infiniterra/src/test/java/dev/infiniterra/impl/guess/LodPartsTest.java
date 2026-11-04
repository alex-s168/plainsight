package dev.infiniterra.impl.guess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LodPartsTest {
	/** Parts from the bottom: '#' blocks throughout, 'T' blocks reaching the top layer, 'B' the bottom layer, 'X' both, '.' air, ' ' no LOD section. */
	private static boolean[] unknown(String column) {
		int n = column.length();
		boolean[] present = new boolean[n], blocks = new boolean[n], top = new boolean[n], bottom = new boolean[n];
		for (int i = 0; i < n; i++) {
			char c = column.charAt(i);
			present[i] = c != ' ';
			blocks[i] = c == '#' || c == 'T' || c == 'B' || c == 'X';
			top[i] = c == '#' || c == 'T' || c == 'X';
			bottom[i] = c == '#' || c == 'B' || c == 'X';
		}
		return LodParts.unknown(present, blocks, top, bottom);
	}

	private static boolean[] parts(String unknown) {
		boolean[] b = new boolean[unknown.length()];
		for (int i = 0; i < b.length; i++) b[i] = unknown.charAt(i) == '?';
		return b;
	}

	@Test
	void groundWithSkyAboveIsReal() {
		// surface ends inside the third part: the sky above is real air
		assertArrayEquals(parts("......"), unknown("##B..."));
	}

	@Test
	void terrainCutAtAPartBoundaryIsFilledIn() {
		// stone right up to the top layer, then air: Voxy lacks what is above, it is generated
		assertArrayEquals(parts("...???"), unknown("###..."));
	}

	@Test
	void chunkWithoutBlocksIsUnknown() {
		// another chunk of the LOD section was seen, this one not
		assertArrayEquals(parts("??????"), unknown("......"));
	}

	@Test
	void missingSectionsStayUnknownWithoutBeingMarked() {
		assertArrayEquals(parts("......"), unknown("#B  .."));
	}

	@Test
	void airBelowTerrainHangingIntoItIsUnknown() {
		// a floating cut from above (bottom layer has blocks): the air under it is unknown down to the ground
		assertArrayEquals(parts(".??..."), unknown("B..B.."));
	}
}
