package dev.plainsight;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a level-of-detail renderer is installed. Without one the editor camera only sees the chunks
 * loaded around the player, which is a limit of the game we cannot lift ourselves. Detected by class
 * (not mod id), so renamed or repackaged builds still count.
 */
public final class LodSupport {
	private static final String[] LOD_CLASSES = {
			"me.cortex.voxy.client.VoxyClient", // Voxy
			"com.seibel.distanthorizons.api.DhApi", // Distant Horizons
	};
	private static Boolean present;

	private LodSupport() {
	}

	public static boolean present() {
		if (present == null) {
			boolean found = false;
			ClassLoader loader = LodSupport.class.getClassLoader();
			for (String c : LOD_CLASSES) {
				// look the class file up without loading (initialising) the other mod's class
				if (loader.getResource(c.replace('.', '/') + ".class") != null) found = true;
			}
			present = found;
		}
		return present;
	}

	private static Path dismissedFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(Plainsight.ID).resolve("lod-warning-dismissed");
	}

	private static Boolean dismissed;

	/** The user chose not to be reminded again. */
	public static boolean dismissed() {
		if (dismissed == null) dismissed = Files.exists(dismissedFile());
		return dismissed;
	}

	public static void dismiss() {
		dismissed = true;
		try {
			Files.createDirectories(dismissedFile().getParent());
			Files.writeString(dismissedFile(), "The editor's no-LOD-mod hint was dismissed. Delete this file to see it again.\n");
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not save the LOD hint setting", e);
		}
	}

	/** For tests: show the hint again. */
	public static void undismiss() {
		dismissed = false;
		try {
			Files.deleteIfExists(dismissedFile());
		} catch (IOException e) {
			Plainsight.LOG.warn("Could not reset the LOD hint setting", e);
		}
	}

	public static boolean showWarning() {
		return !present() && !dismissed();
	}
}
