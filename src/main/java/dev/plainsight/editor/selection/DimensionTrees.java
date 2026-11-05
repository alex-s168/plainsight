package dev.plainsight.editor.selection;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The selection trees of one world (or server), one per dimension, each saved as
 * {@code <dir>/<dimension id>.json} (e.g. {@code minecraft_the_nether.json}). Trees are loaded when first
 * asked for and kept, so the tree view can show another dimension's selections.
 */
public final class DimensionTrees {
	private static final Logger LOG = LoggerFactory.getLogger("plainsight");
	public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether", END = "minecraft:the_end";

	private final Path dir;
	private final Map<String, SelectionTree> trees = new LinkedHashMap<>();
	private final Map<String, Integer> savedVersions = new LinkedHashMap<>();

	/**
	 * @param legacyFile the world's selections from before they were kept per dimension: moved to the
	 *                   overworld's file if that does not exist yet ({@code null}: none)
	 */
	public DimensionTrees(Path dir, @Nullable Path legacyFile) {
		this.dir = dir;
		if (legacyFile != null && Files.isRegularFile(legacyFile)) {
			Path overworld = file(OVERWORLD);
			if (!Files.exists(overworld)) {
				try {
					Files.createDirectories(dir);
					Files.move(legacyFile, overworld);
					LOG.info("Moved selections {} to the overworld's {}", legacyFile, overworld);
				} catch (IOException e) {
					LOG.warn("Could not move selections {} to {}", legacyFile, overworld, e);
				}
			}
		}
	}

	public Path dir() {
		return dir;
	}

	public Path file(String dimension) {
		return dir.resolve(dimension.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
	}

	/** The dimension's tree, loaded from its file on first use. */
	public SelectionTree tree(String dimension) {
		SelectionTree t = trees.get(dimension);
		if (t != null) return t;
		t = new SelectionTree();
		Path f = file(dimension);
		try {
			SelectionStore.load(t, f);
		} catch (Exception e) {
			LOG.warn("Could not load selections from {}", f, e);
			t.clear();
		}
		trees.put(dimension, t);
		savedVersions.put(dimension, t.version());
		return t;
	}

	/** The dimensions whose trees are loaded. */
	public Set<String> loaded() {
		return trees.keySet();
	}

	/** Saves the trees changed since they were loaded or last saved. */
	public void saveChanged() {
		for (var e : trees.entrySet()) {
			if (e.getValue().version() == savedVersions.get(e.getKey())) continue;
			Path f = file(e.getKey());
			try {
				SelectionStore.save(e.getValue(), f);
				savedVersions.put(e.getKey(), e.getValue().version());
			} catch (IOException ex) {
				LOG.warn("Could not save selections to {}", f, ex);
			}
		}
	}

	/** Whether a loaded tree has changes not saved yet. */
	public boolean changed() {
		for (var e : trees.entrySet()) if (e.getValue().version() != savedVersions.get(e.getKey())) return true;
		return false;
	}

	/** "Overworld", "Nether", "End", or the id of another (modded) dimension. */
	public static String displayName(String dimension) {
		return switch (dimension) {
			case OVERWORLD -> "Overworld";
			case NETHER -> "Nether";
			case END -> "End";
			default -> dimension;
		};
	}
}
