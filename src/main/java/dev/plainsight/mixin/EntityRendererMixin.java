package dev.plainsight.mixin;

import dev.plainsight.module.CutawayRegion;
import dev.plainsight.module.SeeThrough;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities in the opened space of the see-through cut are lit like the blocks around them. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
	@Inject(method = "getSkyLightLevel", at = @At("RETURN"), cancellable = true)
	private void plainsight$cutSkyLight(T entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		CutawayRegion cut = SeeThrough.region();
		if (cut.isEmpty() || cir.getReturnValueI() >= cut.skyLight()) return;
		if (cut.near(pos.getX(), pos.getY(), pos.getZ(), 1.0)) cir.setReturnValue(cut.skyLight());
	}
}
