package dev.infiniterra.mixin.voxy;

import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The LOD geometry and block models, shared by the camera pass's own section renderer (with a shader pack its
 * terrain shaders are compiled against its own pipeline's uniform and sampler layout).
 */
@Mixin(value = AbstractSectionRenderer.class, remap = false)
public interface SectionRendererAccessor {
	@Accessor("geometryManager")
	IGeometryData infiniterra$geometry();

	@Accessor("modelStore")
	ModelStore infiniterra$modelStore();
}
