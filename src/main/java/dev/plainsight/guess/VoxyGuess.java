package dev.plainsight.guess;

import dev.plainsight.Plainsight;
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
			Plainsight.LOG.error("Could not set up Voxy for guessed terrain", e);
		}
	}

	static void detach() {
		if (PRESENT) VoxyGuessImpl.detach();
	}

	/** Copies the normal LOD world's data around chunk (cx, cz) (2x2 chunks) into the guessed world; which 32-block sections had any. */
	static long copyReal(int cx, int cz) {
		if (!PRESENT) return 0;
		try {
			return VoxyGuessImpl.copyReal(cx, cz);
		} catch (RuntimeException | LinkageError e) {
			Plainsight.LOG.warn("Could not copy LOD at chunk {}, {}", cx, cz, e);
			return 0;
		}
	}

	public static String describeReal(int cx, int cz) {
		return PRESENT ? VoxyGuessImpl.describeReal(cx, cz) : "no voxy";
	}

	/** Lists the 32-block LOD columns the normal LOD world has data in (packed like a ChunkPos). */
	static void realColumns(java.util.function.LongConsumer out) {
		if (!PRESENT) return;
		try {
			VoxyGuessImpl.realColumns(out);
		} catch (RuntimeException | LinkageError e) {
			Plainsight.LOG.warn("Could not list Voxy's LOD", e);
		}
	}

	/** Gives a generated chunk to the guessed LOD world (server thread); false if that is not possible. */
	static boolean ingestGuessed(LevelChunk chunk, long realSections) {
		return PRESENT && VoxyGuessImpl.ingestGuessed(chunk, realSections);
	}

	/** Shows the guessed LOD world (editor) or the normal one; call every frame from the render thread. */
	public static void show(boolean guessed) {
		if (PRESENT) VoxyGuessImpl.show(guessed);
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

	/** Whether Voxy currently renders the guessed LOD world. */
	public static boolean showing() {
		return PRESENT && VoxyGuessImpl.showing();
	}
}
