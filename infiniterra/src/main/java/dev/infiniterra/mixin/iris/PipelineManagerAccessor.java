package dev.infiniterra.mixin.iris;

import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The active Iris pipeline, swapped for the camera pass's during its pass (samplers and Voxy look it up). */
@Mixin(value = PipelineManager.class, remap = false)
public interface PipelineManagerAccessor {
	@Accessor("pipeline")
	void infiniterra$setPipeline(WorldRenderingPipeline pipeline);
}
