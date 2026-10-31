package dev.plainsight.mixin.voxy;

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
 * With a shader pack the minimap has its own Voxy pipeline (drawing into its own Iris pipeline's targets),
 * built from the render system's parts and swapped in for the minimap's pass.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public interface VoxyRenderSystemAccessor {
	@Accessor("pipeline")
	AbstractRenderPipeline plainsight$pipeline();

	@Mutable
	@Accessor("pipeline")
	void plainsight$setPipeline(AbstractRenderPipeline pipeline);

	@Accessor("properties")
	RenderProperties plainsight$properties();

	@Accessor("nodeManager")
	AsyncNodeManager plainsight$nodeManager();

	@Accessor("nodeCleaner")
	NodeCleaner plainsight$nodeCleaner();

	@Accessor("traversal")
	HierarchicalOcclusionTraverser plainsight$traversal();
}
