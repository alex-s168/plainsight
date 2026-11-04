package dev.infiniterra.impl;

/**
 * Whether a level-of-detail mod is installed. Detected by class (not mod id), so renamed or repackaged
 * builds still count.
 */
public final class LodDetect {
	private static final String[] LOD_CLASSES = {
			"me.cortex.voxy.client.VoxyClient", // Voxy
			"com.seibel.distanthorizons.api.DhApi", // Distant Horizons
	};
	private static Boolean present;

	private LodDetect() {
	}

	public static boolean present() {
		if (present == null) {
			boolean found = false;
			ClassLoader loader = LodDetect.class.getClassLoader();
			for (String c : LOD_CLASSES) {
				// look the class file up without loading (initialising) the other mod's class
				if (loader.getResource(c.replace('.', '/') + ".class") != null) found = true;
			}
			present = found;
		}
		return present;
	}
}
