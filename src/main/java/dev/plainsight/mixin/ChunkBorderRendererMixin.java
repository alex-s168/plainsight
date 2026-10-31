package dev.plainsight.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.plainsight.editor.Editor;
import net.minecraft.client.renderer.debug.ChunkBorderRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** In the editor the chunk borders (F3+G) are around the chunk under the cursor (see Editor#chunkBorderAnchor). */
@Mixin(ChunkBorderRenderer.class)
public abstract class ChunkBorderRendererMixin {
	@WrapOperation(method = "emitGizmos", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;blockPosition()Lnet/minecraft/core/BlockPos;"))
	private BlockPos plainsight$editorChunk(Entity camera, Operation<BlockPos> original) {
		return Editor.isActive() ? Editor.get().chunkBorderAnchor() : original.call(camera);
	}
}
