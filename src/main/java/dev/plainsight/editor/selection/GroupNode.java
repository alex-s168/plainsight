package dev.plainsight.editor.selection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** A named group of selections; selects the union of its children. */
public final class GroupNode extends SelectionNode {
	final List<SelectionNode> children = new ArrayList<>();
	public boolean expanded = true;
	/**
	 * A group of detected structures (the read-only groups planned in #26, e.g. "Nether portals"): its members
	 * have about the same names, so the tree search does not open it. Not saved.
	 */
	public boolean detected;

	public GroupNode() {
		this(UUID.randomUUID());
	}

	public GroupNode(UUID id) {
		super(id);
	}

	public List<SelectionNode> children() {
		return Collections.unmodifiableList(children);
	}

	@Override
	public String kind() {
		return "group";
	}

	/** Sum of the children's counts (overlapping boxes are counted once per box). */
	@Override
	public long blockCount() {
		long n = 0;
		for (SelectionNode c : children) n += c.blockCount();
		return n;
	}

	/** Visits this node and all descendants, parents before children. */
	public void forEach(Consumer<SelectionNode> visitor) {
		visitor.accept(this);
		for (SelectionNode c : children) {
			if (c instanceof GroupNode g) g.forEach(visitor);
			else visitor.accept(c);
		}
	}

	@Override
	public GroupNode copy() {
		GroupNode g = new GroupNode();
		g.rename(customName());
		g.expanded = expanded;
		g.visible = visible;
		g.waypoint = waypoint;
		for (SelectionNode c : children) {
			SelectionNode cc = c.copy();
			cc.parent = g;
			g.children.add(cc);
		}
		return g;
	}
}
