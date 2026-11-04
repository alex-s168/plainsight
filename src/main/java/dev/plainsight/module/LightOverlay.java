package dev.plainsight.module;

import dev.plainsight.render.Overlay;
import imgui.ImDrawList;
import imgui.ImGui;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Marks the top faces of blocks by the light above them: red where both block and sky light are at or
 * below the threshold (mobs can spawn at any time), yellow where only block light is (they can spawn at
 * night). Shown in the editor when enabled, and in the normal game too while editor overlays are shown
 * there (J).
 */
public final class LightOverlay implements Module {
	public static final int RED = 0x66FF2020, YELLOW = 0x66FFD020;

	private boolean enabled;
	/** Light level up to which a face counts as dark (0 = only complete darkness, like mob spawning). */
	public int threshold = 0;
	public int radius = 24;

	/** Marked faces: packed block position and colour, rebuilt periodically. */
	private final LongArrayList positions = new LongArrayList();
	private final it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
	private final Vector3d lastCentre = new Vector3d(Double.NaN, 0, 0);
	private long lastScan;
	private int lastThreshold = -1, lastRadius = -1;

	@Override
	public String id() {
		return "light_overlay";
	}

	@Override
	public String name() {
		return "Light Overlay";
	}

	@Override
	public String description() {
		return "Colour block tops by light: red = mobs can always spawn, yellow = at night. Also in game with J. Right-click for settings.";
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean e) {
		enabled = e;
		if (!e) {
			positions.clear();
			colors.clear();
		}
		lastScan = 0;
	}

	public int markedFaces() {
		return positions.size();
	}

	/** Adds the marks around {@code centre} to the overlay, rescanning when due. */
	public void render(Overlay o, Vector3dc centre) {
		if (!enabled) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		long now = System.currentTimeMillis();
		if (now - lastScan > 500 || !(lastCentre.distanceSquared(centre) < 4) || threshold != lastThreshold || radius != lastRadius) {
			scan(level, centre);
			lastScan = now;
			lastCentre.set(centre);
			lastThreshold = threshold;
			lastRadius = radius;
		}
		Vector3d a = new Vector3d(), b = new Vector3d(), c = new Vector3d(), d = new Vector3d();
		for (int i = 0; i < positions.size(); i++) {
			BlockPos p = BlockPos.of(positions.getLong(i));
			double y = p.getY() + 1.01, x0 = p.getX() + 0.06, z0 = p.getZ() + 0.06, x1 = p.getX() + 0.94, z1 = p.getZ() + 0.94;
			o.quad(Overlay.Layer.WORLD, a.set(x0, y, z0), b.set(x1, y, z0), c.set(x1, y, z1), d.set(x0, y, z1), colors.getInt(i));
		}
	}

	private void scan(ClientLevel level, Vector3dc centre) {
		positions.clear();
		colors.clear();
		int cx = (int) Math.floor(centre.x()), cy = (int) Math.floor(centre.y()), cz = (int) Math.floor(centre.z());
		int r = radius, ry = Math.max(8, radius / 2);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), above = new BlockPos.MutableBlockPos();
		for (int x = cx - r; x <= cx + r; x++) {
			for (int z = cz - r; z <= cz + r; z++) {
				if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > r * r) continue;
				for (int y = Math.max(level.getMinY(), cy - ry); y <= Math.min(level.getMaxY() - 1, cy + ry); y++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || !state.isFaceSturdy(level, pos, Direction.UP)) continue;
					above.set(x, y + 1, z);
					BlockState up = level.getBlockState(above);
					if (!up.getCollisionShape(level, above).isEmpty() || !up.getFluidState().isEmpty()) continue;
					int block = level.getBrightness(LightLayer.BLOCK, above);
					if (block > threshold) continue;
					int sky = level.getBrightness(LightLayer.SKY, above);
					positions.add(pos.asLong());
					colors.add(sky <= threshold ? RED : YELLOW);
				}
			}
		}
	}

	@Override
	public void options() {
		int[] t = {threshold};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderInt("Dark up to light", t, 0, 15)) threshold = t[0];
		int[] r = {radius};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderInt("Radius", r, 8, 48)) radius = r[0];
		ImGui.textDisabled(markedFaces() + " faces marked");
	}

	@Override
	public void saveSettings(com.google.gson.JsonObject o) {
		o.addProperty("threshold", threshold);
		o.addProperty("radius", radius);
	}

	@Override
	public void loadSettings(com.google.gson.JsonObject o) {
		threshold = ModuleConfig.getInt(o, "threshold", threshold);
		radius = ModuleConfig.getInt(o, "radius", radius);
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		// a light bulb
		float cx = x + size / 2, cy = y + size * 0.42f;
		dl.addCircle(cx, cy, size * 0.2f, color, 16, 2f);
		dl.addRectFilled(cx - size * 0.09f, cy + size * 0.2f, cx + size * 0.09f, cy + size * 0.34f, color, 1f);
		dl.addLine(cx - size * 0.07f, cy + size * 0.4f, cx + size * 0.07f, cy + size * 0.4f, color, 2f);
	}
}
