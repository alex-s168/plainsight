package dev.plainsight.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.plainsight.Plainsight;
import dev.plainsight.guess.GuessWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Screen-space marker for guessed terrain: a full-screen pass that rebuilds each pixel's world position
 * from the depth buffer and tints it when it lies in a guessed chunk (see guess_tint.fsh). Works for
 * whatever drew the terrain (Voxy's LOD included), after the level is rendered.
 */
public final class GuessTint {
	private static final int SIZE = 256;
	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("MaskSampler", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("GuessInfo", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(Plainsight.ID, "pipeline/guess_tint"))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(Plainsight.ID, "core/guess_tint"))
			.withBindGroupLayout(LAYOUT)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build();
	private static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec4().get();

	private static GpuTexture mask;
	private static GpuTextureView maskView;
	private static MappableRingBuffer ubo;
	private static final byte[] cells = new byte[SIZE * SIZE];
	private static int maskVersion = -1, maskOriginX = Integer.MIN_VALUE, maskOriginZ;
	private static long maskBuiltAt;
	public static int drawn;

	private GuessTint() {
	}

	public static void render(CameraRenderState camera, GuessWorld world) {
		var device = RenderSystem.getDevice();
		if (mask == null) {
			mask = device.createTexture("plainsight guess mask", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
					GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
			maskView = device.createTextureView(mask);
			ubo = new MappableRingBuffer(() -> "plainsight guess tint", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, UBO_SIZE);
		}
		var cam = camera.pos;
		int ox = ((int) Math.floor(cam.x) >> 4) - SIZE / 2, oz = ((int) Math.floor(cam.z) >> 4) - SIZE / 2;
		long now = System.currentTimeMillis();
		// rebuild when the area moved a lot or the guesses changed (at most 4 times a second)
		boolean moved = Math.abs(ox - maskOriginX) > SIZE / 8 || Math.abs(oz - maskOriginZ) > SIZE / 8;
		if (moved || (world.version() != maskVersion && now - maskBuiltAt > 250)) {
			if (moved) {
				maskOriginX = ox;
				maskOriginZ = oz;
			}
			maskVersion = world.version();
			maskBuiltAt = now;
			world.fillMask(maskOriginX, maskOriginZ, SIZE, cells);
			ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
			for (byte b : cells) pixels.put(b).put((byte) 0).put((byte) 0).put((byte) 255);
			pixels.flip();
			device.createCommandEncoder().writeToTexture(mask, pixels, 0, 0, 0, 0, SIZE, SIZE);
		}
		Matrix4f inv = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
		try (GpuBufferSlice.MappedView view = ubo.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(view.data())
					.putMat4f(inv)
					.putVec4(new Vector4f((float) (cam.x - maskOriginX * 16.0), (float) cam.y, (float) (cam.z - maskOriginZ * 16.0), 0))
					.putVec4(new Vector4f(SIZE, dev.plainsight.module.GuessedTerrain.tintOpacity, zeroToOne() ? 1 : 0, (now % 100000) / 1000f));
		}
		Minecraft mc = Minecraft.getInstance();
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "plainsight guess tint", target.getColorTextureView(), Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("InSampler", target.getDepthTextureView(), nearest);
			pass.setUniform("MaskSampler", maskView, nearest);
			pass.setUniform("GuessInfo", ubo.currentBuffer());
			pass.draw(3, 1, 0, 0);
		}
		ubo.rotate();
		drawn++;
	}

	/** Whether clip-space depth runs 0..1 (otherwise -1..1); overridable for testing. */
	private static boolean zeroToOne() {
		String p = System.getProperty("plainsight.guessDepth");
		return p == null || !p.equals("gl");
	}
}
