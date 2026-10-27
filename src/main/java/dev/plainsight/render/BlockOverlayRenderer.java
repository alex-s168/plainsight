package dev.plainsight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.plainsight.Plainsight;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws {@link BlockOverlay}s: each overlay is tesselated once into a cached mesh (real block models
 * with ambient occlusion and the world's sky/block light at each position) and drawn after the level
 * in two passes. A depth-only pass first keeps just the front-most surface; the colour pass then blends
 * only that surface over the world, so a translucent overlay never shows its own hidden faces.
 */
public final class BlockOverlayRenderer {
	private static final RenderPipeline DEPTH = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/block_overlay_depth"))
			.withShaderDefine("ALPHA_CUTOUT", 0.5F)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
			.build();
	// the opacity is applied (as colour modulator) before the shader's cutout test: keep that test low
	private static final RenderPipeline COLOR = RenderPipeline.builder(RenderPipelines.BLOCK_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/block_overlay_color"))
			.withShaderDefine("ALPHA_CUTOUT", 0.01F)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.build();

	/** Block state view for tesselation: neighbours come from the overlay, light from the world. */
	static final class OverlayBlock extends MovingBlockRenderState {
		final BlockOverlay overlay;
		private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();

		OverlayBlock(BlockOverlay overlay) {
			this.overlay = overlay;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			local.set(pos.getX() - overlay.offset.getX(), pos.getY() - overlay.offset.getY(), pos.getZ() - overlay.offset.getZ());
			return overlay.get(local);
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return getBlockState(pos).getFluidState();
		}
	}

	private static final class Mesh {
		GpuBuffer vertices;
		int indexCount;
		int version = -1;
		long offset = Long.MIN_VALUE;
		long builtAt;
		int blocks;

		void close() {
			if (vertices != null) vertices.close();
			vertices = null;
		}
	}

	/** Rebuild at least this often, so light changes around the overlay (torches, new blocks) show up. */
	private static final long REBUILD_MS = 1000;
	private static final Map<BlockOverlay, Mesh> MESHES = new IdentityHashMap<>();
	private static final ByteBufferBuilder BYTES = new ByteBufferBuilder(1 << 16);

	/** Number of overlay blocks drawn in the last frame (tests, debugging). */
	public static int submitted;

	private BlockOverlayRenderer() {
	}

	public static void render(CameraRenderState camera) {
		submitted = 0;
		ClientLevel level = Minecraft.getInstance().level;
		MESHES.entrySet().removeIf(e -> {
			boolean gone = !BlockOverlay.visible().contains(e.getKey()) || level == null;
			if (gone) e.getValue().close();
			return gone;
		});
		if (level == null || BlockOverlay.visible().isEmpty()) return;
		for (BlockOverlay overlay : BlockOverlay.visible()) {
			if (overlay.size() == 0) continue;
			Mesh mesh = MESHES.computeIfAbsent(overlay, o -> new Mesh());
			long now = System.currentTimeMillis();
			if (mesh.version != overlay.version() || mesh.offset != overlay.offset.asLong() || now - mesh.builtAt > REBUILD_MS) {
				build(overlay, mesh, level);
				mesh.builtAt = now;
			}
			if (mesh.vertices == null) continue;
			draw(overlay, mesh, camera);
			submitted += mesh.blocks;
		}
	}

	private static void build(BlockOverlay overlay, Mesh mesh, ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		var models = mc.getModelManager().getBlockStateModelSet();
		ModelBlockRenderer renderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
		BufferBuilder builder = new BufferBuilder(BYTES, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
		BlockQuadOutput output = builder::putBlockBakedQuad;
		OverlayBlock view = new OverlayBlock(overlay);
		view.cardinalLighting = level.cardinalLighting();
		view.lightEngine = level.getLightEngine();
		int blocks = 0;
		for (Long2ObjectMap.Entry<BlockState> e : overlay.blocks().long2ObjectEntrySet()) {
			BlockState state = e.getValue();
			if (state.getRenderShape() != RenderShape.MODEL) continue;
			BlockPos local = BlockPos.of(e.getLongKey());
			BlockPos world = local.offset(overlay.offset);
			view.blockPos = world;
			view.randomSeedPos = world;
			view.blockState = state;
			view.biome = level.getBiome(world);
			renderer.tesselateBlock(output, local.getX(), local.getY(), local.getZ(), view, world, state, models.get(state), state.getSeed(world));
			blocks++;
		}
		mesh.close();
		mesh.version = overlay.version();
		mesh.offset = overlay.offset.asLong();
		mesh.blocks = blocks;
		try (MeshData data = builder.build()) {
			if (data == null) {
				mesh.indexCount = 0;
				return;
			}
			mesh.vertices = RenderSystem.getDevice().createBuffer(() -> "plainsight block overlay", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
			mesh.indexCount = data.drawState().indexCount();
		}
	}

	private static void draw(BlockOverlay overlay, Mesh mesh, CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		Vec3 cam = camera.pos;
		Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix)
				.translate((float) (overlay.offset.getX() - cam.x), (float) (overlay.offset.getY() - cam.y), (float) (overlay.offset.getZ() - cam.z));
		float alpha = Math.max(0.05f, Math.min(1f, overlay.opacity));
		var solid = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
		var faded = RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(1, 1, 1, alpha), new Vector3f(), new Matrix4f());
		AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		var indexBuffer = indices.getBuffer(mesh.indexCount);
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "plainsight block overlay",
				target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Sampler0", atlas.getTextureView(), atlas.getSampler());
			pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setVertexBuffer(0, mesh.vertices.slice());
			pass.setIndexBuffer(indexBuffer, indices.type());
			pass.setPipeline(RenderSystem.getCompiledPipeline(DEPTH));
			pass.setUniform("DynamicTransforms", solid);
			pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0);
			pass.setPipeline(RenderSystem.getCompiledPipeline(COLOR));
			pass.setUniform("DynamicTransforms", faded);
			pass.drawIndexed(mesh.indexCount, 1, 0, 0, 0);
		}
	}
}
