package dev.plainsight.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import org.jspecify.annotations.Nullable;

/**
 * The {@code #ps} chat alias of {@code /plainsight} (issue #47): {@code #ps seed} in chat runs
 * {@code /plainsight seed}, with the same suggestions while typing. It lives in its own dispatcher, so it
 * neither shows up among the slash commands nor reaches the server, and it is handled before Baritone, whose
 * commands also start with {@code #} (Baritone has no {@code ps} command; {@code #p...} stays Baritone's).
 */
public final class ChatAlias {
	public static final String PREFIX = "#ps";

	private static @Nullable CommandDispatcher<FabricClientCommandSource> dispatcher;

	private ChatAlias() {
	}

	public static void setTree(LiteralArgumentBuilder<FabricClientCommandSource> tree) {
		CommandDispatcher<FabricClientCommandSource> d = new CommandDispatcher<>();
		d.register(tree);
		dispatcher = d;
	}

	/** The dispatcher with the alias, or null before the commands were registered. */
	public static @Nullable CommandDispatcher<FabricClientCommandSource> dispatcher() {
		return dispatcher;
	}

	/** Whether chat input is meant for the alias: {@code #ps} alone or followed by a space. */
	public static boolean matches(String text) {
		return text.startsWith(PREFIX) && (text.length() == PREFIX.length() || text.charAt(PREFIX.length()) == ' ');
	}

	/** The source the alias runs with: the client's suggestion provider, which Fabric makes a client command source. */
	public static @Nullable FabricClientCommandSource source() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		return (FabricClientCommandSource) mc.player.connection.getSuggestionsProvider();
	}

	/** Runs a chat message the alias {@link #matches}; reports errors in chat like a slash command does. */
	public static void execute(String text) {
		CommandDispatcher<FabricClientCommandSource> d = dispatcher;
		FabricClientCommandSource source = source();
		if (d == null || source == null) return;
		try {
			ParseResults<FabricClientCommandSource> parse = d.parse(new StringReader(text), source);
			d.execute(parse);
		} catch (CommandSyntaxException e) {
			source.sendError(ComponentUtils.fromMessage(e.getRawMessage()));
			if (e.getInput() != null && e.getCursor() >= 0) {
				int cursor = Math.min(e.getInput().length(), e.getCursor());
				Component context = Component.empty().withStyle(ChatFormatting.GRAY)
						.append(Component.literal(e.getInput().substring(Math.max(0, cursor - 10), cursor)))
						.append(Component.literal(e.getInput().substring(cursor)).withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE))
						.append(Component.translatable("command.context.here").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
				source.sendError(context);
			}
		} catch (RuntimeException e) {
			source.sendError(Component.translatable("command.failed"));
			dev.plainsight.Plainsight.LOG.warn("{} failed", text, e);
		}
	}
}
