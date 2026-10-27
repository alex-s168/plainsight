package dev.plainsight.editor.selection;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The tree of selections. The (hidden) root holds the top-level nodes. Generated names count per kind
 * ("box 1", "box 2", "group 1") and are never reused within a tree.
 */
public final class SelectionTree {
	private final GroupNode root = new GroupNode(new UUID(0, 0));
	private final Map<String, Integer> counters = new HashMap<>();
	private final Map<UUID, SelectionNode> byId = new HashMap<>();
	/** Nodes selected in the tree view (in click order). */
	private final LinkedHashSet<SelectionNode> selected = new LinkedHashSet<>();
	private int version;

	public SelectionTree() {
		byId.put(root.id(), root);
	}

	public GroupNode root() {
		return root;
	}

	/** Increments on every structural or naming change (used to persist and refresh views). */
	public int version() {
		return version;
	}

	public void touch() {
		version++;
	}

	public Optional<SelectionNode> find(UUID id) {
		return Optional.ofNullable(byId.get(id));
	}

	public List<SelectionNode> all() {
		List<SelectionNode> out = new ArrayList<>();
		root.forEach(n -> {
			if (n != root) out.add(n);
		});
		return out;
	}

	// -- naming ---------------------------------------------------------------------------------------------

	private void assignNames(SelectionNode node) {
		// nodes named by the user (or entities, which are named after their entity) get a generated
		// name only when they lose their own one
		boolean wantsName = node != root && node.defaultName.isEmpty() && node.customName() == null && !(node instanceof EntityNode);
		if (wantsName) node.defaultName = next(node.kind());
		if (node instanceof GroupNode g) for (SelectionNode c : g.children) assignNames(c);
	}

	private String next(String kind) {
		int n = counters.merge(kind, 1, Integer::sum);
		return kind + " " + n;
	}

	/** Restores counters after loading so new names continue after existing ones. */
	void bumpCounter(String kind, int atLeast) {
		counters.merge(kind, atLeast, Math::max);
	}

	void setDefaultName(SelectionNode node, String name) {
		node.defaultName = name;
	}

	// -- structure ------------------------------------------------------------------------------------------

	/** Adds {@code node} (with its subtree) under {@code parent} at {@code index} (-1 = end). */
	public <T extends SelectionNode> T add(T node, @Nullable GroupNode parent, int index) {
		GroupNode p = parent == null ? root : parent;
		if (node.parent != null) throw new IllegalArgumentException("node already in a tree");
		if (node == p || (node instanceof GroupNode g && g.isAncestorOf(p))) throw new IllegalArgumentException("cycle");
		int i = index < 0 || index > p.children.size() ? p.children.size() : index;
		p.children.add(i, node);
		node.parent = p;
		register(node);
		assignNames(node);
		version++;
		return node;
	}

	public <T extends SelectionNode> T add(T node) {
		return add(node, null, -1);
	}

	private void register(SelectionNode n) {
		byId.put(n.id(), n);
		if (n instanceof GroupNode g) for (SelectionNode c : g.children) register(c);
	}

	private void unregister(SelectionNode n) {
		byId.remove(n.id());
		selected.remove(n);
		if (n instanceof GroupNode g) for (SelectionNode c : g.children) unregister(c);
	}

	public void remove(SelectionNode node) {
		if (node == root || node.parent == null) return;
		node.parent.children.remove(node);
		node.parent = null;
		unregister(node);
		version++;
	}

	/**
	 * Moves {@code node} into {@code newParent} at {@code index} (index in the new parent's list before the
	 * move). Moving a group into itself or a descendant is refused.
	 */
	public boolean move(SelectionNode node, @Nullable GroupNode newParent, int index) {
		GroupNode p = newParent == null ? root : newParent;
		if (node == root || node.parent == null) return false;
		if (node == p || (node instanceof GroupNode g && g.isAncestorOf(p))) return false;
		GroupNode old = node.parent;
		int oldIndex = old.children.indexOf(node);
		int i = index < 0 || index > p.children.size() ? p.children.size() : index;
		if (old == p && oldIndex < i) i--;
		old.children.remove(oldIndex);
		p.children.add(Math.min(i, p.children.size()), node);
		node.parent = p;
		version++;
		return true;
	}

	/** Moves {@code node} directly before {@code target} (into target's parent). */
	public boolean moveBefore(SelectionNode node, SelectionNode target) {
		if (target.parent == null || node == target) return false;
		return move(node, target.parent, target.parent.children.indexOf(target));
	}

	public boolean moveAfter(SelectionNode node, SelectionNode target) {
		if (target.parent == null || node == target) return false;
		return move(node, target.parent, target.parent.children.indexOf(target) + 1);
	}

	/**
	 * Puts the given nodes into a new group, placed where the first of them (in tree order) was. Nodes
	 * whose ancestor is also given move along with that ancestor.
	 */
	public GroupNode group(List<SelectionNode> nodes) {
		List<SelectionNode> top = topLevel(nodes);
		GroupNode g = new GroupNode();
		if (top.isEmpty()) return add(g);
		SelectionNode first = top.get(0);
		GroupNode parent = first.parent;
		int index = parent.children.indexOf(first);
		add(g, parent, index);
		for (SelectionNode n : top) move(n, g, -1);
		return g;
	}

	/** Replaces a group by its children. */
	public void ungroup(GroupNode g) {
		if (g == root || g.parent == null) return;
		GroupNode parent = g.parent;
		int index = parent.children.indexOf(g);
		for (SelectionNode c : new ArrayList<>(g.children)) move(c, parent, index++);
		remove(g);
	}

	public SelectionNode duplicate(SelectionNode node) {
		SelectionNode copy = node.copy();
		GroupNode parent = node.parent == null ? root : node.parent;
		return add(copy, parent, parent.children.indexOf(node) + 1);
	}

	/** Drops nodes that have an ancestor in the list and sorts the rest in tree order. */
	public List<SelectionNode> topLevel(List<SelectionNode> nodes) {
		Set<SelectionNode> set = new LinkedHashSet<>(nodes);
		List<SelectionNode> out = new ArrayList<>();
		for (SelectionNode n : set) {
			boolean covered = false;
			for (GroupNode p = n.parent; p != null; p = p.parent) {
				if (set.contains(p)) {
					covered = true;
					break;
				}
			}
			if (!covered && n != root && n.parent != null) out.add(n);
		}
		List<SelectionNode> order = all();
		out.sort(Comparator.comparingInt(order::indexOf));
		return out;
	}

	public void rename(SelectionNode node, @Nullable String name) {
		node.rename(name);
		if (node.parent != null) assignNames(node);
		version++;
	}

	public void clear() {
		for (SelectionNode c : new ArrayList<>(root.children)) remove(c);
		counters.clear();
		version++;
	}

	// -- tree view selection --------------------------------------------------------------------------------

	public Set<SelectionNode> selected() {
		return selected;
	}

	public void select(SelectionNode n, boolean additive) {
		if (!additive) selected.clear();
		if (additive && selected.contains(n)) selected.remove(n);
		else selected.add(n);
	}

	public void deselect(SelectionNode n) {
		selected.remove(n);
	}

	public void clearSelection() {
		selected.clear();
	}

	/** Leaf nodes (boxes and entities) inside {@code node}, including itself. */
	public static List<SelectionNode> leaves(SelectionNode node) {
		List<SelectionNode> out = new ArrayList<>();
		if (node instanceof GroupNode g) {
			g.forEach(n -> {
				if (!(n instanceof GroupNode)) out.add(n);
			});
		} else {
			out.add(node);
		}
		return out;
	}
}
