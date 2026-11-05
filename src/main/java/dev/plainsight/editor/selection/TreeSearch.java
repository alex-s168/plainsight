package dev.plainsight.editor.selection;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A fuzzy search over a selection tree: which nodes the tree view shows for a query and which groups it
 * opens to show them. A node matches when the query's letters appear in its title in order (case and
 * spaces ignored: "nprt" finds "Nether portal"). Matching nodes are shown with their groups, which open,
 * except groups of detected structures ({@link GroupNode#detected}): their members all have about the same
 * name, so they stay closed. A matching group shows everything in it.
 */
public final class TreeSearch {
	/** Nodes shown: the matches and the groups on the way to them. */
	public final Set<SelectionNode> shown = new HashSet<>();
	/** Groups opened to show matches inside them. */
	public final Set<GroupNode> expand = new HashSet<>();
	/** Matching groups: all of their contents are shown. */
	public final Set<GroupNode> whole = new HashSet<>();
	private final String query;
	private int matches;

	public TreeSearch(GroupNode root, String query) {
		this.query = normalize(query);
		for (SelectionNode c : root.children()) visit(c);
	}

	/** Number of matching nodes. */
	public int matches() {
		return matches;
	}

	private boolean visit(SelectionNode n) {
		boolean self = matches(query, n.title());
		if (self) matches++;
		boolean inside = false;
		if (n instanceof GroupNode g) {
			for (SelectionNode c : g.children()) inside |= visit(c);
			if (inside && !g.detected) expand.add(g);
			if (self) whole.add(g);
		}
		if (self || inside) shown.add(n);
		return self || inside;
	}

	/** Whether the node is shown: it or a group it is in matched, or it contains a match. */
	public boolean shows(SelectionNode n) {
		if (shown.contains(n)) return true;
		for (GroupNode p = n.parent(); p != null; p = p.parent()) if (whole.contains(p)) return true;
		return false;
	}

	/** Whether {@code text} contains the query's letters in order, ignoring case and spaces. */
	public static boolean matches(String query, String text) {
		String q = normalize(query);
		if (q.isEmpty()) return true;
		String t = text.toLowerCase(Locale.ROOT);
		int i = 0;
		for (int j = 0; j < t.length() && i < q.length(); j++) if (t.charAt(j) == q.charAt(i)) i++;
		return i == q.length();
	}

	private static String normalize(String query) {
		return query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}
}
