package dev.plainsight.mixin.sodium;

import dev.plainsight.render.AlphaOverride;
import dev.plainsight.render.CutMeshing;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium's quads all pass through here: applies the thread's {@link AlphaOverride} (translucent fluids,
 * see-through). Cut blocks are drawn without face shading and ambient occlusion: darker side faces would
 * look more transparent than the top at the same alpha.
 */
@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public abstract class MeshBufferMixin {
	@Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"))
	private void plainsight$alpha(ChunkVertexEncoder.Vertex[] vertices, int material, CallbackInfo ci) {
		if (AlphaOverride.alpha() < 0) return;
		boolean cut = CutMeshing.raw();
		for (ChunkVertexEncoder.Vertex v : vertices) {
			v.color = AlphaOverride.apply(v.color);
			if (cut) v.ao = 1f;
		}
	}
}
