package dev.plainsight.minimap;

import dev.plainsight.Plainsight;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL44;


/**
 * Renders the minimap as an extra Voxy pass, right after Voxy's own frame (mixin on its render system):
 * the same LOD world through another of Voxy's viewports (it keeps one per view, as for Vivecraft's eyes),
 * from the minimap camera, into the minimap's textures. With a shader pack (Voxy's Iris pipeline) the pass
 * goes through the pack instead, as a frame of its own before the level's ({@link MinimapIris}).
 */
public final class MinimapVoxy {
	/** Set during the minimap pass: Voxy then picks the minimap viewport, projection and targets. */
	public static boolean inPass;
	static final boolean IRIS = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");
	static final FogParameters NO_FOG = new FogParameters(0, 0, 0, 0, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f);
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
		if (inPass) return;
		try {
			AbstractRenderPipeline pipeline = pipeline(rs);
			// shader packs (Iris) bring their own pipeline: see throughPack
			if (!(pipeline instanceof NormalRenderPipeline) || !(pipeline instanceof MinimapTargets targets) || !Minimap.prepare(partialTick)) return;
			reingestChanged();
			RenderProperties props = pipeline.properties;
			int colour = ((com.mojang.renderpearl.backend.opengl.GlTexture) Minimap.colour).glId();
			int depth = ((com.mojang.renderpearl.backend.opengl.GlTexture) Minimap.depth).glId();
			// dark behind the terrain: where the layer cut opens solid ground (walls, rock) nothing is drawn;
			// empty depth everywhere, so Voxy draws the LOD on every pixel
			GL44.glClearTexImage(colour, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[]{0.07f, 0.07f, 0.08f, 1f});
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
			Minimap.markRendered(false);
		} catch (RuntimeException | LinkageError e) {
			Minimap.error = e.toString();
			Plainsight.LOG.error("Minimap rendering failed; minimap off", e);
		}
	}

	/** Before the level is drawn: the minimap through the shader pack, when Voxy renders with one. */
	public static void throughPack(float partialTick) {
		VoxyRenderSystem rs = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullable();
		if (!IRIS || rs == null) return;
		try {
			if (!MinimapIris.usesPack(pipeline(rs)) || !Minimap.prepare(partialTick)) return;
			reingestChanged();
			if (MinimapIris.render(rs)) Minimap.markRendered(true);
		} catch (RuntimeException | LinkageError e) {
			Minimap.error = e.toString();
			Plainsight.LOG.error("Minimap rendering through the shader pack failed; minimap off", e);
			release();
		}
	}

	/** Frees the minimap's own pipelines (shader pack), e.g. with the render system they were made for. */
	public static void release() {
		if (IRIS) MinimapIris.release();
	}

	private static AbstractRenderPipeline pipeline(VoxyRenderSystem rs) {
		return ((dev.plainsight.mixin.voxy.VoxyRenderSystemAccessor) rs).plainsight$pipeline();
	}
}
