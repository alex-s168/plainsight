package dev.plainsight.minimap;

/**
 * A key that acts once when pressed and repeatedly while held, faster the longer it is held (the
 * minimap's Y level and zoom keys).
 */
public final class HoldRepeat {
	private final double delay, startRate, acceleration, maxRate;
	private boolean held;
	private double heldFor, pending;

	/** Repeats {@code startRate} times a second after {@code delay} seconds, speeding up to {@code maxRate}. */
	public HoldRepeat(double delay, double startRate, double acceleration, double maxRate) {
		this.delay = delay;
		this.startRate = startRate;
		this.acceleration = acceleration;
		this.maxRate = maxRate;
	}

	/**
	 * Steps to make now. {@code presses}: presses since the last call (a press shorter than one update
	 * still counts), {@code down}: whether the key is held, {@code dt}: seconds since the last call.
	 */
	public int update(int presses, boolean down, double dt) {
		if (!held) {
			if (presses == 0 && !down) return 0;
			held = down;
			heldFor = 0;
			pending = 0;
			return Math.max(1, presses);
		}
		if (!down) {
			held = false;
			return 0;
		}
		heldFor += dt;
		if (heldFor < delay) return 0;
		double rate = Math.min(maxRate, startRate + acceleration * (heldFor - delay));
		pending += rate * dt;
		int steps = (int) pending;
		pending -= steps;
		return steps;
	}

	public void reset() {
		held = false;
	}
}
