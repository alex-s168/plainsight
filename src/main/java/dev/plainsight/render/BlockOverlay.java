package dev.plainsight.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Blocks drawn into the world as if they were placed (e.g. a paste preview): full block models, lit by
 * the world's light at their position, with faces between overlay blocks culled. Register an overlay
 * with {@link #show()}; it is drawn every frame until {@link #hide()}.
 */
public final class BlockOverlay {
	private static final List<BlockOverlay> VISIBLE = new CopyOnWriteArrayList<>();

	private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
	/** Offset applied to every block (lets a preview follow the cursor without rebuilding). */
	public final BlockPos.MutableBlockPos offset = new BlockPos.MutableBlockPos();
	/** Outline colour (ARGB) drawn around the overlay's blocks, 0 for none. */
	public int outlineColor;
	/** 1 draws the blocks solid; below that they are drawn translucent like a ghost preview. */
	public float opacity = 0.65f;

	public static List<BlockOverlay> visible() {
		return VISIBLE;
	}

	public void show() {
		if (!VISIBLE.contains(this)) VISIBLE.add(this);
	}

	public void hide() {
		VISIBLE.remove(this);
	}

	public void put(BlockPos pos, BlockState state) {
		if (state.isAir()) blocks.remove(pos.asLong());
		else blocks.put(pos.asLong(), state);
	}

	public void clear() {
		blocks.clear();
	}

	public int size() {
		return blocks.size();
	}

	/** State at an overlay-local position (air when empty). */
	public BlockState get(BlockPos local) {
		BlockState s = blocks.get(local.asLong());
		return s == null ? Blocks.AIR.defaultBlockState() : s;
	}

	public Long2ObjectMap<BlockState> blocks() {
		return blocks;
	}
}
