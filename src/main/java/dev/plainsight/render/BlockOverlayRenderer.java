package dev.plainsight.render;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Submits {@link BlockOverlay} blocks through the moving-block (piston) path, which tesselates real
 * block models with ambient occlusion and the level's sky/block light at each position.
 */
public final class BlockOverlayRenderer {
	private BlockOverlayRenderer() {
	}

	/** Block state view for one overlay block: neighbours come from the overlay, not the world. */
	static final class OverlayBlock extends MovingBlockRenderState {
		final BlockOverlay overlay;
		private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();

		OverlayBlock(BlockOverlay overlay) {
			this.overlay = overlay;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			local.set(pos.getX() - overlay.offset.getX(), pos.getY() - overlay.offset.getY(), pos.getZ() - overlay.offset.getZ());
			return overlay.get(local);
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}
	}

	public static int submitted;

	/** Alpha (0..255) the given moving-block state is drawn with, or -1 for a normal (opaque) block. */
	public static int alphaOf(MovingBlockRenderState state) {
		if (state instanceof OverlayBlock b && b.overlay.opacity < 0.999f) return Math.round(Math.max(0, b.overlay.opacity) * 255);
		return -1;
	}

	public static void submit(SubmitNodeCollector collector, PoseStack pose, Vec3 camera) {
		submitted = 0;
		boolean translucent = false;
		for (BlockOverlay o : BlockOverlay.visible()) translucent |= o.opacity < 0.999f && o.size() > 0;
		AlphaOverride.overlayActive = translucent;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		for (BlockOverlay overlay : BlockOverlay.visible()) {
			for (Long2ObjectMap.Entry<BlockState> e : overlay.blocks().long2ObjectEntrySet()) {
				BlockPos local = BlockPos.of(e.getLongKey());
				BlockPos world = local.offset(overlay.offset);
				OverlayBlock state = new OverlayBlock(overlay);
				state.blockPos = world;
				state.randomSeedPos = world;
				state.blockState = e.getValue();
				state.biome = level.getBiome(world);
				state.cardinalLighting = level.cardinalLighting();
				state.lightEngine = level.getLightEngine();
				pose.pushPose();
				pose.translate(world.getX() - camera.x, world.getY() - camera.y, world.getZ() - camera.z);
				collector.submitMovingBlock(pose, state, overlay.outlineColor);
				pose.popPose();
				submitted++;
			}
		}
	}
}
