package dev.infiniterra.impl.guess;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportProgressTest {
	@Test
	void notDoneBeforeTheColumnsAreListed() {
		assertFalse(new ImportProgress().doneNear(0, 0, 4, 0));
	}

	@Test
	void doneNearOnceTheColumnsAroundAreCopied() {
		ImportProgress p = new ImportProgress();
		long near = ChunkPos.pack(2, -3), far = ChunkPos.pack(40, 0);
		p.list(new long[]{near, far});
		assertEquals(2, p.total());
		assertFalse(p.doneNear(0, 0, 4, 0), "a column 3 away is still to copy");
		p.copied(near);
		assertEquals(1, p.done());
		assertTrue(p.doneNear(0, 0, 4, ImportProgress.RECHECK_MS), "only far columns are left");
		assertFalse(p.doneNear(36, 0, 4, ImportProgress.RECHECK_MS * 2), "but not near those");
		p.copied(far);
		p.copied(far);
		assertEquals(2, p.done(), "a column counts once");
		assertTrue(p.doneNear(36, 0, 4, ImportProgress.RECHECK_MS * 3));
	}

	@Test
	void nothingToCopyIsDone() {
		ImportProgress p = new ImportProgress();
		p.list(new long[0]);
		assertTrue(p.doneNear(5, 5, 100, 0));
	}
}
