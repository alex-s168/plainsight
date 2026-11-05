package dev.infiniterra.impl.guess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Which chunks of the guessed LOD world are not real in the world being played (guesses, or real only in
 * another world with the same seed), as a texture Voxy's terrain shader reads: with
 * the guessed world rendered all the time (so switching the editor needs no new renderer), the main view
 * outside the editor hides those chunks and shows only what is real (see {@code ShaderPatchMixin#patchGuessMask}).
 * One texel per chunk, {@link #SIZE} chunks across, centred on the camera. Render thread.
 */
public final class VoxyGuessMask {
	/** Chunks across (32768 blocks): outside, everything is hidden (the guessed world may hold more there). */
	public static final int SIZE = 2048;
	/** The texture unit the mask is bound to (well above the ones Voxy and shader packs use). */
	public static final int UNIT = 15;

	private static int texture;
	private static ByteBuffer upload;
	private static final byte[] cells = new byte[SIZE * SIZE];
	private static int originX = Integer.MIN_VALUE, originZ, version = -1;
	/** The camera this frame (blocks), for the shader's tie-break at chunk borders. */
	private static double cameraX, cameraY, cameraZ;
	private static GuessWorld builtFor;
	private static long builtAt;
	/** Whether the main view hides guessed chunks now (set by {@link VoxyGuessImpl#show}). */
	static volatile boolean hide;
	/** Whether Voxy's terrain shader was patched to hide guessed chunks by the mask. */
	public static volatile boolean patched;
	public static int rebuilds;

	private VoxyGuessMask() {
	}

	/** Keeps the mask up to date around the camera (at most 4 rebuilds a second while guesses change). */
	static void update(GuessWorld w, double camX, double camY, double camZ) {
		cameraX = camX;
		cameraY = camY;
		cameraZ = camZ;
		if (texture == 0) {
			texture = GL11.glGenTextures();
			int prev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, SIZE, SIZE, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
			GL13.glActiveTexture(prev);
			upload = MemoryUtil.memAlloc(SIZE * SIZE);
		}
		int ox = ((int) Math.floor(camX) >> 4) - SIZE / 2, oz = ((int) Math.floor(camZ) >> 4) - SIZE / 2;
		boolean moved = Math.abs(ox - originX) > SIZE / 8 || Math.abs(oz - originZ) > SIZE / 8;
		long now = System.currentTimeMillis();
		if (!moved && w == builtFor && (w.version() == version || now - builtAt < 250)) return;
		if (moved) {
			originX = ox;
			originZ = oz;
		}
		builtFor = w;
		version = w.version();
		builtAt = now;
		w.fillHideMask(originX, originZ, SIZE, cells);
		upload.clear();
		upload.put(cells).flip();
		int prev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SIZE, SIZE, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, upload);
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
		GL13.glActiveTexture(prev);
		rebuilds++;
	}

	/**
	 * Before Voxy draws terrain with {@code program}: binds the mask and sets whether guesses are hidden. The
	 * binding stays (other draws with the program, like the temporal pass, see the same mask).
	 */
	public static void apply(int program) {
		int hideLoc = GL20.glGetUniformLocation(program, "infiniterraHideGuessed");
		if (hideLoc < 0) return;
		boolean on = hide && texture != 0;
		GL41.glProgramUniform1i(program, hideLoc, on ? 1 : 0);
		if (!on) return;
		GL41.glProgramUniform2i(program, GL20.glGetUniformLocation(program, "infiniterraMaskOrigin"), originX * 16, originZ * 16);
		GL41.glProgramUniform1i(program, GL20.glGetUniformLocation(program, "infiniterraMaskSize"), SIZE);
		GL41.glProgramUniform3f(program, GL20.glGetUniformLocation(program, "infiniterraMaskCamera"),
				(float) (cameraX - originX * 16.0), (float) cameraY, (float) (cameraZ - originZ * 16.0));
		GL41.glProgramUniform1i(program, GL20.glGetUniformLocation(program, "infiniterraGuessMask"), UNIT);
		int prev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL13.glActiveTexture(prev);
	}

	/** Voxy shut down (world left): the GL objects go with the context's use. */
	static void release() {
		if (texture != 0) GL11.glDeleteTextures(texture);
		texture = 0;
		if (upload != null) MemoryUtil.memFree(upload);
		upload = null;
		originX = Integer.MIN_VALUE;
		builtFor = null;
		version = -1;
	}
}
