package dev.plainsight.render;

import dev.plainsight.editor.math.OrientedBox;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Tints the blocks a selection box covers: the faces of its solid blocks that are open to the air (inside
 * or outside the box). Where a box reaches above the terrain its own faces float in the air, and where
 * it ends inside a wall or the ground nothing showed the cut; this shows the selected terrain itself.
 * The faces are found per box and kept until the box changes (and refreshed now and then for block
 * changes); very large boxes are not tinted.
 */
public final class BoxTerrainTint {
	private static final long MAX_BLOCKS = 150_000;
	private static final int MAX_FACES = 40_000;
	private static final long REFRESH_MS = 500;
	private static final double LIFT = 0.006;
	private static final Direction[] DIRS = Direction.values();

	/** Faces of one box: x, y, z, direction ordinal per face. */
	private record Faces(IntArrayList data, long built) {
	}

	private static final Map<String, Faces> CACHE = new HashMap<>();
	private static final Map<String, Faces> USED = new HashMap<>();
	/** The last faces per box object (an edited box keeps its object while it changes). */
	private static final Map<OrientedBox, Faces> LAST = new java.util.WeakHashMap<>();
	private static final long LARGE = 20_000, DRAG_REFRESH_MS = 250;

	private BoxTerrainTint() {
	}

	public static void draw(Overlay o, OrientedBox box, int color) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || box.blockCount() > MAX_BLOCKS) return;
		String key = box.center + " " + box.half + " " + box.rotation;
		Faces f = USED.get(key);
		if (f == null) f = CACHE.get(key);
		long now = System.currentTimeMillis();
		// a large box being dragged changes every frame: its tint follows a few times a second
		Faces last = LAST.get(box);
		if (f == null && last != null && box.blockCount() > LARGE && now - last.built < DRAG_REFRESH_MS) f = last;
		if (f == null || now - f.built > REFRESH_MS) f = new Faces(collect(level, box), now);
		USED.put(key, f);
		LAST.put(box, f);
		IntArrayList d = f.data;
		for (int i = 0; i < d.size(); i += 4) o.blockFace(Overlay.Layer.WORLD, d.getInt(i), d.getInt(i + 1), d.getInt(i + 2), DIRS[d.getInt(i + 3)], LIFT, color);
	}

	/** Called once per frame after drawing: forgets the faces of boxes that were not drawn. */
	public static void endFrame() {
		CACHE.clear();
		CACHE.putAll(USED);
		USED.clear();
	}

	private static IntArrayList collect(ClientLevel level, OrientedBox box) {
		IntArrayList out = new IntArrayList();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), n = new BlockPos.MutableBlockPos();
		box.forEachBlock((x, y, z) -> {
			if (out.size() >= MAX_FACES * 4) return;
			p.set(x, y, z);
			BlockState s = level.getBlockState(p);
			// solid blocks only (not air, fluids or plants)
			if (s.isAir() || s.getCollisionShape(level, p).isEmpty()) return;
			for (Direction d : DIRS) {
				n.setWithOffset(p, d);
				if (level.getBlockState(n).canOcclude()) continue;
				out.add(x);
				out.add(y);
				out.add(z);
				out.add(d.ordinal());
			}
		});
		return out;
	}
}
