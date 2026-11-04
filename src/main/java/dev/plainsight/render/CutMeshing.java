package dev.plainsight.render;

/**
 * State of Sodium's meshing thread for see-through: whether the block the mesher is at is cut (drawn
 * faintly), and whether reads of the level should see the real blocks (while a cut block is meshed, so
 * its faces between other cut blocks stay culled) instead of air at cut positions.
 */
public final class CutMeshing {
	private static final ThreadLocal<boolean[]> STATE = ThreadLocal.withInitial(() -> new boolean[2]);

	private CutMeshing() {
	}

	public static boolean raw() {
		return STATE.get()[0];
	}

	public static void setRaw(boolean raw) {
		STATE.get()[0] = raw;
	}

	/** Whether the block the meshing loop read last is a cut block that is drawn faintly. */
	public static boolean current() {
		return STATE.get()[1];
	}

	public static void setCurrent(boolean cut) {
		STATE.get()[1] = cut;
	}
}
