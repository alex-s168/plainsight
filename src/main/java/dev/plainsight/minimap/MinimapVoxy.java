package dev.plainsight.minimap;

import dev.plainsight.Plainsight;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL44;

import java.lang.reflect.Field;

/**
 * Renders the minimap as an extra Voxy pass, right after Voxy's own frame (mixin on its render system):
 * the same LOD world through another of Voxy's viewports (it keeps one per view, as for Vivecraft's eyes),
 * from the minimap camera, into the minimap's textures.
 */
public final class MinimapVoxy {
	/** Set during the minimap pass: Voxy then picks the minimap viewport, projection and targets. */
	public static boolean inPass;
	private static final FogParameters NO_FOG = new FogParameters(0, 0, 0, 0, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f);
	private static Field pipelineField;
	private static long lastReingest;

	private MinimapVoxy() {
	}

	/** Gives the loaded chunks where blocks changed to Voxy again (see {@link Minimap#blockChanged}), at most once a second. */
	private static void reingestChanged() {
		var changed = Minimap.changed;
		long now = System.currentTimeMillis();
		if (changed.isEmpty() || now - lastReingest < 1000) return;
		lastReingest = now;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level != null) {
			for (long k : changed) {
				var chunk = level.getChunkSource().getChunk(net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k),
						net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (chunk != null) me.cortex.voxy.common.world.service.VoxelIngestService.tryAutoIngestChunk(chunk);
			}
		}
		changed.clear();
	}

	public static void render(VoxyRenderSystem rs, float partialTick) {
		if (inPass || !Minimap.prepare(partialTick)) return;
		reingestChanged();
		try {
			AbstractRenderPipeline pipeline = pipeline(rs);
			// shader packs (Iris) bring their own pipeline: not supported
			if (!(pipeline instanceof NormalRenderPipeline) || !(pipeline instanceof MinimapTargets targets)) return;
			RenderProperties props = pipeline.properties;
			int colour = ((com.mojang.renderpearl.backend.opengl.GlTexture) Minimap.colour).glId();
			int depth = ((com.mojang.renderpearl.backend.opengl.GlTexture) Minimap.depth).glId();
			// sky behind the terrain; empty depth everywhere, so Voxy draws the LOD on every pixel
			GL44.glClearTexImage(colour, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[]{0.55f, 0.69f, 0.95f, 1f});
			GL44.glClearTexImage(depth, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[]{props.clearDepth()});
			var proj = new org.joml.Matrix4f(Minimap.projection(props.isReverseZ(), props.isZero2One()));
			inPass = true;
			targets.plainsight$swapMinimapTargets();
			try {
				var p = Minimap.cameraPos;
				var viewport = rs.setupViewport(proj, Minimap.modelView, NO_FOG, Minimap.size, Minimap.size, p.x, p.y, p.z);
				rs.renderOpaque(viewport, depth, colour);
			} finally {
				targets.plainsight$swapMinimapTargets();
				inPass = false;
			}
			Minimap.rendered++;
		} catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
			Minimap.error = e.toString();
			Plainsight.LOG.error("Minimap rendering failed; minimap off", e);
		}
	}

	private static AbstractRenderPipeline pipeline(VoxyRenderSystem rs) throws ReflectiveOperationException {
		if (pipelineField == null) {
			pipelineField = VoxyRenderSystem.class.getDeclaredField("pipeline");
			pipelineField.setAccessible(true);
		}
		return (AbstractRenderPipeline) pipelineField.get(rs);
	}
}
