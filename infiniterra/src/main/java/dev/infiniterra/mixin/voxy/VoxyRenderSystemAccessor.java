package dev.infiniterra.mixin.voxy;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * With a shader pack the camera pass has its own Voxy pipeline (drawing into its own Iris pipeline's targets),
 * built from the render system's parts and swapped in for the camera pass's pass.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public interface VoxyRenderSystemAccessor {
	@Accessor("pipeline")
	AbstractRenderPipeline infiniterra$pipeline();

	@Mutable
	@Accessor("pipeline")
	void infiniterra$setPipeline(AbstractRenderPipeline pipeline);

	@Accessor("properties")
	RenderProperties infiniterra$properties();

	@Accessor("nodeManager")
	AsyncNodeManager infiniterra$nodeManager();

	@Accessor("nodeCleaner")
	NodeCleaner infiniterra$nodeCleaner();

	@Accessor("traversal")
	HierarchicalOcclusionTraverser infiniterra$traversal();
}
