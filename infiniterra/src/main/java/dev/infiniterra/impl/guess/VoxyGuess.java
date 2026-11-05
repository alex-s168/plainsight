package dev.infiniterra.impl.guess;

import dev.infiniterra.InfiniTerraMod;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Voxy side of guessed terrain, without Voxy types here (so this loads without Voxy); the work is done
 * by {@link VoxyGuessImpl}, which is only touched when Voxy is installed.
 */
public final class VoxyGuess {
	public static final boolean PRESENT = VoxyGuess.class.getClassLoader()
			.getResource("me/cortex/voxy/client/core/IVoxyRenderSystemHolder.class") != null;

	private VoxyGuess() {
	}

	/** Whether Voxy is up (not between worlds or shutting down). */
	static boolean running() {
		if (!PRESENT) return false;
		try {
			return VoxyGuessImpl.running();
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	static void attach(GuessWorld world) {
		if (!PRESENT) return;
		try {
			VoxyGuessImpl.attach(world);
		} catch (RuntimeException | LinkageError e) {
			InfiniTerraMod.LOG.error("Could not set up Voxy for guessed terrain", e);
		}
	}

	static void detach() {
		if (PRESENT) VoxyGuessImpl.detach();
	}

	/** Copies the normal LOD world's data around chunk (cx, cz) (2x2 chunks) into the guessed world; per chunk, which 16-block sections it covered. */
	static long[] copyReal(int cx, int cz) {
		if (!PRESENT) return new long[4];
		try {
			return VoxyGuessImpl.copyReal(cx, cz);
		} catch (RuntimeException | LinkageError e) {
			InfiniTerraMod.LOG.warn("Could not copy LOD at chunk {}, {}", cx, cz, e);
			return new long[4];
		}
	}

	public static String describeReal(int cx, int cz) {
		return PRESENT ? VoxyGuessImpl.describeReal(cx, cz) : "no voxy";
	}

	/** Chunks around (cx, cz) whose surface the guessed world lacks compared with the normal LOD world (debugging). */
	public static String compareReal(int cx, int cz, int r) {
		return PRESENT ? VoxyGuessImpl.compareReal(cx, cz, r) : "no voxy";
	}

	/** Lists the 32-block LOD columns the normal LOD world has data in (packed like a ChunkPos). */
	static void realColumns(java.util.function.LongConsumer out) {
		if (!PRESENT) return;
		try {
			VoxyGuessImpl.realColumns(out);
		} catch (RuntimeException | LinkageError e) {
			InfiniTerraMod.LOG.warn("Could not list Voxy's LOD", e);
		}
	}

	/** Gives a generated chunk to the guessed LOD world (server thread); false if that is not possible. */
	static boolean ingestGuessed(LevelChunk chunk, long realSections) {
		return PRESENT && VoxyGuessImpl.ingestGuessed(chunk, realSections);
	}

	/** Shows the guessed LOD world (editor) or the normal one; call every frame from the render thread. */
	public static void show(boolean guessed) {
		if (!PRESENT) return;
		VoxyGuessImpl.show(guessed);
		GuessWorld w = GuessWorld.current();
		if (w == null) return;
		w.showing = VoxyGuessImpl.rendering();
		if (w.reloadView && w.showing) {
			w.reloadView = false;
			VoxyGuessImpl.reload();
		}
	}

	/** The block Voxy's rendered LOD world has at (x, y, z), or null when it has none (or no Voxy). Render thread. */
	public static net.minecraft.world.level.block.state.BlockState lodBlock(int x, int y, int z) {
		if (!PRESENT) return null;
		try {
			return VoxyGuessImpl.lodBlock(x, y, z);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** How far Voxy draws its LOD, in 32-block LOD columns. */
	static int lodRadiusColumns() {
		return PRESENT ? VoxyGuessImpl.lodRadiusColumns() : 0;
	}

	/** For tests: LOD columns around the player to import before seamless switching (0: Voxy's LOD distance). */
	public static String setSeamlessRadius(int columns) {
		if (PRESENT) VoxyGuessImpl.radiusOverride = columns;
		return "ok";
	}

	/** Whether opening and closing the editor switches seamlessly now (the LOD around the player is copied in). */
	public static boolean seamlessReady() {
		return PRESENT && VoxyGuessImpl.seamlessReady();
	}

	/** Whether Voxy currently shows the guessed terrain (renders the guessed LOD world, guesses not hidden). */
	public static boolean showing() {
		return PRESENT && VoxyGuessImpl.showing();
	}

	/** Off: switch Voxy's renderer between the worlds as before (tests compare the two). */
	public static String setSeamless(boolean on) {
		if (PRESENT) VoxyGuessImpl.seamless = on;
		return "ok";
	}

	/** For tests: "rendering hide switches patched importDone". */
	public static String seamlessDebug() {
		return PRESENT ? VoxyGuessImpl.debug() : "no voxy";
	}

	/** Whether Voxy renders the guessed LOD world, also outside the editor with the guesses hidden (tests). */
	public static boolean rendering() {
		return PRESENT && VoxyGuessImpl.rendering();
	}
}
