package dev.plainsight.editor.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plainsight.editor.math.OrientedBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Saves and loads selection trees as JSON. */
public final class SelectionStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private SelectionStore() {
	}

	public static JsonObject toJson(SelectionTree tree) {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.add("nodes", children(tree.root()));
		return o;
	}

	private static JsonArray children(GroupNode g) {
		JsonArray a = new JsonArray();
		for (SelectionNode c : g.children()) a.add(node(c));
		return a;
	}

	private static JsonObject node(SelectionNode n) {
		JsonObject o = new JsonObject();
		o.addProperty("id", n.id().toString());
		o.addProperty("defaultName", n.defaultName);
		if (n.customName() != null) o.addProperty("name", n.customName());
		if (!n.visible) o.addProperty("visible", false);
		switch (n) {
			case GroupNode g -> {
				o.addProperty("type", "group");
				if (!g.expanded) o.addProperty("expanded", false);
				o.add("children", children(g));
			}
			case BoxNode b -> {
				o.addProperty("type", "box");
				OrientedBox box = b.box();
				o.add("center", vec(box.center.x, box.center.y, box.center.z));
				o.add("half", vec(box.half.x, box.half.y, box.half.z));
				JsonArray q = new JsonArray();
				q.add(box.rotation.x);
				q.add(box.rotation.y);
				q.add(box.rotation.z);
				q.add(box.rotation.w);
				o.add("rotation", q);
				if (b.startCorner != 0) o.addProperty("startCorner", b.startCorner);
			}
			case EntityNode e -> {
				o.addProperty("type", "entity");
				o.addProperty("entity", e.entity().toString());
				o.addProperty("entityType", e.type());
				o.addProperty("entityName", e.entityName());
			}
		}
		return o;
	}

	private static JsonArray vec(double x, double y, double z) {
		JsonArray a = new JsonArray();
		a.add(x);
		a.add(y);
		a.add(z);
		return a;
	}

	/** Replaces the tree's contents with the saved ones. */
	public static void fromJson(SelectionTree tree, JsonObject o) {
		tree.clear();
		JsonArray nodes = o.getAsJsonArray("nodes");
		if (nodes == null) return;
		for (JsonElement e : nodes) tree.add(read(tree, e.getAsJsonObject()), null, -1);
	}

	private static SelectionNode read(SelectionTree tree, JsonObject o) {
		UUID id = UUID.fromString(o.get("id").getAsString());
		String type = o.get("type").getAsString();
		SelectionNode n = switch (type) {
			case "group" -> {
				GroupNode g = new GroupNode(id);
				if (o.has("expanded")) g.expanded = o.get("expanded").getAsBoolean();
				for (JsonElement c : o.getAsJsonArray("children")) {
					SelectionNode child = read(tree, c.getAsJsonObject());
					child.parent = g;
					g.children.add(child);
				}
				yield g;
			}
			case "box" -> {
				OrientedBox box = new OrientedBox();
				JsonArray c = o.getAsJsonArray("center"), h = o.getAsJsonArray("half"), q = o.getAsJsonArray("rotation");
				box.center.set(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
				box.half.set(h.get(0).getAsDouble(), h.get(1).getAsDouble(), h.get(2).getAsDouble());
				box.rotation.set(q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble(), q.get(3).getAsDouble()).normalize();
				BoxNode bn = new BoxNode(id, box);
				if (o.has("startCorner")) bn.startCorner = o.get("startCorner").getAsInt() & 7;
				yield bn;
			}
			case "entity" -> new EntityNode(id, UUID.fromString(o.get("entity").getAsString()), o.get("entityType").getAsString(),
					o.get("entityName").getAsString());
			default -> throw new IllegalArgumentException("unknown selection type " + type);
		};
		if (o.has("name")) n.rename(o.get("name").getAsString());
		if (o.has("visible")) n.visible = o.get("visible").getAsBoolean();
		String def = o.has("defaultName") ? o.get("defaultName").getAsString() : "";
		tree.setDefaultName(n, def);
		int space = def.lastIndexOf(' ');
		if (space > 0) {
			try {
				tree.bumpCounter(def.substring(0, space), Integer.parseInt(def.substring(space + 1)));
			} catch (NumberFormatException ignored) {
				// a default name without a number does not affect counters
			}
		}
		return n;
	}

	public static void save(SelectionTree tree, Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(toJson(tree)), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void load(SelectionTree tree, Path file) throws IOException {
		if (!Files.exists(file)) {
			tree.clear();
			return;
		}
		fromJson(tree, JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
	}
}
