package dev.plainsight.mixin.sodium;

import dev.plainsight.editor.Editor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Like vanilla's spectator rule (see the camera mixin): no occlusion culling while the editor camera is
 * inside solid blocks (caves seen from within rock) or outside the loaded chunks (else the loaded terrain
 * around the player can disappear).
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class OcclusionCullerMixin {
	@ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private boolean plainsight$noCulling(boolean useOcclusionCulling) {
		return useOcclusionCulling && !Editor.disableCaveCulling;
	}

}
