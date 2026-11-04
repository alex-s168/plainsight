package dev.infiniterra.impl.pass;

import dev.infiniterra.InfiniTerraMod;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL44;


/**
 * Renders the camera pass as an extra Voxy pass, right after Voxy's own frame (mixin on its render system):
 * the same LOD world through another of Voxy's viewports (it keeps one per view, as for Vivecraft's eyes),
 * from the pass camera, into the camera pass's textures. With a shader pack (Voxy's Iris pipeline) the pass
 * goes through the pack instead, as a frame of its own before the level's ({@link PassIris}).
 */
public final class PassVoxy {
	/** Set during the camera pass: Voxy then picks the camera pass viewport, projection and targets. */
	public static boolean inPass;
	static final boolean IRIS = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");
	static final FogParameters NO_FOG = new FogParameters(0, 0, 0, 0, 1e9f, 1e9f, 1e9f, 1e9f, 1e9f);
	private static long lastReingest;

	private PassVoxy() {
	}

	/** Gives the loaded chunks where blocks changed to Voxy again (see {@link Passes#blockChanged}), at most once a second. */
	private static void reingestChanged() {
		var changed = Passes.changed;
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
			if (!(pipeline instanceof NormalRenderPipeline) || !(pipeline instanceof PassTargets targets) || !Passes.prepare(partialTick)) return;
			reingestChanged();
			RenderProperties props = pipeline.properties;
			int colour = ((com.mojang.renderpearl.backend.opengl.GlTexture) Passes.colour).glId();
			int depth = ((com.mojang.renderpearl.backend.opengl.GlTexture) Passes.depth).glId();
			// dark behind the terrain: where the layer cut opens solid ground (walls, rock) nothing is drawn;
			// empty depth everywhere, so Voxy draws the LOD on every pixel
			GL44.glClearTexImage(colour, 0, GL11.GL_RGBA, GL11.GL_FLOAT, new float[]{0.07f, 0.07f, 0.08f, 1f});
			GL44.glClearTexImage(depth, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[]{props.clearDepth()});
			var proj = Passes.passProjection(props.isReverseZ(), props.isZero2One());
			inPass = true;
			targets.infiniterra$swapPassTargets();
			try {
				var p = Passes.cameraPos;
				var viewport = rs.setupViewport(proj, Passes.modelView, NO_FOG, Passes.width, Passes.height, p.x, p.y, p.z);
				rs.renderOpaque(viewport, depth, colour);
			} finally {
				targets.infiniterra$swapPassTargets();
				inPass = false;
			}
			Passes.markRendered(false);
		} catch (RuntimeException | LinkageError e) {
			Passes.error = e.toString();
			InfiniTerraMod.LOG.error("Camera pass failed; camera passes off", e);
		}
	}

	/** Before the level is drawn: the camera pass through the shader pack, when Voxy renders with one. */
	public static void throughPack(float partialTick) {
		VoxyRenderSystem rs = me.cortex.voxy.client.core.IVoxyRenderSystemHolder.getNullable();
		if (!IRIS || rs == null) return;
		try {
			if (!PassIris.usesPack(pipeline(rs)) || !Passes.prepare(partialTick)) return;
			reingestChanged();
			if (PassIris.render(rs)) Passes.markRendered(true);
		} catch (RuntimeException | LinkageError e) {
			Passes.error = e.toString();
			InfiniTerraMod.LOG.error("Camera pass through the shader pack failed; camera passes off", e);
			release();
		}
	}

	/** Frees the camera pass's own pipelines (shader pack), e.g. with the render system they were made for. */
	public static void release() {
		if (IRIS) PassIris.release();
	}

	private static AbstractRenderPipeline pipeline(VoxyRenderSystem rs) {
		return ((dev.infiniterra.mixin.voxy.VoxyRenderSystemAccessor) rs).infiniterra$pipeline();
	}
}
