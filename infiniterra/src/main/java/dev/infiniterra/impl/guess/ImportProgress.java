package dev.infiniterra.impl.guess;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

/**
 * Which LOD columns (32x32 blocks, packed like {@link ChunkPos}) of the normal world still have to be copied
 * into the guessed one, so seamless switching can start once those around the player are done, before the
 * whole (possibly huge) world is. Thread-safe.
 */
final class ImportProgress {
	private final LongOpenHashSet pending = new LongOpenHashSet();
	private volatile boolean listed;
	private volatile int total, done;
	/** Last answer of {@link #doneNear}, recomputed at most every {@link #RECHECK_MS}. */
	private long checkedAt = Long.MIN_VALUE;
	private int checkedX, checkedZ, checkedRadius = -1;
	private boolean checkedResult;
	static final long RECHECK_MS = 500;

	/** The columns to copy are known (every other one is done already). */
	synchronized void list(long[] todo) {
		pending.clear();
		for (long c : todo) pending.add(c);
		total = todo.length;
		done = 0;
		listed = true;
		checkedAt = Long.MIN_VALUE;
	}

	synchronized void copied(long column) {
		if (pending.remove(column)) done++;
	}

	boolean listed() {
		return listed;
	}

	int total() {
		return total;
	}

	int done() {
		return done;
	}

	/**
	 * Whether every column within {@code radius} columns (Chebyshev) of column ({@code colX}, {@code colZ}) is
	 * copied. False until the columns to copy are listed.
	 */
	synchronized boolean doneNear(int colX, int colZ, int radius, long now) {
		if (!listed) return false;
		if (pending.isEmpty()) return true;
		if (colX == checkedX && colZ == checkedZ && radius == checkedRadius && now - checkedAt < RECHECK_MS) return checkedResult;
		boolean result = true;
		for (LongIterator it = pending.iterator(); it.hasNext(); ) {
			long c = it.nextLong();
			if (Math.abs(ChunkPos.getX(c) - colX) <= radius && Math.abs(ChunkPos.getZ(c) - colZ) <= radius) {
				result = false;
				break;
			}
		}
		checkedX = colX;
		checkedZ = colZ;
		checkedRadius = radius;
		checkedAt = now;
		checkedResult = result;
		return result;
	}
}
