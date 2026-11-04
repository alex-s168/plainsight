package dev.plainsight.guess;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.util.OptionalLong;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * {@code /plainsight seed [<seed>|clear|world|hide <true|false>]}, {@code /plainsight guess radius <chunks>} and
 * {@code /plainsight locate ...} ({@link LocateCommands}): client-side commands around the world seed.
 */
public final class GuessCommands {
	private GuessCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("plainsight")
				.then(literal("seed")
						.executes(GuessCommands::show)
						.then(literal("clear").executes(c -> {
							SeedStore.clear();
							c.getSource().sendFeedback(Component.literal("Seed cleared; no guessed terrain for this world."));
							return 1;
						}))
						.then(literal("world").executes(GuessCommands::fromWorld))
						.then(literal("hide").then(argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> {
							boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on");
							SeedHider.setEnabled(on);
							c.getSource().sendFeedback(Component.literal(on ? "Seeds are hidden on screen." : "Seeds are shown on screen."));
							return 1;
						})))
						.then(argument("seed", StringArgumentType.greedyString()).executes(c -> {
							OptionalLong seed = WorldOptions.parseSeed(StringArgumentType.getString(c, "seed"));
							if (seed.isEmpty()) {
								c.getSource().sendError(Component.literal("Not a seed"));
								return 0;
							}
							return set(c, seed.getAsLong(), "command");
						})))
				.then(LocateCommands.locate())
				.then(LocateCommands.waypoint())
				.then(literal("guess").then(literal("radius").then(argument("chunks", IntegerArgumentType.integer(4, 128)).executes(c -> {
					GuessWorld.radius = IntegerArgumentType.getInteger(c, "chunks");
					c.getSource().sendFeedback(Component.literal("Guessed terrain radius: " + GuessWorld.radius + " chunks"));
					return 1;
				})))));
	}

	private static int show(CommandContext<FabricClientCommandSource> c) {
		OptionalLong seed = SeedStore.seed();
		if (seed.isEmpty()) {
			c.getSource().sendFeedback(Component.literal("No seed known for this world. SeedcrackerX finds it, or use /plainsight seed <seed>."));
			return 0;
		}
		GuessWorld w = GuessWorld.current();
		c.getSource().sendFeedback(Component.literal("Seed " + SeedHider.show(seed.getAsLong()) + " (" + SeedStore.source() + ")"
				+ (w == null ? "" : ", guessed terrain: " + w.state().name().toLowerCase(java.util.Locale.ROOT) + ", " + w.guessedCount() + " chunks")));
		return 1;
	}

	private static int fromWorld(CommandContext<FabricClientCommandSource> c) {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			c.getSource().sendError(Component.literal("Only in singleplayer: elsewhere give the seed (or let SeedcrackerX find it)."));
			return 0;
		}
		return set(c, server.overworld().getSeed(), "world");
	}

	private static int set(CommandContext<FabricClientCommandSource> c, long seed, String source) {
		SeedStore.set(seed, source);
		String check = SeedStore.matchesServer(seed).map(m -> m ? " It matches the server's hashed seed." : " Warning: it does not match the server's hashed seed.").orElse("");
		c.getSource().sendFeedback(Component.literal("Seed set to " + SeedHider.show(seed) + "." + check + " The editor now shows guessed terrain (needs Voxy)."));
		return 1;
	}
}
