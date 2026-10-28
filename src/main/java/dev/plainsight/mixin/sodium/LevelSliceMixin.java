package dev.plainsight.mixin.sodium;

import dev.plainsight.module.CutawayRegion;
import dev.plainsight.module.SeeThrough;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * See-through with Sodium: its mesher reads blocks, shading and light from the {@link LevelSlice}, so cut
 * blocks are reported as air (hidden, neighbours keep their faces, no ambient occlusion from them) and
 * the opened space gets the sky light around the camera. Unlike vanilla, cut blocks are not drawn faintly.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin {
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	@Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
	private void plainsight$cutXyz(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		CutawayRegion cut = SeeThrough.region();
		if (!cut.isEmpty() && cut.contains(x, y, z)) cir.setReturnValue(AIR);
	}

	@Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
	private void plainsight$cutPos(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		CutawayRegion cut = SeeThrough.region();
		if (!cut.isEmpty() && cut.contains(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(AIR);
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void plainsight$cutFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		CutawayRegion cut = SeeThrough.region();
		if (!cut.isEmpty() && cut.contains(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
	}

	@Inject(method = "getBrightness", at = @At("RETURN"), cancellable = true)
	private void plainsight$openedSkyLight(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		CutawayRegion cut = SeeThrough.region();
		if (layer != LightLayer.SKY || cut.isEmpty() || cir.getReturnValueI() >= cut.skyLight()) return;
		if (cut.near(pos.getX(), pos.getY(), pos.getZ(), 1.0)) cir.setReturnValue(cut.skyLight());
	}
}
