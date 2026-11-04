package dev.infiniterra.api;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.infiniterra.impl.pass.Passes;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * An extra view of the LOD terrain from another camera, rendered every frame it is wanted into its own
 * colour and depth target (e.g. a camera pass, or an editor's map): with the LOD mod (Voxy), and through the
 * shader pack when one is in use (Iris). No fog, no clouds; terrain above {@link View#maxY} can be left out.
 * <p>
 * One host provides the camera ({@link #setSource}); InfiniTerra calls it from inside the LOD renderer's
 * frame, before the HUD is drawn, and the host shows the result ({@link #colourView()}, {@link #depth()}).
 */
public final class CameraPass {
	private CameraPass() {
	}

	/** Provides the pass's camera. */
	public interface Source {
		/** Whether a pass is wanted this frame (cheap; e.g. whether the camera pass is shown). */
		boolean wanted();

		/** Fills in this frame's view; false: no pass this frame. Render thread, inside the LOD renderer's frame. */
		boolean prepare(float partialTick, View view);
	}

	/** The camera of one pass (reset before each {@link Source#prepare}). */
	public static final class View {
		/** Camera position (world) and rotation (the view matrix without translation). */
		public final Vector3d position = new Vector3d();
		public final Matrix4f viewRotation = new Matrix4f();
		/**
		 * Use {@link #projection} as it is (the game's own projection of this frame, e.g. to line up with
		 * the main view); otherwise a perspective from {@link #fovDegrees}, {@link #near} and {@link #far}
		 * in the LOD renderer's depth convention.
		 */
		public boolean frameProjection;
		public final Matrix4f projection = new Matrix4f();
		public float fovDegrees = 70, near = 1, far = 48000;
		/** Target size in framebuffer pixels. */
		public int width, height;
		/** Terrain above this height is left out (Float.MAX_VALUE: nothing). */
		public float maxY = Float.MAX_VALUE;

		public void reset() {
			position.zero();
			viewRotation.identity();
			frameProjection = false;
			projection.identity();
			fovDegrees = 70;
			near = 1;
			far = 48000;
			width = height = 0;
			maxY = Float.MAX_VALUE;
		}
	}

	/** Whether passes can be rendered at all (an LOD mod that supports them is loaded). */
	public static boolean supported() {
		return Passes.supported();
	}

	/** Sets (or with null removes) the host that provides the camera. */
	public static void setSource(@Nullable Source source) {
		Passes.source = source;
	}

	/** The last pass's target, colour and depth (null before the first pass); they change when the size does. */
	public static @Nullable RenderTarget target() {
		return Passes.target;
	}

	public static @Nullable GpuTexture colour() {
		return Passes.colour;
	}

	public static @Nullable GpuTextureView colourView() {
		return Passes.colourView;
	}

	public static @Nullable GpuTexture depth() {
		return Passes.depth;
	}

	/** Whether a pass rendered since the last call (once per pass). */
	public static boolean takeRendered() {
		return Passes.takeRendered();
	}

	/** Passes rendered so far, and when the last one was (System.nanoTime). */
	public static long rendered() {
		return Passes.rendered;
	}

	public static long lastRendered() {
		return Passes.lastRendered;
	}

	/** Whether the last pass went through the shader pack (then its depth is not the LOD's). */
	public static boolean throughPack() {
		return Passes.throughPack;
	}

	/** Why passes stopped (empty while they work). */
	public static String error() {
		return Passes.error;
	}
}
