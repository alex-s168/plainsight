package dev.plainsight.compat;

import dev.plainsight.Plainsight;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Baritone never stops its thread pool, whose threads are not daemons: its region cache packer and the
 * 10-minute save loop of each world it cached keep the JVM alive after the game closed, until Minecraft's
 * shutdown watchdog crashes it ("Client shutdown from post-main"). Once the game thread has ended, the
 * pool is shut down (the save Baritone starts when the world is left still runs) and the threads that
 * only wait (the packer on its queue, the save loop in its sleep) are interrupted: both tasks then end.
 * A thread that is saving is left alone until it waits again.
 */
public final class BaritoneShutdown {
	private static final long TIMEOUT_MS = 60_000;

	private BaritoneShutdown() {
	}

	/** On the game thread, when the client stops. */
	public static void schedule() {
		ExecutorService pool;
		try {
			Class<?> c = Class.forName("baritone.Baritone", false, BaritoneShutdown.class.getClassLoader());
			if (!(c.getMethod("getExecutor").invoke(null) instanceof ExecutorService p)) return;
			pool = p;
		} catch (ClassNotFoundException e) {
			return;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			Plainsight.LOG.debug("No Baritone thread pool", e);
			return;
		}
		Thread game = Thread.currentThread();
		Thread t = new Thread(() -> stopWhenDone(game, pool), "plainsight baritone shutdown");
		t.setDaemon(true);
		t.start();
	}

	private static void stopWhenDone(Thread game, ExecutorService pool) {
		try {
			game.join();
			pool.shutdown();
			long end = System.currentTimeMillis() + TIMEOUT_MS;
			while (!pool.isTerminated() && System.currentTimeMillis() < end) {
				for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet())
					if (waiting(e.getValue())) e.getKey().interrupt();
				Thread.sleep(100);
			}
		} catch (InterruptedException ignored) {
		} catch (RuntimeException e) {
			Plainsight.LOG.debug("Could not stop Baritone's threads", e);
		}
	}

	/** Whether the stack is Baritone's packer waiting for a chunk or its save loop sleeping. */
	static boolean waiting(StackTraceElement[] stack) {
		for (int i = 1; i < stack.length; i++) {
			String c = stack[i].getClassName();
			if (!c.startsWith("baritone.")) continue;
			String below = stack[i - 1].getClassName() + "." + stack[i - 1].getMethodName();
			if (c.equals("baritone.cache.CachedWorld$PackerThread") && stack[i].getMethodName().equals("run"))
				return below.equals("java.util.concurrent.LinkedBlockingQueue.take");
			if (c.equals("baritone.cache.CachedWorld") && stack[i].getMethodName().startsWith("lambda$"))
				return below.equals("java.lang.Thread.sleep");
			return false;
		}
		return false;
	}
}
