package dev.plainsight.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaritoneShutdownTest {
	private static StackTraceElement f(String c, String m) {
		return new StackTraceElement(c, m, null, -1);
	}

	@Test
	void waitingThreadsAreRecognised() {
		assertTrue(BaritoneShutdown.waiting(new StackTraceElement[]{
				f("java.lang.Thread", "sleep0"), f("java.lang.Thread", "sleep"),
				f("baritone.cache.CachedWorld", "lambda$new$0"), f("java.lang.Thread", "run")}));
		assertTrue(BaritoneShutdown.waiting(new StackTraceElement[]{
				f("jdk.internal.misc.Unsafe", "park"), f("java.util.concurrent.locks.LockSupport", "park"),
				f("java.util.concurrent.LinkedBlockingQueue", "take"),
				f("baritone.cache.CachedWorld$PackerThread", "run")}));
	}

	@Test
	void savingAndOtherThreadsAreLeftAlone() {
		// the save loop while saving
		assertFalse(BaritoneShutdown.waiting(new StackTraceElement[]{
				f("java.io.FileOutputStream", "writeBytes"), f("baritone.cache.CachedRegion", "save"),
				f("baritone.cache.CachedWorld", "save"), f("baritone.cache.CachedWorld", "lambda$new$0")}));
		// the save started when the world is left
		assertFalse(BaritoneShutdown.waiting(new StackTraceElement[]{
				f("java.lang.Thread", "sleep"), f("baritone.cache.WorldData", "lambda$onClose$0")}));
		assertFalse(BaritoneShutdown.waiting(new StackTraceElement[]{
				f("java.lang.Thread", "sleep"), f("net.minecraft.Util", "run")}));
	}
}
