package dev.infiniterra.mixin.iris;

import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Parts of an Iris pipeline the camera pass's pass through the pack drives itself (see PassIris). */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {
	/** The prepare passes run after the shadow pass, which the camera pass does not render. */
	@Accessor("prepareRenderer")
	CompositeRenderer infiniterra$prepareRenderer();

	/** Set on the camera pass's pipeline: the main one already gave the game the pack's block ids (a full chunk rebuild). */
	@Accessor("initializedBlockIds")
	void infiniterra$setInitializedBlockIds(boolean initialized);
}
