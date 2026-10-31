package dev.plainsight.mixin.iris;

import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Parts of an Iris pipeline the minimap's pass through the pack drives itself (see MinimapIris). */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {
	/** The prepare passes run after the shadow pass, which the minimap does not render. */
	@Accessor("prepareRenderer")
	CompositeRenderer plainsight$prepareRenderer();

	/** Set on the minimap's pipeline: the main one already gave the game the pack's block ids (a full chunk rebuild). */
	@Accessor("initializedBlockIds")
	void plainsight$setInitializedBlockIds(boolean initialized);
}
