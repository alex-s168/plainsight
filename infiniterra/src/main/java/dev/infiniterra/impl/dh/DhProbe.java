package dev.infiniterra.impl.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import dev.infiniterra.impl.guess.SeedServer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.TimeUnit;

/**
 * Prototype (#48, step 3): feeds one chunk generated from a seed into Distant Horizons' LOD of the client
 * level through its API ({@code overwriteChunkDataAsync}), and reads DH's data there back. Tests only.
 */
public final class DhProbe {
	private DhProbe() {
	}

	/** The result of the last {@link #run} (DH's data calls must not block the render thread). */
	private static volatile String last = "";

	/** Runs "inject seed cx cz", "surface x z" or "block x y z" on a thread of its own; see {@link #last()}. */
	public static String run(String what) {
		last = "running";
		Thread t = new Thread(() -> {
			try {
				String[] a = what.split(" ");
				last = switch (a[0]) {
					case "inject" -> inject(Long.parseLong(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
					case "surface" -> surface(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
					default -> block(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
				};
			} catch (Throwable e) {
				last = "error: " + e;
			}
		}, "InfiniTerra DH probe");
		t.setDaemon(true);
		t.start();
		return "started";
	}

	public static String last() {
		return last;
	}

	public static boolean present() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");
	}

	/** The client level's DH wrapper ("type dimension" of each loaded one for the report). */
	static IDhApiLevelWrapper clientLevel(StringBuilder report) {
		IDhApiLevelWrapper found = null;
		for (IDhApiLevelWrapper w : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
			report.append(w.getLevelType()).append(' ').append(w.getDimensionName()).append("; ");
			if (w.getLevelType() == EDhApiLevelType.CLIENT_LEVEL && w.getDimensionName().equals(Minecraft.getInstance().level.dimension().identifier().toString()))
				found = w;
		}
		if (found == null) {
			// in singleplayer DH may give the shared level a server type: the one of this dimension
			for (IDhApiLevelWrapper w : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers())
				if (w.getDimensionName().equals(Minecraft.getInstance().level.dimension().identifier().toString())) found = w;
		}
		return found;
	}

	/** Generates chunk (cx, cz) from {@code seed} and gives it to DH for the client level; a report. */
	public static String inject(long seed, int cx, int cz) throws Exception {
		if (!present()) return "no Distant Horizons";
		StringBuilder report = new StringBuilder();
		IDhApiLevelWrapper level = clientLevel(report);
		if (level == null) return "no DH level: " + report;
		SeedServer server = SeedServer.acquire(seed);
		try {
			var mc = server.ready().get(120, TimeUnit.SECONDS);
			ChunkAccess chunk = mc.submit(() -> mc.overworld().getChunk(cx, cz, ChunkStatus.FULL, true)).get(120, TimeUnit.SECONDS);
			var result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(level, new Object[]{chunk, Minecraft.getInstance().level});
			return (result.success ? "ok" : "failed: " + result.message) + " (" + level.getLevelType() + "; levels: " + report + ")";
		} finally {
			server.release();
		}
	}

	/** DH's block at a position of the client level's LOD ("none" where it has no data). */
	public static String block(int x, int y, int z) {
		if (!present()) return "no Distant Horizons";
		IDhApiLevelWrapper level = clientLevel(new StringBuilder());
		if (level == null) return "no DH level";
		var r = DhApi.Delayed.terrainRepo.getSingleDataPointAtBlockPos(level, x, y, z, DhApi.Delayed.terrainRepo.createSoftCache());
		if (!r.success || r.payload == null) return "none: " + r.message;
		return r.payload.blockStateWrapper == null ? "null" : r.payload.blockStateWrapper.getSerialString();
	}

	/** The highest non-air block DH knows in the column ("none" without data). */
	public static String surface(int x, int z) {
		if (!present()) return "no Distant Horizons";
		IDhApiLevelWrapper level = clientLevel(new StringBuilder());
		if (level == null) return "no DH level";
		var r = DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(level, x, z, DhApi.Delayed.terrainRepo.createSoftCache());
		if (!r.success || r.payload == null || r.payload.length == 0) return "none: " + r.message;
		DhApiTerrainDataPoint top = null;
		for (var p : r.payload)
			if (p.blockStateWrapper != null && !p.blockStateWrapper.isAir() && (top == null || p.topYBlockPos > top.topYBlockPos)) top = p;
		if (top == null) return "only air (" + r.payload.length + " points)";
		return top.blockStateWrapper.getSerialString() + " up to " + top.topYBlockPos + " (" + r.payload.length + " points)";
	}
}
