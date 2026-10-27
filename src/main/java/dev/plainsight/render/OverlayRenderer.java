package dev.plainsight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.plainsight.Plainsight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;
import java.util.OptionalDouble;

/** Draws the frame's {@link Overlay} after the level, into the main target with the level's depth. */
public final class OverlayRenderer {
	private static final DepthStencilState TESTED = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);
	private static final DepthStencilState BEHIND = new DepthStencilState(CompareOp.LESS_THAN, false);
	private static final DepthStencilState ALWAYS = new DepthStencilState(CompareOp.ALWAYS_PASS, false);

	private static final RenderPipeline[] LINES = {
			lines("world", TESTED), lines("xray", BEHIND), lines("on_top", ALWAYS)};
	private static final RenderPipeline[] TRIS = {
			tris("world", TESTED), tris("xray", BEHIND), tris("on_top", ALWAYS)};

	private static RenderPipeline lines(String name, DepthStencilState depth) {
		return RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/overlay_lines_" + name))
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(depth)
				.withCull(false)
				.build();
	}

	private static RenderPipeline tris(String name, DepthStencilState depth) {
		return RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/overlay_tris_" + name))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(depth)
				.withCull(false)
				.build();
	}

	private static final Overlay.Layer[] ORDER = {Overlay.Layer.XRAY, Overlay.Layer.WORLD, Overlay.Layer.ON_TOP};

	private final ByteBufferBuilder lineBytes = new ByteBufferBuilder(1 << 16);
	private final ByteBufferBuilder triBytes = new ByteBufferBuilder(1 << 16);

	public void render(Overlay overlay, CameraRenderState camera) {
		if (overlay.isEmpty()) return;
		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		Vec3 cam = camera.pos;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(camera.viewRotationMatrix));
		try (RenderPass pass = encoder.createRenderPass(() -> "plainsight overlay", target.getColorTextureView(), Optional.empty(),
				target.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transform);
			for (Overlay.Layer layer : ORDER) {
				Overlay.Batch batch = overlay.batches[layer.ordinal()];
				drawTris(pass, encoder, batch, layer, cam);
				drawLines(pass, encoder, batch, layer, cam);
			}
		}
	}

	private void drawTris(RenderPass pass, CommandEncoder encoder, Overlay.Batch batch, Overlay.Layer layer, Vec3 cam) {
		if (batch.triCount == 0) return;
		BufferBuilder b = new BufferBuilder(triBytes, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		double[] t = batch.tris;
		for (int i = 0; i < batch.triCount; i++) {
			int o = i * 10, color = (int) t[o + 9];
			for (int v = 0; v < 3; v++) {
				b.addVertex((float) (t[o + v * 3] - cam.x), (float) (t[o + v * 3 + 1] - cam.y), (float) (t[o + v * 3 + 2] - cam.z)).setColor(color);
			}
		}
		try (MeshData mesh = b.build()) {
			if (mesh == null) return;
			GpuBufferSlice vertices = encoder.transientMemory().uploadGpu(mesh.vertexBuffer(), DefaultVertexFormat.POSITION_COLOR.getVertexSize(), GpuBuffer.USAGE_VERTEX);
			pass.setPipeline(RenderSystem.getCompiledPipeline(TRIS[layer.ordinal()]));
			pass.setVertexBuffer(0, vertices);
			pass.draw(mesh.drawState().vertexCount(), 1, 0, 0);
		}
	}

	private void drawLines(RenderPass pass, CommandEncoder encoder, Overlay.Batch batch, Overlay.Layer layer, Vec3 cam) {
		if (batch.lineCount == 0) return;
		BufferBuilder b = new BufferBuilder(lineBytes, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
		double[] l = batch.lines;
		for (int i = 0; i < batch.lineCount; i++) {
			int o = i * 8, color = (int) l[o + 6];
			float width = (float) l[o + 7];
			float ax = (float) (l[o] - cam.x), ay = (float) (l[o + 1] - cam.y), az = (float) (l[o + 2] - cam.z);
			float bx = (float) (l[o + 3] - cam.x), by = (float) (l[o + 4] - cam.y), bz = (float) (l[o + 5] - cam.z);
			float nx = bx - ax, ny = by - ay, nz = bz - az;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-6f) continue;
			nx /= len;
			ny /= len;
			nz /= len;
			b.addVertex(ax, ay, az).setColor(color).setNormal(nx, ny, nz).setLineWidth(width);
			b.addVertex(bx, by, bz).setColor(color).setNormal(nx, ny, nz).setLineWidth(width);
		}
		try (MeshData mesh = b.build()) {
			if (mesh == null) return;
			GpuBufferSlice vertices = encoder.transientMemory().uploadGpu(mesh.vertexBuffer(),
					DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH.getVertexSize(), GpuBuffer.USAGE_VERTEX);
			RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.LINES);
			int indexCount = mesh.drawState().indexCount();
			pass.setPipeline(RenderSystem.getCompiledPipeline(LINES[layer.ordinal()]));
			pass.setVertexBuffer(0, vertices);
			pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
			pass.drawIndexed(indexCount, 1, 0, 0, 0);
		}
	}
}
