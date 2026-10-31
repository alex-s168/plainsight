package dev.plainsight.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.plainsight.editor.Editor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * Picks the surface under the cursor from the depth buffer, so the editor can aim at anything drawn:
 * Voxy's LOD and guessed terrain too, which block raycasts (loaded chunks only) never hit. Every frame
 * the depth pixel under the mouse is copied to a small buffer; its value arrives a frame or two later,
 * together with the camera of the frame it came from.
 */
public final class DepthPick {
	/** A depth sample: window position (editor coordinates), world point (null on sky), when. */
	private record Sample(double x, double y, Vector3d point, long time) {
	}

	private static final int BUFFERS = 3;
	private static final GpuBuffer[] buffers = new GpuBuffer[BUFFERS];
	private static final boolean[] busy = new boolean[BUFFERS];
	private static int next;
	private static volatile Sample latest;

	private DepthPick() {
	}

	/** This frame's sample was taken already (before always-on-top gizmos clear the depth). */
	private static boolean sampledThisFrame;

	/**
	 * Copies the depth under the mouse, once per frame: right before the level's always-on-top gizmos (e.g.
	 * the chunk borders, F3+G) when there are any, as that pass clears the depth, else after the level.
	 */
	public static void sampleOnce(CameraRenderState camera) {
		if (sampledThisFrame) return;
		sampledThisFrame = true;
		sample(camera);
	}

	/** After the level: the frame's sample, unless it was taken before always-on-top gizmos. */
	public static void afterLevel(CameraRenderState camera) {
		sampleOnce(camera);
		sampledThisFrame = false;
	}

	/** Copies the depth under the mouse (call after the level is drawn, before editor overlays). */
	private static void sample(CameraRenderState camera) {
		Editor editor = Editor.get();
		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTexture depth = target.getDepthTexture();
		if (depth == null || depth.getFormat().blockSize() != 4) return;
		var vp = editor.viewport;
		double mx = editor.mouseX, my = editor.mouseY;
		int fx = (int) Math.floor(mx * target.width / vp.windowWidth), fy = (int) Math.floor(my * target.height / vp.windowHeight);
		if (fx < 0 || fy < 0 || fx >= target.width || fy >= target.height) return;
		int slot = next;
		if (busy[slot]) return;
		next = (next + 1) % BUFFERS;
		if (buffers[slot] == null)
			buffers[slot] = RenderSystem.getDevice().createBuffer(() -> "plainsight depth pick", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 4);
		busy[slot] = true;
		Matrix4f inv = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
		Vector3d cam = new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z);
		int w = target.width, h = target.height;
		GpuBuffer buffer = buffers[slot];
		// texture rows run bottom-up (as in Screenshot)
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(depth, buffer, 0L, () -> {
			float d;
			try (var read = buffer.map(true, false)) {
				d = read.data().getFloat(0);
			}
			busy[slot] = false;
			Vector3d point = null;
			if (d > 0f && d < 1f) { // reverse-Z: 0 is the cleared far plane (sky)
				Vector4f p = inv.transform(new Vector4f((fx + 0.5f) / w * 2f - 1f, 1f - (fy + 0.5f) / h * 2f, d, 1f));
				point = new Vector3d(p.x / p.w, p.y / p.w, p.z / p.w).add(cam);
			}
			latest = new Sample(mx, my, point, System.currentTimeMillis());
		}, 0, fx, h - 1 - fy, 1, 1);
	}

	/** The drawn surface at window position (x, y), if a recent sample is there; null on sky or when unknown. */
	public static Vector3d at(double x, double y) {
		Sample s = latest;
		if (s == null || s.point == null || System.currentTimeMillis() - s.time > 500) return null;
		if (Math.abs(s.x - x) > 3 || Math.abs(s.y - y) > 3) return null;
		return new Vector3d(s.point);
	}
}
