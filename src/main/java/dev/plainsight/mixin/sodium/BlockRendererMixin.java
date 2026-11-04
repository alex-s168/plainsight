package dev.plainsight.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.render.CutMeshing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** See-through: a cut block's quads go to Sodium's translucent pass, so their reduced alpha blends. */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
	@WrapOperation(method = "processQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/DefaultMaterials;forChunkLayer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;"))
	private Material plainsight$cutTranslucent(ChunkSectionLayer layer, Operation<Material> original) {
		return CutMeshing.raw() ? DefaultMaterials.TRANSLUCENT : original.call(layer);
	}
}
