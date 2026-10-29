package dev.plainsight.minimap;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.plainsight.editor.Editor;
import dev.plainsight.imgui.ImGuiLayer;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * A minimap that is Voxy's LOD world seen from above the player, tilted and turned with the player's view
 * ({@link MinimapVoxy} renders it as an extra Voxy pass into {@link #colour}); drawn with ImGui in the top
 * right corner of the HUD. Only with Voxy, and not in the editor (which has its own camera).
 */
public final class Minimap {
	/** Pitch of the minimap camera (degrees down from the horizon): a tilted top view. */
	public static final float PITCH = 60;
	/** Vertical field of view of the minimap camera (degrees); narrow, so it looks almost like a map. */
	public static final float FOV = 40;
	public static final double MIN_DISTANCE = 24, MAX_DISTANCE = 6000;

	public static boolean enabled = true;
	/** Camera distance from the player that zooming aims at, and the current (smoothed) one. */
	public static double targetDistance = 160, distance = 160;
	private static float yaw = Float.NaN;
	private static long lastFrame;

	/** Render target (colour and depth, GL textures behind them) and its size in framebuffer pixels. */
	static GpuTexture colour, depth;
	private static GpuTextureView colourView;
	private static long imguiTexture = -1;
	static int size;
	/** This frame's camera: position, rotation (model view) and projection; set by {@link #prepare}. */
	static final Vector3d cameraPos = new Vector3d();
	static final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
	/** Frames rendered (tests and debugging), and when the last one was. */
	public static volatile long rendered;
	public static volatile String error = "";

	/**
	 * Loaded chunks where blocks changed. Voxy reads a chunk when it is loaded and unloaded, not while it
	 * changes (the game draws loaded chunks itself), but the minimap shows them from Voxy: {@link MinimapVoxy}
	 * gives them to Voxy again.
	 */
	static final it.unimi.dsi.fastutil.longs.LongOpenHashSet changed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	private Minimap() {
	}

	/** A block changed in a loaded chunk (client level, render thread). */
	public static void blockChanged(int cx, int cz) {
		if (visible()) changed.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
	}

	public static void zoom(int steps) {
		targetDistance = Math.clamp(targetDistance * Math.pow(1.5, -steps), MIN_DISTANCE, MAX_DISTANCE);
	}

	/** Whether the minimap is shown (and rendered) this frame. */
	public static boolean visible() {
		Minecraft mc = Minecraft.getInstance();
		return enabled && dev.plainsight.guess.VoxyGuess.PRESENT && error.isEmpty() && mc.player != null && mc.level != null
				&& !mc.gui.hud.isHidden() && !Editor.isActive();
	}

	/** Side of the minimap on screen, in GUI pixels (ImGui display units). */
	static float screenSide() {
		var w = Minecraft.getInstance().getWindow();
		return Math.round(Math.min(w.getScreenWidth(), w.getScreenHeight()) * 0.3f);
	}

	/**
	 * Sets up this frame's camera and render target; false if nothing should be rendered. Render thread,
	 * from inside Voxy's frame (so before the HUD is drawn).
	 */
	static boolean prepare(float partialTick) {
		if (!visible()) return false;
		Minecraft mc = Minecraft.getInstance();
		var w = mc.getWindow();
		float scale = w.getWidth() / (float) Math.max(1, w.getScreenWidth());
		int px = Math.max(64, Math.round(screenSide() * scale));
		if (colour == null || px != size) allocate(px);

		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
		lastFrame = now;
		// zoom glides; the map turns with the player's view, slightly smoothed
		distance += (targetDistance - distance) * (1 - Math.exp(-dt * 12));
		float playerYaw = mc.player.getYRot(partialTick);
		if (Float.isNaN(yaw)) yaw = playerYaw;
		yaw += (float) (net.minecraft.util.Mth.wrapDegrees(playerYaw - yaw) * (1 - Math.exp(-dt * 10)));

		var at = mc.player.getPosition(partialTick);
		double pitch = Math.toRadians(PITCH), yawR = Math.toRadians(yaw);
		// view direction (Minecraft: yaw 0 looks towards +Z); the camera sits behind and above the player
		double dx = -Math.sin(yawR) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yawR) * Math.cos(pitch);
		cameraPos.set(at.x - dx * distance, at.y + 1 - dy * distance, at.z - dz * distance);
		modelView.rotation(new Quaternionf().rotationYXZ((float) Math.PI - (float) yawR, -(float) pitch, 0).conjugate());
		return true;
	}

	/** Projection in Voxy's depth convention (reverse-Z and/or 0..1 like the game's). */
	static Matrix4f projection(boolean reverseZ, boolean zeroToOne) {
		float near = (float) Math.max(1, distance * 0.05), far = 48000;
		float fov = (float) Math.toRadians(FOV);
		return reverseZ ? projection.setPerspective(fov, 1, far, near, zeroToOne) : projection.setPerspective(fov, 1, near, far, zeroToOne);
	}

	private static void allocate(int px) {
		release();
		var device = RenderSystem.getDevice();
		colour = device.createTexture("plainsight minimap", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, px, px, 1, 1);
		depth = device.createTexture("plainsight minimap depth", GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, px, px, 1, 1);
		colourView = device.createTextureView(colour);
		size = px;
	}

	private static void release() {
		if (imguiTexture >= 0 && ImGuiLayer.isInitialized()) ImGuiLayer.get().renderer().releaseTexture(imguiTexture);
		imguiTexture = -1;
		if (colourView != null) colourView.close();
		if (colour != null) colour.close();
		if (depth != null) depth.close();
		colourView = null;
		colour = depth = null;
		size = 0;
	}

	/** Draws the minimap into the current ImGui frame (HUD, top right). */
	public static void draw() {
		if (!visible() || colourView == null || rendered == 0) return;
		if (imguiTexture < 0) imguiTexture = ImGuiLayer.get().renderer().registerTexture(colourView, true);
		Minecraft mc = Minecraft.getInstance();
		float side = screenSide(), margin = 8;
		float x0 = mc.getWindow().getScreenWidth() - side - margin, y0 = margin, x1 = x0 + side, y1 = y0 + side;
		ImDrawList dl = ImGui.getBackgroundDrawList();
		dl.addRectFilled(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xC0202020, 4);
		// GL textures are stored bottom row first
		dl.addImage(imguiTexture, x0, y0, x1, y1, 0, 1, 1, 0);
		dl.addRect(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0xFF505050, 4, 0, 2);

		// the player: an arrow at its projected position, pointing where it looks (the map turns with it)
		var p = mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		float[] s = project(p.x, p.y, p.z, x0, y0, side);
		if (s != null) {
			double a = Math.toRadians(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yaw));
			float r = 7;
			float tx = s[0] + (float) Math.sin(a) * r, ty = s[1] - (float) Math.cos(a) * r;
			float lx = s[0] + (float) Math.sin(a + 2.5) * r, ly = s[1] - (float) Math.cos(a + 2.5) * r;
			float rx = s[0] + (float) Math.sin(a - 2.5) * r, ry = s[1] - (float) Math.cos(a - 2.5) * r;
			dl.addTriangleFilled(tx, ty, lx, ly, rx, ry, 0xFFFFFFFF);
			dl.addTriangle(tx, ty, lx, ly, rx, ry, 0xFF000000, 1.5f);
		}
		// north, on the edge of the map in its direction
		float[] n = project(p.x, p.y, p.z - distance, x0, y0, side);
		if (s != null && n != null) {
			float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
			double ang = Math.atan2(n[0] - s[0], -(n[1] - s[1]));
			float ex = cx + (float) Math.sin(ang) * (side / 2 - 10), ey = cy - (float) Math.cos(ang) * (side / 2 - 10);
			dl.addCircleFilled(ex, ey, 8, 0xC0202020);
			dl.addText(ex - 3.5f, ey - 7, 0xFF6060FF, "N");
		}
		String zoom = Math.round(distance) + " m";
		dl.addText(x1 - ImGui.calcTextSizeX(zoom) - 4, y1 - ImGui.getTextLineHeight() - 2, 0xC0FFFFFF, zoom);
	}

	/** Where a world point is on the minimap (screen coordinates), or null behind the camera. */
	static float[] project(double x, double y, double z, float x0, float y0, float side) {
		Matrix4f mvp = new Matrix4f().setPerspective((float) Math.toRadians(FOV), 1, 1, 1000).mul(modelView);
		Vector4f v = mvp.transform(new Vector4f((float) (x - cameraPos.x), (float) (y - cameraPos.y), (float) (z - cameraPos.z), 1));
		if (v.w <= 0) return null;
		return new float[]{x0 + (v.x / v.w * 0.5f + 0.5f) * side, y0 + (0.5f - v.y / v.w * 0.5f) * side};
	}
}
