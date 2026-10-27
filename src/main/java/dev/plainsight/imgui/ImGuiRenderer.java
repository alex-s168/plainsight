package dev.plainsight.imgui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.plainsight.Plainsight;
import imgui.ImDrawData;
import imgui.ImFontAtlas;
import imgui.ImVec4;
import imgui.type.ImInt;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws Dear ImGui's draw data through Minecraft's GPU abstraction, so it works on both of the game's
 * backends (OpenGL and Vulkan). ImGui vertices (pos2, uv2, rgba8) are widened to the vanilla
 * POSITION_TEX_COLOR format and drawn with the vanilla position_tex_color shaders.
 */
public final class ImGuiRenderer implements AutoCloseable {
	public static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/imgui"))
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
			.withVertexShader("core/position_tex_color")
			.withFragmentShader("core/position_tex_color")
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.build();

	private static final int OUT_VERTEX_SIZE = 24;
	public static final long FONT_TEXTURE_ID = 1;
	/** Statistics of the last frame, for tests and debugging. */
	public static volatile String lastFrameInfo = "";

	private record Tex(GpuTextureView view, GpuSampler sampler) {}

	private final Long2ObjectOpenHashMap<Tex> textures = new Long2ObjectOpenHashMap<>();
	private long nextTextureId = 16;
	private final Projection projection = new Projection();
	private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("plainsight imgui");
	private GpuTexture fontTexture;
	private ByteBuffer vertexScratch = MemoryUtil.memAlloc(1 << 20);
	private ByteBuffer indexScratch = MemoryUtil.memAlloc(1 << 18);
	private final ImVec4 clip = new ImVec4();

	public void createFontTexture(ImFontAtlas atlas) {
		ImInt w = new ImInt(), h = new ImInt();
		ByteBuffer pixels = atlas.getTexDataAsRGBA32(w, h);
		var device = RenderSystem.getDevice();
		if (fontTexture != null) fontTexture.close();
		fontTexture = device.createTexture("plainsight imgui font", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
				GpuFormat.RGBA8_UNORM, w.get(), h.get(), 1, 1);
		device.createCommandEncoder().writeToTexture(fontTexture, pixels, 0, 0, 0, 0, w.get(), h.get());
		GpuTextureView view = device.createTextureView(fontTexture);
		textures.put(FONT_TEXTURE_ID, new Tex(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
		atlas.setTexID(FONT_TEXTURE_ID);
	}

	/** Makes a game texture usable as an ImGui texture id (for ImGui.image and draw lists). */
	public long registerTexture(GpuTextureView view, boolean linear) {
		long id = nextTextureId++;
		textures.put(id, new Tex(view, RenderSystem.getSamplerCache().getClampToEdge(linear ? FilterMode.LINEAR : FilterMode.NEAREST)));
		return id;
	}

	public void render(ImDrawData data, RenderTarget target) {
		int lists = data.getCmdListsCount();
		lastFrameInfo = "lists=" + lists + " vtx=" + data.getTotalVtxCount() + " display=" + data.getDisplaySizeX() + "x" + data.getDisplaySizeY();
		if (lists <= 0 || data.getTotalVtxCount() == 0) return;
		int draws = 0;
		int fbW = target.width, fbH = target.height;
		float scaleX = data.getFramebufferScaleX(), scaleY = data.getFramebufferScaleY();
		float dispX = data.getDisplayPosX(), dispY = data.getDisplayPosY();

		int inVertexSize = ImDrawData.sizeOfImDrawVert();
		int idxSize = ImDrawData.sizeOfImDrawIdx();
		long vtxBytes = 0, idxBytes = 0;
		for (int n = 0; n < lists; n++) {
			vtxBytes += data.getCmdListVtxBufferData(n).remaining() / inVertexSize * (long) OUT_VERTEX_SIZE;
			idxBytes += data.getCmdListIdxBufferData(n).remaining();
		}
		vertexScratch = ensure(vertexScratch, vtxBytes);
		indexScratch = ensure(indexScratch, idxBytes);
		vertexScratch.clear().order(ByteOrder.nativeOrder());
		indexScratch.clear();

		// widen vertices: (x, y, u, v, rgba) -> (x, y, 0, u, v, rgba); the byte order of rgba matches
		int[] listVtxStart = new int[lists], listIdxStart = new int[lists];
		int vtxBase = 0, idxBase = 0;
		for (int n = 0; n < lists; n++) {
			// imgui-java returns vertex and index data in one shared buffer: copy each before asking for the next
			ByteBuffer vb = data.getCmdListVtxBufferData(n).order(ByteOrder.nativeOrder());
			int vc = vb.remaining() / inVertexSize;
			listVtxStart[n] = vtxBase;
			listIdxStart[n] = idxBase;
			for (int i = 0; i < vc; i++) {
				int o = i * inVertexSize;
				vertexScratch.putFloat(vb.getFloat(o));
				vertexScratch.putFloat(vb.getFloat(o + 4));
				vertexScratch.putFloat(0f);
				vertexScratch.putFloat(vb.getFloat(o + 8));
				vertexScratch.putFloat(vb.getFloat(o + 12));
				vertexScratch.putInt(vb.getInt(o + 16));
			}
			ByteBuffer ib = data.getCmdListIdxBufferData(n);
			int ic = ib.remaining() / idxSize;
			indexScratch.put(ib);
			vtxBase += vc;
			idxBase += ic;
		}
		vertexScratch.flip();
		indexScratch.flip();

		var device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();
		GpuBufferSlice vertices = encoder.transientMemory().uploadGpu(vertexScratch, OUT_VERTEX_SIZE, GpuBuffer.USAGE_VERTEX);
		GpuBufferSlice indices = encoder.transientMemory().uploadGpu(indexScratch, 4, GpuBuffer.USAGE_INDEX);
		IndexType indexType = idxSize == 2 ? IndexType.SHORT : IndexType.INT;
		int indexOffset = (int) (indices.offset() / idxSize);

		projection.setupOrtho(-1f, 1f, data.getDisplaySizeX(), data.getDisplaySizeY(), true);
		GpuBufferSlice proj = projectionBuffer.getBuffer(projection);
		Matrix4f modelView = new Matrix4f().translate(-dispX, -dispY, 0f);
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(modelView);

		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(proj, ProjectionType.ORTHOGRAPHIC);
		try (RenderPass pass = encoder.createRenderPass(() -> "plainsight imgui", target.getColorTextureView(), Optional.empty(), null, OptionalDouble.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("Projection", proj);
			pass.setUniform("DynamicTransforms", transform);
			pass.setVertexBuffer(0, vertices);
			pass.setIndexBuffer(indices.buffer(), indexType);
			long boundTex = -1;
			for (int n = 0; n < lists; n++) {
				int cmds = data.getCmdListCmdBufferSize(n);
				for (int c = 0; c < cmds; c++) {
					int elemCount = data.getCmdListCmdBufferElemCount(n, c);
					if (elemCount == 0) continue;
					data.getCmdListCmdBufferClipRect(clip, n, c);
					float x1 = (clip.x - dispX) * scaleX, y1 = (clip.y - dispY) * scaleY;
					float x2 = (clip.z - dispX) * scaleX, y2 = (clip.w - dispY) * scaleY;
					int sx = Math.max(0, (int) x1), sy = Math.max(0, (int) y1);
					int ex = Math.min(fbW, (int) Math.ceil(x2)), ey = Math.min(fbH, (int) Math.ceil(y2));
					if (ex <= sx || ey <= sy) continue;
					long texId = data.getCmdListCmdBufferTextureId(n, c);
					if (texId != boundTex) {
						Tex tex = textures.get(texId);
						if (tex == null) tex = textures.get(FONT_TEXTURE_ID);
						pass.setUniform("Sampler0", tex.view(), tex.sampler());
						boundTex = texId;
					}
					// scissor uses a bottom-left origin
					pass.enableScissor(sx, fbH - ey, ex - sx, ey - sy);
					draws++;
					pass.drawIndexed(elemCount, 1, indexOffset + listIdxStart[n] + data.getCmdListCmdBufferIdxOffset(n, c),
							listVtxStart[n] + data.getCmdListCmdBufferVtxOffset(n, c), 0);
				}
			}
			lastFrameInfo += " draws=" + draws + " target=" + fbW + "x" + fbH + " firstVertex=" + vertexScratch.getFloat(0) + "," + vertexScratch.getFloat(4);
		} finally {
			RenderSystem.restoreProjectionMatrix();
		}
	}

	private static ByteBuffer ensure(ByteBuffer buf, long bytes) {
		if (buf.capacity() >= bytes) return buf;
		long cap = Math.max(bytes, buf.capacity() * 2L);
		return MemoryUtil.memRealloc(buf, (int) cap);
	}

	@Override
	public void close() {
		if (fontTexture != null) fontTexture.close();
		projectionBuffer.close();
		MemoryUtil.memFree(vertexScratch);
		MemoryUtil.memFree(indexScratch);
	}
}
