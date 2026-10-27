package dev.plainsight.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import dev.plainsight.render.AlphaOverride;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scales the alpha of vertices written while an alpha override is set (see {@link AlphaOverride}). */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {
	@ModifyVariable(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int plainsight$cutAlpha(int color) {
		return AlphaOverride.apply(color);
	}

	@ModifyVariable(method = "setColor(I)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int plainsight$cutAlphaPacked(int color) {
		return AlphaOverride.apply(color);
	}
}
