package dev.plainsight.mixin.voxy;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The section renderer, shared by the minimap's Voxy pipeline (with a shader pack) with the main one. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public interface RenderPipelineAccessor {
	@Accessor("sectionRenderer")
	AbstractSectionRenderer<?, ?> plainsight$sectionRenderer();
}
