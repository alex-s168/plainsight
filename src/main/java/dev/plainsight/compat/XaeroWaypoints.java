package dev.plainsight.compat;

import dev.plainsight.Plainsight;
import net.fabricmc.loader.api.FabricLoader;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.waypoint.WaypointColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Xaero's Minimap's waypoints: read for plainsight's minimap, added by /plainsight locate. Only touches
 * Xaero's classes when it is installed ({@link #PRESENT}); every call fails soft (Xaero's internals may
 * change between its versions).
 */
public final class XaeroWaypoints {
	public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("xaerominimap");
	private static boolean warned;

	/** A waypoint of the current dimension's current set: block position, initials, name, colour (RGB). */
	public record Point(int x, int y, int z, String initials, String name, int rgb) {
	}

	private XaeroWaypoints() {
	}

	/** The enabled waypoints Xaero shows in this dimension now (empty without Xaero or a world). */
	public static List<Point> current() {
		List<Point> out = new ArrayList<>();
		if (!PRESENT) return out;
		try {
			var session = BuiltInHudModules.MINIMAP.getCurrentSession();
			if (session == null) return out;
			var world = session.getWorldManager().getCurrentWorld();
			var set = world == null ? null : world.getCurrentWaypointSet();
			if (set == null) return out;
			double division = session.getDimensionHelper().getDimensionDivision(world);
			for (Waypoint w : set.getWaypoints()) {
				if (w.isDisabled()) continue;
				out.add(new Point(w.getX(division), w.getY(), w.getZ(division), w.getInitials(), w.getName(), WaypointColor.fromIndex(w.getColor()).getHex()));
			}
		} catch (RuntimeException | LinkageError e) {
			warnOnce(e);
		}
		return out;
	}

	/** Adds a waypoint to Xaero's current set (and saves it); false if Xaero has no world now. */
	public static boolean add(int x, int y, int z, String name) {
		if (!PRESENT) return false;
		try {
			var session = BuiltInHudModules.MINIMAP.getCurrentSession();
			if (session == null) return false;
			var world = session.getWorldManager().getCurrentWorld();
			var set = world == null ? null : world.getCurrentWaypointSet();
			if (set == null) return false;
			set.add(new Waypoint(x, y, z, name, initials(name), WaypointColor.getRandom()));
			session.getWorldManagerIO().saveWorld(world);
			return true;
		} catch (java.io.IOException | RuntimeException | LinkageError e) {
			warnOnce(e);
			return false;
		}
	}

	/** Removes the current set's waypoints called {@code name} (tests); how many. */
	public static int removeNamed(String name) {
		if (!PRESENT) return 0;
		try {
			var session = BuiltInHudModules.MINIMAP.getCurrentSession();
			var world = session == null ? null : session.getWorldManager().getCurrentWorld();
			var set = world == null ? null : world.getCurrentWaypointSet();
			if (set == null) return 0;
			List<Waypoint> gone = new ArrayList<>();
			for (Waypoint w : set.getWaypoints()) if (name.equals(w.getName())) gone.add(w);
			set.removeAll(gone);
			session.getWorldManagerIO().saveWorld(world);
			return gone.size();
		} catch (java.io.IOException | RuntimeException | LinkageError e) {
			warnOnce(e);
			return 0;
		}
	}

	/** Up to two letters: the first of the first two words ("Village Plains" -> "VP"). */
	static String initials(String name) {
		StringBuilder sb = new StringBuilder();
		for (String word : name.trim().split("\\s+")) {
			if (!word.isEmpty() && sb.length() < 2) sb.appendCodePoint(word.codePointAt(0));
		}
		return sb.isEmpty() ? "X" : sb.toString().toUpperCase(Locale.ROOT);
	}

	private static void warnOnce(Throwable e) {
		if (warned) return;
		warned = true;
		Plainsight.LOG.warn("Xaero's Minimap waypoints unavailable (changed version?)", e);
	}
}
