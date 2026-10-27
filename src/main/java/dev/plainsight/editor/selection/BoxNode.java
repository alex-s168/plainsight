package dev.plainsight.editor.selection;

import dev.plainsight.editor.math.OrientedBox;

import java.util.UUID;

/** An oriented box selecting the blocks whose centres it contains. */
public final class BoxNode extends SelectionNode {
	private final OrientedBox box;
	/**
	 * Corner (bits, see {@link OrientedBox#corner}) where the box was started; its opposite corner is
	 * where it ended. These two corners carry the box's corner handles.
	 */
	public int startCorner;
	private long cachedCount = -1;
	private boolean cachedExact;
	private final OrientedBox cachedFor = new OrientedBox();

	public BoxNode(OrientedBox box) {
		this(UUID.randomUUID(), box);
	}

	public BoxNode(UUID id, OrientedBox box) {
		super(id);
		this.box = box;
	}

	/** The live box; edits are picked up by {@link #blockCount()} automatically. */
	public OrientedBox box() {
		return box;
	}

	@Override
	public String label() {
		return name() + " (" + blocks(blockCount(), cachedExact) + ")";
	}

	@Override
	public String kind() {
		return "box";
	}

	@Override
	public long blockCount() {
		if (cachedCount < 0 || !same(cachedFor, box)) {
			cachedFor.set(box);
			cachedCount = box.blockCount();
			cachedExact = box.isCountExact();
		}
		return cachedCount;
	}

	private static boolean same(OrientedBox a, OrientedBox b) {
		return a.center.equals(b.center) && a.half.equals(b.half) && a.rotation.equals(b.rotation);
	}

	@Override
	public BoxNode copy() {
		BoxNode b = new BoxNode(new OrientedBox(box));
		b.startCorner = startCorner;
		b.rename(customName());
		b.visible = visible;
		return b;
	}
}
