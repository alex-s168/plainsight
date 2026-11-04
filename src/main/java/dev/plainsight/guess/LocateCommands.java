package dev.plainsight.guess;

import dev.infiniterra.api.WorldgenServer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.datafixers.util.Pair;
import dev.plainsight.compat.XaeroWaypoints;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /plainsight locate structure|biome <id or #tag>}: vanilla's /locate, run on the seed's worldgen
 * server ({@link WorldgenServer}) from the player's position in its dimension, so it works on any server once
 * the seed is known. The answer is what the seed generates (a world made with other versions or mods can
 * differ). With Xaero's Minimap the result offers a waypoint ({@code /plainsight waypoint x y z name}).
 */
public final class LocateCommands {
	private static final int MAX_STRUCTURE_SEARCH_RADIUS = 100, MAX_BIOME_SEARCH_RADIUS = 6400;
	/** The server this command keeps for the world's seed (released when leaving the world or the seed changes). */
	private static @Nullable WorldgenServer held;

	private enum Kind {
		STRUCTURE("structure", "commands.locate.structure.success", "commands.locate.structure.not_found", "commands.locate.structure.invalid"),
		BIOME("biome", "commands.locate.biome.success", "commands.locate.biome.not_found", null);

		final String arg, success, notFound;
		/** Vanilla's message for an unknown id (biomes have none: their argument rejects it). */
		private final @Nullable String invalid;

		Kind(String arg, String success, String notFound, String invalid) {
			this.arg = arg;
			this.success = success;
			this.notFound = notFound;
			this.invalid = invalid;
		}

		Component invalid(String input) {
			return invalid != null ? Component.translatable(invalid, input) : Component.literal("There is no biome with type \"" + input + "\"");
		}

		ResourceKey<? extends Registry<?>> registry() {
			return this == STRUCTURE ? Registries.STRUCTURE : Registries.BIOME;
		}
	}

	private LocateCommands() {
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> locate() {
		LiteralArgumentBuilder<FabricClientCommandSource> locate = literal("locate");
		for (Kind kind : Kind.values()) {
			locate.then(literal(kind.arg).then(argument(kind.arg, StringArgumentType.greedyString()).suggests(suggestions(kind))
					.executes(c -> run(c, kind))));
		}
		return locate;
	}

	public static LiteralArgumentBuilder<FabricClientCommandSource> waypoint() {
		return literal("waypoint").then(argument("x", IntegerArgumentType.integer()).then(argument("y", IntegerArgumentType.integer())
				.then(argument("z", IntegerArgumentType.integer()).then(argument("name", StringArgumentType.greedyString()).executes(c -> {
					int x = IntegerArgumentType.getInteger(c, "x"), y = IntegerArgumentType.getInteger(c, "y"), z = IntegerArgumentType.getInteger(c, "z");
					String name = StringArgumentType.getString(c, "name");
					if (!XaeroWaypoints.PRESENT) {
						c.getSource().sendError(Component.literal("Waypoints need Xaero's Minimap."));
						return 0;
					}
					if (!XaeroWaypoints.add(x, y, z, name)) {
						c.getSource().sendError(Component.literal("Xaero's Minimap has no waypoint set for this world yet."));
						return 0;
					}
					c.getSource().sendFeedback(Component.literal("Waypoint \"" + name + "\" added at " + x + " " + y + " " + z + "."));
					return 1;
				})))));
	}

	/** {@code /plainsight select x1 y1 z1 x2 y2 z2 name}: a box selection over those blocks, selected in the editor. */
	public static LiteralArgumentBuilder<FabricClientCommandSource> select() {
		var name = argument("name", StringArgumentType.greedyString()).executes(c -> select(c, StringArgumentType.getString(c, "name")));
		return literal("select").then(argument("x1", IntegerArgumentType.integer()).then(argument("y1", IntegerArgumentType.integer())
				.then(argument("z1", IntegerArgumentType.integer()).then(argument("x2", IntegerArgumentType.integer())
						.then(argument("y2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer())
								.executes(c -> select(c, null)).then(name)))))));
	}

	private static int select(CommandContext<FabricClientCommandSource> c, @Nullable String name) {
		int[] v = new int[6];
		String[] args = {"x1", "y1", "z1", "x2", "y2", "z2"};
		for (int i = 0; i < 6; i++) v[i] = IntegerArgumentType.getInteger(c, args[i]);
		var editor = dev.plainsight.editor.Editor.get();
		var node = editor.selectBlocks(v[0], v[1], v[2], v[3], v[4], v[5], name);
		c.getSource().sendFeedback(Component.literal("Selection \"" + node.name() + "\" created (" + node.label().replaceAll(".*\\(|\\)$", "")
				+ ") and selected" + (dev.plainsight.editor.Editor.isActive() ? "." : "; open the editor (Right Shift) to see it.")));
		return 1;
	}

	/** Stops using the seed's server (leaving the world). Render thread. */
	public static void shutdown() {
		if (held != null) held.release();
		held = null;
	}

	/** The worldgen server for the world's seed (started if needed), or null without a seed. Render thread. */
	private static @Nullable WorldgenServer server() {
		OptionalLong seed = Minecraft.getInstance().level == null ? OptionalLong.empty() : SeedStore.seed();
		if (held != null && (seed.isEmpty() || held.seed() != seed.getAsLong() || held.failed())) shutdown();
		if (held == null && seed.isPresent()) held = WorldgenServer.acquire(seed.getAsLong());
		return held;
	}

	/** The structures or biomes (and their tags) of the seed's server; starts it on the first try. */
	private static SuggestionProvider<FabricClientCommandSource> suggestions(Kind kind) {
		return (c, builder) -> {
			WorldgenServer s = server();
			MinecraftServer server = s == null ? null : s.serverNow();
			if (server == null) return builder.buildFuture();
			var lookup = server.registryAccess().lookupOrThrow(kind.registry());
			Stream<Identifier> ids = lookup.listElementIds().map(ResourceKey::identifier);
			SharedSuggestionProvider.suggestResource(lookup.listTagIds().map(TagKey::location), builder, "#");
			return SharedSuggestionProvider.suggestResource(ids, builder);
		};
	}

	private static int run(CommandContext<FabricClientCommandSource> c, Kind kind) {
		FabricClientCommandSource source = c.getSource();
		String input = StringArgumentType.getString(c, kind.arg).trim();
		Minecraft mc = Minecraft.getInstance();
		WorldgenServer s = server();
		if (s == null || mc.player == null || mc.level == null) {
			source.sendError(Component.literal("No seed known for this world: SeedcrackerX finds it, or use /plainsight seed <seed>."));
			return 0;
		}
		boolean tag = input.startsWith("#");
		Identifier id = Identifier.tryParse(tag ? input.substring(1) : input);
		if (id == null) {
			source.sendError(kind.invalid(input));
			return 0;
		}
		var dimension = mc.level.dimension();
		BlockPos origin = mc.player.blockPosition();
		if (s.serverNow() == null) source.sendFeedback(Component.literal("Starting the worldgen server for this seed...").withStyle(ChatFormatting.GRAY));
		s.ready().whenComplete((server, error) -> {
			if (error != null) {
				mc.execute(() -> source.sendError(Component.literal("The worldgen server could not start: " + error)));
				return;
			}
			server.execute(() -> {
				Component result;
				try {
					result = locate(server.getLevel(dimension), kind, tag, id, input, origin);
				} catch (RuntimeException e) {
					dev.plainsight.Plainsight.LOG.warn("Locating {} failed", input, e);
					result = Component.literal("Locating failed: " + e).withStyle(ChatFormatting.RED);
				}
				Component shown = result;
				mc.execute(() -> source.sendFeedback(shown));
			});
		});
		return 1;
	}

	/** On the worldgen server's thread: the nearest match as vanilla shows it (an error message if none). */
	private static Component locate(@Nullable ServerLevel level, Kind kind, boolean tag, Identifier id, String input, BlockPos origin) {
		if (level == null) return Component.literal("The seed's generator has no " + Minecraft.getInstance().level.dimension().identifier() + ".").withStyle(ChatFormatting.RED);
		Pair<BlockPos, ? extends Holder<?>> found;
		if (kind == Kind.STRUCTURE) {
			Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			HolderSet<Structure> target = tag ? registry.get(TagKey.create(Registries.STRUCTURE, id)).orElse(null)
					: registry.get(ResourceKey.create(Registries.STRUCTURE, id)).<HolderSet<Structure>>map(HolderSet::direct).orElse(null);
			if (target == null) return kind.invalid(input).copy().withStyle(ChatFormatting.RED);
			found = level.getChunkSource().getGenerator().findNearestMapStructure(level, target, origin, MAX_STRUCTURE_SEARCH_RADIUS, false);
		} else {
			var registry = level.registryAccess().lookupOrThrow(Registries.BIOME);
			Predicate<Holder<net.minecraft.world.level.biome.Biome>> test;
			if (tag) {
				var key = TagKey.create(Registries.BIOME, id);
				if (registry.get(key).isEmpty()) return kind.invalid(input).copy().withStyle(ChatFormatting.RED);
				test = h -> h.is(key);
			} else {
				var key = ResourceKey.create(Registries.BIOME, id);
				if (registry.get(key).isEmpty()) return kind.invalid(input).copy().withStyle(ChatFormatting.RED);
				test = h -> h.is(key);
			}
			found = level.findClosestBiome3d(test, origin, MAX_BIOME_SEARCH_RADIUS, 32, 64);
		}
		if (found == null) return Component.translatable(kind.notFound, input).withStyle(ChatFormatting.RED);
		BlockPos pos = found.getFirst();
		boolean includeY = kind == Kind.BIOME;
		// structures are found by chunk: their height for a waypoint is the generated surface there
		int y = includeY ? pos.getY() : level.getChunkSource().getGenerator().getBaseHeight(pos.getX(), pos.getZ(), Heightmap.Types.WORLD_SURFACE_WG,
				level, level.getChunkSource().randomState());
		String foundId = found.getSecond().getRegisteredName();
		// a structure's pieces: its bounds (for a selection) and what it is exactly
		StructureStart start = kind == Kind.STRUCTURE ? start(level, (Structure) found.getSecond().value(), pos) : null;
		String details = start == null ? null : StructureDetails.describe(foundId, start);
		String name = tag ? input + " (" + foundId + (details != null ? ", " + details : "") + ")"
				: id + (details != null ? " (" + details + ")" : "");
		return message(kind, name, origin, pos, y, includeY, foundId, details, start == null ? null : start.getBoundingBox());
	}

	/** The structure's start generated in or next to the chunk it was located in. */
	private static @Nullable StructureStart start(ServerLevel level, Structure structure, BlockPos at) {
		int cx = at.getX() >> 4, cz = at.getZ() >> 4;
		for (int r = 0; r <= 1; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					var chunk = level.getChunk(cx + dx, cz + dz, ChunkStatus.STRUCTURE_STARTS, true);
					StructureStart s = chunk.getStartForStructure(structure);
					if (s != null && s.isValid()) return s;
				}
			}
		}
		return null;
	}

	/** Vanilla's message (green coordinates suggesting a /tp), marked as from the seed, and a waypoint button with Xaero. */
	private static Component message(Kind kind, String name, BlockPos origin, BlockPos pos, int y, boolean includeY, String foundId,
			@Nullable String details, @Nullable BoundingBox bounds) {
		int distance = includeY ? Mth.floor(Mth.sqrt((float) origin.distSqr(pos)))
				: Mth.floor(Mth.sqrt((float) (Mth.square(pos.getX() - origin.getX()) + Mth.square(pos.getZ() - origin.getZ()))));
		String shownY = includeY ? String.valueOf(pos.getY()) : "~";
		Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", pos.getX(), shownY, pos.getZ()))
				.withStyle(st -> st.withColor(ChatFormatting.GREEN)
						.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + pos.getX() + " " + shownY + " " + pos.getZ()))
						.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
		MutableComponent out = Component.translatable(kind.success, name, coordinates, distance);
		out.append(Component.literal(" (from the seed)").withStyle(st -> st.withColor(ChatFormatting.GRAY)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Found with the world seed's generator: a world made with other versions or mods can differ.")))));
		String selectionName = displayName(foundId) + (details != null ? " (" + details + ")" : "");
		if (bounds != null) {
			out.append(" ").append(ComponentUtils.wrapInSquareBrackets(Component.literal("Create selection")).withStyle(st -> st.withColor(ChatFormatting.YELLOW)
					.withClickEvent(new ClickEvent.RunCommand("/plainsight select " + bounds.minX() + " " + bounds.minY() + " " + bounds.minZ() + " "
							+ bounds.maxX() + " " + bounds.maxY() + " " + bounds.maxZ() + " " + selectionName))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("A box selection around the whole structure ("
							+ (bounds.getXSpan()) + " x " + bounds.getYSpan() + " x " + bounds.getZSpan() + "), selected in the editor")))));
		}
		if (XaeroWaypoints.PRESENT) {
			String waypointName = displayName(foundId);
			out.append(" ").append(ComponentUtils.wrapInSquareBrackets(Component.literal("+ Waypoint")).withStyle(st -> st.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.RunCommand("/plainsight waypoint " + pos.getX() + " " + y + " " + pos.getZ() + " " + waypointName))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Add a waypoint \"" + waypointName + "\" in Xaero's Minimap")))));
		}
		return out;
	}

	/** "minecraft:village_plains" -> "Village Plains". */
	static String displayName(String id) {
		String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		path = path.substring(path.lastIndexOf('/') + 1);
		StringBuilder sb = new StringBuilder();
		for (String w : path.split("_")) {
			if (w.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
		}
		return sb.toString();
	}
}
