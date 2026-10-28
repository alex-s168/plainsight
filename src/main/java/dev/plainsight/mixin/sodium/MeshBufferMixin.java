package dev.plainsight.mixin.sodium;

import dev.plainsight.render.AlphaOverride;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium's quads all pass through here: applies the thread's {@link AlphaOverride} (translucent fluids). */
@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public abstract class MeshBufferMixin {
	@Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"))
	private void plainsight$alpha(ChunkVertexEncoder.Vertex[] vertices, int material, CallbackInfo ci) {
		if (AlphaOverride.alpha() < 0) return;
		for (ChunkVertexEncoder.Vertex v : vertices) v.color = AlphaOverride.apply(v.color);
	}
}
