package dev.plainsight.editor.math;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrientedBoxTest {
	@Test
	void blocksBoxCoversBothCornersInclusive() {
		OrientedBox b = OrientedBox.ofBlocks(5, 0, 2, 2, 3, 6);
		assertEquals(4 * 4 * 5, b.blockCount());
		assertTrue(b.containsBlock(2, 0, 2));
		assertTrue(b.containsBlock(5, 3, 6));
		assertFalse(b.containsBlock(6, 3, 6));
		assertFalse(b.containsBlock(1, 0, 2));
		assertArrayEquals(new int[]{2, 0, 2, 5, 3, 6}, b.blockBounds());
	}

	@Test
	void singleBlock() {
		OrientedBox b = OrientedBox.ofBlocks(-3, 64, 7, -3, 64, 7);
		assertEquals(1, b.blockCount());
		assertTrue(b.containsBlock(-3, 64, 7));
	}

	@Test
	void cornersAreAtLocalExtremes() {
		OrientedBox b = OrientedBox.ofBlocks(0, 0, 0, 3, 1, 2);
		assertEquals(new Vector3d(0, 0, 0), b.corner(0, new Vector3d()));
		assertEquals(new Vector3d(4, 2, 3), b.corner(7, new Vector3d()));
		assertEquals(new Vector3d(4, 0, 0), b.corner(1, new Vector3d()));
	}

	@Test
	void rotatedCountMatchesBruteForce() {
		Random r = new Random(42);
		for (int iter = 0; iter < 200; iter++) {
			OrientedBox b = new OrientedBox();
			b.center.set(r.nextDouble() * 20 - 10, r.nextDouble() * 20 - 10, r.nextDouble() * 20 - 10);
			b.half.set(0.2 + r.nextDouble() * 6, 0.2 + r.nextDouble() * 6, 0.2 + r.nextDouble() * 6);
			b.rotation.rotateXYZ(r.nextDouble() * 7, r.nextDouble() * 7, r.nextDouble() * 7);
			long brute = 0;
			for (int x = -30; x <= 30; x++)
				for (int y = -30; y <= 30; y++)
					for (int z = -30; z <= 30; z++)
						if (b.containsBlock(x, y, z)) brute++;
			assertEquals(brute, b.blockCount(), "box " + b);
			Set<Long> seen = new HashSet<>();
			b.forEachBlock((x, y, z) -> {
				assertTrue(b.containsBlock(x, y, z));
				assertTrue(seen.add(((long) x << 40) ^ ((long) y << 20) ^ z), "visited twice");
			});
			assertEquals(brute, seen.size());
		}
	}

	@Test
	void rotationBy90KeepsAxisAlignedCount() {
		OrientedBox b = OrientedBox.ofBlocks(0, 0, 0, 3, 0, 1);
		b.rotation.rotateY(Math.PI / 2);
		assertTrue(b.isAxisAligned());
		assertEquals(8, b.blockCount());
	}

	@Test
	void moveCornerKeepsOppositeCorner() {
		OrientedBox b = OrientedBox.ofBlocks(0, 0, 0, 3, 3, 3);
		b.rotation.rotateY(0.3);
		Vector3d fixed = b.corner(0, new Vector3d());
		Vector3d target = b.corner(7, new Vector3d()).add(b.axis(0, new Vector3d()).mul(2));
		b.moveCorner(7, target, 0.1);
		assertTrue(b.corner(0, new Vector3d()).distance(fixed) < 1e-9);
		assertTrue(b.corner(7, new Vector3d()).distance(target) < 1e-9);
		assertEquals(3.0, b.half.x, 1e-9);
		assertEquals(2.0, b.half.y, 1e-9);
	}

	@Test
	void moveCornerPastOppositeClampsToMinimum() {
		OrientedBox b = OrientedBox.ofBlocks(0, 0, 0, 3, 3, 3);
		b.moveCorner(7, new Vector3d(-5, 2, 2), 1);
		assertEquals(0.5, b.half.x, 1e-9);
		assertEquals(new Vector3d(0, 0, 0), b.corner(0, new Vector3d()));
	}

	@Test
	void hugeRotatedBoxIsEstimated() {
		OrientedBox b = OrientedBox.ofBlocks(0, 0, 0, 999, 999, 999);
		b.rotation.rotateY(0.5);
		assertFalse(b.isCountExact());
		assertEquals(1e9, b.blockCount(), 1e6);
	}
}
