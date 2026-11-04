package dev.infiniterra.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Shader packs (Iris) render into the game's main target: the camera pass's pass through the pack swaps in its own. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Mutable
	@Accessor("mainRenderTarget")
	void infiniterra$setMainRenderTarget(RenderTarget target);
}
