package dev.infiniterra.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.infiniterra.impl.style.VoxyStyle;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks fluid models on the GPU: Voxy's model flags ({@code BlockModel.flagsA}: 2 biome colour, 4 translucent,
 * 8 shaded) get {@link VoxyStyle#FLUID_FLAG} for fluid blocks, so the terrain shader can apply the fluid opacity of
 * {@link dev.infiniterra.api.TerrainStyle} (see {@link ShaderPatchMixin}). Waterlogged blocks draw their fluid
 * as a model of the fluid's block, which gets the flag too.
 */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelFluidFlagMixin {
	// the third int written for a model is flagsA (after the face data loop and the face count)
	@WrapOperation(method = "processTextureBakeResult", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/system/MemoryUtil;memPutInt(JI)V", ordinal = 2))
	private void infiniterra$fluidFlag(long address, int value, Operation<Void> original, @Local(argsOnly = true) BlockState state) {
		// flagsA only uses the low bits: anything else means Voxy changed and this is not flagsA
		if ((value & ~0xFF) == 0 && state.getBlock() instanceof LiquidBlock) {
			value |= VoxyStyle.FLUID_FLAG;
			VoxyStyle.fluidModels++;
		}
		original.call(address, value);
	}
}
