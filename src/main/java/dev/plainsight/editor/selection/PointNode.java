package dev.plainsight.editor.selection;

import java.util.UUID;

/** A point selection: one block position (e.g. a spot to remember, a waypoint). */
public final class PointNode extends SelectionNode {
	public int x, y, z;

	public PointNode(int x, int y, int z) {
		this(UUID.randomUUID(), x, y, z);
	}

	public PointNode(UUID id, int x, int y, int z) {
		super(id);
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public void set(int x, int y, int z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	/** {minX, minY, minZ, maxX, maxY, maxZ}: its block. */
	public double[] bounds() {
		return new double[]{x, y, z, x + 1, y + 1, z + 1};
	}

	@Override
	public String label() {
		return name() + " (" + x + ", " + y + ", " + z + ")";
	}

	@Override
	public String kind() {
		return "point";
	}

	@Override
	public long blockCount() {
		return 1;
	}

	@Override
	public PointNode copy() {
		PointNode p = new PointNode(x, y, z);
		p.rename(customName());
		p.visible = visible;
		p.waypoint = waypoint;
		return p;
	}
}
