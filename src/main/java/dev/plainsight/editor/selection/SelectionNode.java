package dev.plainsight.editor.selection;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * An element of the selection tree. Every node is a selection on its own: a group is the union of its
 * children, a box selects the blocks inside it, an entity node selects one entity.
 */
public abstract sealed class SelectionNode permits GroupNode, BoxNode, EntityNode {
	private final UUID id;
	/** Name given by the user; {@code null} shows the generated default name. */
	private @Nullable String customName;
	/** Generated name, e.g. "box 3", assigned once when the node enters a tree. */
	String defaultName = "";
	@Nullable GroupNode parent;
	public boolean visible = true;

	protected SelectionNode(UUID id) {
		this.id = id;
	}

	public UUID id() {
		return id;
	}

	public @Nullable GroupNode parent() {
		return parent;
	}

	public String name() {
		return customName != null ? customName : defaultName;
	}

	public @Nullable String customName() {
		return customName;
	}

	public void rename(@Nullable String name) {
		customName = name == null || name.isBlank() ? null : name.strip();
	}

	/** Text shown in the tree, e.g. {@code box 1 (500 blocks)}. */
	public abstract String label();

	/** Kind used for generated names: "box", "group", "player", ... */
	public abstract String kind();

	/** Number of blocks selected by this node (entities count none). */
	public abstract long blockCount();

	public boolean isAncestorOf(SelectionNode other) {
		for (GroupNode p = other.parent; p != null; p = p.parent) {
			if (p == this) return true;
		}
		return false;
	}

	public int depth() {
		int d = 0;
		for (GroupNode p = parent; p != null; p = p.parent) d++;
		return d;
	}

	public abstract SelectionNode copy();

	static String blocks(long n, boolean exact) {
		return (exact ? "" : "~") + n + (n == 1 ? " block" : " blocks");
	}
}
