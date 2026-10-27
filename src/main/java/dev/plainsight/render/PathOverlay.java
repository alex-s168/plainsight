package dev.plainsight.render;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Planning visualisation in the style of path-finding bots: the path as a line through the nodes,
 * the goal, and the blocks the plan will break or place. Meant to be filled by automation (and by the
 * editor demo); the editor draws every visible path overlay.
 */
public final class PathOverlay {
	private static final List<PathOverlay> VISIBLE = new CopyOnWriteArrayList<>();

	/** Feet positions the path walks through, in order. */
	public final List<BlockPos> nodes = new ArrayList<>();
	public final Set<BlockPos> toBreak = new LinkedHashSet<>();
	public final Set<BlockPos> toPlace = new LinkedHashSet<>();
	public @Nullable BlockPos goal;
	/** Index of the node the walker is at; nodes before it are drawn dimmed. */
	public int progress;
	public int pathColor = 0xFFFF4040;
	public int goalColor = 0xFF40FF40;
	public int breakColor = 0xFFFF2020;
	public int placeColor = 0xFF40A0FF;
	/** Also show the plan faintly through terrain. */
	public boolean seeThrough = true;

	public static List<PathOverlay> visible() {
		return VISIBLE;
	}

	public void show() {
		if (!VISIBLE.contains(this)) VISIBLE.add(this);
	}

	public void hide() {
		VISIBLE.remove(this);
	}

	public void clear() {
		nodes.clear();
		toBreak.clear();
		toPlace.clear();
		goal = null;
		progress = 0;
	}

	/** Adds the overlay's lines and boxes to {@code o}. */
	public void draw(Overlay o, double timeSeconds) {
		for (int i = 0; i + 1 < nodes.size(); i++) {
			BlockPos a = nodes.get(i), b = nodes.get(i + 1);
			int c = i < progress ? Overlay.withAlpha(Overlay.shade(pathColor, 0.6f), 140) : pathColor;
			o.line(Overlay.Layer.WORLD, a.getX() + 0.5, a.getY() + 0.1, a.getZ() + 0.5, b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5, c, 3.5f);
			if (seeThrough) {
				o.line(Overlay.Layer.XRAY, a.getX() + 0.5, a.getY() + 0.1, a.getZ() + 0.5, b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5,
						Overlay.withAlpha(c, 90), 2f);
			}
		}
		for (int i = 0; i < nodes.size(); i++) {
			BlockPos n = nodes.get(i);
			int c = i == progress ? 0xFFFFFFFF : Overlay.withAlpha(pathColor, 200);
			o.cube(Overlay.Layer.WORLD, new Vector3d(n.getX() + 0.5, n.getY() + 0.1, n.getZ() + 0.5), i == progress ? 0.22 : 0.12, c, 0, 1);
		}
		for (BlockPos p : toBreak) box(o, p, breakColor, 0.002);
		for (BlockPos p : toPlace) box(o, p, placeColor, 0.002);
		if (goal != null) {
			// pulsing goal column
			double pulse = 0.5 + 0.5 * Math.sin(timeSeconds * 4);
			int fill = Overlay.withAlpha(goalColor, (int) (30 + 40 * pulse));
			o.aabbFaces(Overlay.Layer.WORLD, goal.getX(), goal.getY(), goal.getZ(), goal.getX() + 1, goal.getY() + 2, goal.getZ() + 1, fill);
			o.aabbEdges(Overlay.Layer.WORLD, goal.getX(), goal.getY(), goal.getZ(), goal.getX() + 1, goal.getY() + 2, goal.getZ() + 1, goalColor, 2.5f);
			if (seeThrough) {
				o.aabbEdges(Overlay.Layer.XRAY, goal.getX(), goal.getY(), goal.getZ(), goal.getX() + 1, goal.getY() + 2, goal.getZ() + 1,
						Overlay.withAlpha(goalColor, 110), 2f);
			}
		}
	}

	private void box(Overlay o, BlockPos p, int color, double grow) {
		double x1 = p.getX() - grow, y1 = p.getY() - grow, z1 = p.getZ() - grow;
		double x2 = p.getX() + 1 + grow, y2 = p.getY() + 1 + grow, z2 = p.getZ() + 1 + grow;
		o.aabbFaces(Overlay.Layer.WORLD, x1, y1, z1, x2, y2, z2, Overlay.withAlpha(color, 50));
		o.aabbEdges(Overlay.Layer.WORLD, x1, y1, z1, x2, y2, z2, color, 2f);
		if (seeThrough) o.aabbEdges(Overlay.Layer.XRAY, x1, y1, z1, x2, y2, z2, Overlay.withAlpha(color, 90), 1.5f);
	}
}
