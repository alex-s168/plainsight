package dev.infiniterra.impl.pass;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.infiniterra.api.CameraPass;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** The camera pass's state (see {@link CameraPass}): this frame's view and the target it renders into. */
public final class Passes {
	public static volatile CameraPass.Source source;
	private static final CameraPass.View view = new CameraPass.View();

	public static com.mojang.blaze3d.pipeline.RenderTarget target;
	public static GpuTexture colour, depth;
	public static GpuTextureView colourView;
	public static int width, height;
	/** This frame's camera: position, rotation (model view) and projection; set by {@link #prepare}. */
	public static final Vector3d cameraPos = new Vector3d();
	public static final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
	/** This frame's pass uses the game's projection of the frame (see {@link CameraPass.View#frameProjection}). */
	public static boolean frameProjection;
	/** World height above which the pass leaves terrain out (none: MAX_VALUE). */
	public static float cutY = Float.MAX_VALUE;
	private static float fov, near, far;
	public static volatile long rendered, lastRendered;
	public static volatile boolean throughPack;
	private static boolean ready;
	public static volatile String error = "";

	/**
	 * Loaded chunks where blocks changed. Voxy reads a chunk when it is loaded and unloaded, not while it
	 * changes (the game draws loaded chunks itself), but passes show them from Voxy: {@link PassVoxy}
	 * gives them to Voxy again.
	 */
	static final it.unimi.dsi.fastutil.longs.LongOpenHashSet changed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	private Passes() {
	}

	public static boolean supported() {
		return dev.infiniterra.impl.guess.VoxyGuess.PRESENT;
	}

	/** Whether the host wants a pass this frame. */
	public static boolean wanted() {
		CameraPass.Source s = source;
		return s != null && supported() && error.isEmpty() && s.wanted();
	}

	/** A block changed in a loaded chunk (client level, render thread). */
	public static void blockChanged(int cx, int cz) {
		if (wanted()) changed.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
	}

	/** Sets up this frame's camera and render target; false if nothing should be rendered. Render thread. */
	static boolean prepare(float partialTick) {
		CameraPass.Source s = source;
		if (s == null || !s.wanted()) return false;
		view.reset();
		if (!s.prepare(partialTick, view) || view.width <= 0 || view.height <= 0) return false;
		if (colour == null || view.width != width || view.height != height) allocate(view.width, view.height);
		cameraPos.set(view.position);
		modelView.set(view.viewRotation);
		frameProjection = view.frameProjection;
		projection.set(view.projection);
		fov = view.fovDegrees;
		near = view.near;
		far = view.far;
		cutY = view.maxY;
		return true;
	}

	/** A pass rendered this frame (through the shader pack or not). */
	static void markRendered(boolean pack) {
		ready = true;
		rendered++;
		lastRendered = System.nanoTime();
		throughPack = pack;
	}

	public static boolean takeRendered() {
		boolean r = ready;
		ready = false;
		return r;
	}

	/** The pass's projection: the game's (Voxy makes its own from it), or the view's perspective in Voxy's depth convention. */
	static Matrix4f passProjection(boolean reverseZ, boolean zeroToOne) {
		if (frameProjection) return new Matrix4f(projection);
		float f = (float) Math.toRadians(fov), aspect = width / (float) Math.max(1, height);
		return reverseZ ? new Matrix4f().setPerspective(f, aspect, far, near, zeroToOne) : new Matrix4f().setPerspective(f, aspect, near, far, zeroToOne);
	}

	private static void allocate(int w, int h) {
		release();
		target = new com.mojang.blaze3d.pipeline.TextureTarget("infiniterra camera pass", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		colour = target.getColorTexture();
		depth = target.getDepthTexture();
		colourView = target.getColorTextureView();
		width = w;
		height = h;
	}

	static void release() {
		if (target != null) target.destroyBuffers();
		target = null;
		colourView = null;
		colour = depth = null;
		width = height = 0;
	}
}
