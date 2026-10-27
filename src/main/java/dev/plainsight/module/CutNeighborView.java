package dev.plainsight.module;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The world as seen by blocks next to the cutaway: cut blocks look like air, so their neighbours keep
 * the faces that would otherwise be culled against them (no holes into the void).
 */
public final class CutNeighborView implements BlockAndTintGetter {
	private final BlockAndTintGetter level;
	private final CutawayRegion cut;
	private final BlockPos self;

	public CutNeighborView(BlockAndTintGetter level, CutawayRegion cut, BlockPos self) {
		this.level = level;
		this.cut = cut;
		this.self = self.immutable();
	}

	private boolean hidden(BlockPos pos) {
		return !pos.equals(self) && cut.contains(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		return hidden(pos) ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return hidden(pos) ? Blocks.AIR.defaultBlockState().getFluidState() : level.getFluidState(pos);
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return level.getBlockEntity(pos);
	}

	@Override
	public CardinalLighting cardinalLighting() {
		return level.cardinalLighting();
	}

	@Override
	public int getBlockTint(BlockPos pos, ColorResolver color) {
		return level.getBlockTint(pos, color);
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return level.getLightEngine();
	}

	@Override
	public int getHeight() {
		return level.getHeight();
	}

	@Override
	public int getMinY() {
		return level.getMinY();
	}

	/** Whether any of the six neighbours of {@code pos} is cut. */
	public static boolean touchesCut(CutawayRegion cut, BlockPos pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		return cut.contains(x + 1, y, z) || cut.contains(x - 1, y, z) || cut.contains(x, y + 1, z)
				|| cut.contains(x, y - 1, z) || cut.contains(x, y, z + 1) || cut.contains(x, y, z - 1);
	}
}
