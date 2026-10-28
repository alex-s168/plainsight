package dev.plainsight.guess;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SystemReport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A private, headless server that only generates terrain: a vanilla world (default world preset, the
 * given seed) without players, in its own folder. Chunks are asked for through
 * {@link net.minecraft.server.level.ServerChunkCache#getChunkFuture} on its thread and unload again like any
 * chunk without a ticket; they are not saved (Voxy keeps the result).
 */
final class GuessServer extends MinecraftServer {
	private final LocalSampleLogger tickTimes = new LocalSampleLogger(1);

	private GuessServer(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs, WorldStem stem,
			DataFixer fixer, Services services) {
		super(thread, access, packs, stem, Optional.empty(), Proxy.NO_PROXY, fixer, services, SILENT, false, new NotificationManager());
		setPlayerList(new PlayerList(this, registries(), playerDataStorage, notificationManager()) {
		});
	}

	/** Creates (or reopens) the world in {@code dir} and starts the server; blocks until the world data is loaded. */
	static GuessServer start(Path dir, long seed) throws Exception {
		LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(dir).createAccess("world");
		try {
			PackRepository packs = ServerPacksSource.createPackRepository(access);
			LevelSettings settings = new LevelSettings("plainsight guess", GameType.SPECTATOR,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), false, WorldDataConfiguration.DEFAULT);
			WorldOptions options = new WorldOptions(seed, true, false);
			WorldLoader.InitConfig config = new WorldLoader.InitConfig(new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, false),
					Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.GAMEMASTER);
			WorldStem stem = WorldLoader.load(config, context -> {
				WorldDimensions dimensions = context.datapackWorldRegistries().lookupOrThrow(Registries.WORLD_PRESET)
						.getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
				WorldDimensions.Complete complete = dimensions.bake(context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM));
				return new WorldLoader.DataLoadOutput<>(new LevelDataAndDimensions.WorldDataAndGenSettings(
						new PrimaryLevelData(settings, complete.specialWorldProperty(), complete.lifecycle()),
						new WorldGenSettings(options, dimensions)), complete.dimensionsRegistryAccess());
			}, WorldStem::new, Util.backgroundExecutor(), Util.backgroundExecutor()).get();
			access.saveDataTag(stem.worldDataAndGenSettings().data());
			Minecraft mc = Minecraft.getInstance();
			GuessServer server = MinecraftServer.spin(t -> new GuessServer(t, access, packs, stem, mc.getFixerUpper(), mc.services()));
			server.getRunningThread().setName("Plainsight worldgen server");
			server.getRunningThread().setPriority(Thread.NORM_PRIORITY - 1);
			return server;
		} catch (Exception | Error e) {
			access.safeClose();
			throw e;
		}
	}

	@Override
	protected boolean initServer() {
		loadLevel();
		// Voxy keeps what was generated; the chunks themselves need not be written to disk
		for (var level : getAllLevels()) level.noSave = true;
		return true;
	}

	@Override
	public LevelBasedPermissionSet operatorUserPermissions() {
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public PermissionSet getFunctionCompilationPermissions() {
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public boolean shouldRconBroadcast() {
		return false;
	}

	@Override
	protected SampleLogger getTickTimeLogger() {
		return tickTimes;
	}

	@Override
	public boolean isTickTimeLoggingEnabled() {
		return false;
	}

	@Override
	public SystemReport fillServerSystemReport(SystemReport report) {
		report.setDetail("Type", "Plainsight worldgen server");
		return report;
	}

	@Override
	public boolean isDedicatedServer() {
		return false;
	}

	@Override
	public int getRateLimitPacketsPerSecond() {
		return 0;
	}

	@Override
	public int getCommandSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public int getChatSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public boolean useNativeTransport() {
		return false;
	}

	@Override
	public boolean isPublished() {
		return false;
	}

	@Override
	public boolean shouldInformAdmins() {
		return false;
	}

	@Override
	public int getMaxPlayers() {
		return 0;
	}

	@Override
	public boolean isSingleplayerOwner(NameAndId user) {
		return false;
	}

	private static final LevelLoadListener SILENT = new LevelLoadListener() {
		@Override
		public void start(Stage stage, int totalChunks) {
		}

		@Override
		public void update(Stage stage, int currentChunks, int totalChunks) {
		}

		@Override
		public void finish(Stage stage) {
		}

		@Override
		public void updateFocus(ResourceKey<Level> dimension, ChunkPos chunkPos) {
		}
	};
}
