package dev.plainsight.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.plainsight.command.ChatAlias;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Suggestions, usage and argument colours for the {@code #ps} chat alias ({@link ChatAlias}): the input is
 * parsed with the alias's dispatcher as the command branch of {@code updateCommandInfo} parses slash
 * commands. A lower priority than Baritone's mixin, so this runs (and cancels) before Baritone's own
 * {@code #} completion at the head of the same method.
 */
@Mixin(value = CommandSuggestions.class, priority = 900)
public abstract class CommandSuggestionsMixin {
	@Shadow @Final private EditBox input;
	@Shadow @Final private boolean onlyShowIfCursorPastError;
	@Shadow @Final private List<FormattedCharSequence> commandUsage;
	@Shadow private @Nullable ParseResults<ClientSuggestionProvider> currentParse;
	@Shadow private @Nullable CompletableFuture<Suggestions> pendingSuggestions;
	@Shadow private CommandSuggestions.@Nullable SuggestionsList suggestions;
	@Shadow private boolean currentParseIsCommand;
	@Shadow private boolean currentParseIsMessage;
	@Shadow private boolean keepSuggestions;

	@Shadow
	private void updateUsageInfo(ParseResults<ClientSuggestionProvider> currentParse, Suggestions suggestions) {
	}

	@Shadow
	private static boolean hasMessageArguments(ParseResults<ClientSuggestionProvider> parseResults) {
		return false;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
	private void plainsight$chatAlias(CallbackInfo ci) {
		String text = input.getValue();
		CommandDispatcher<FabricClientCommandSource> dispatcher = ChatAlias.dispatcher();
		FabricClientCommandSource source = ChatAlias.source();
		if (!ChatAlias.matches(text) || dispatcher == null || source == null) return;
		ci.cancel();

		if (currentParse != null && !currentParse.getReader().getString().equals(text)) currentParse = null;
		if (!keepSuggestions) {
			input.setSuggestion(null);
			suggestions = null;
		}
		commandUsage.clear();
		StringReader reader = new StringReader(text);
		if (currentParse == null) {
			currentParse = (ParseResults) dispatcher.parse(reader, source);
			currentParseIsCommand = true;
			currentParseIsMessage = hasMessageArguments(currentParse);
		}
		int cursor = input.getCursorPosition();
		int parseStart = onlyShowIfCursorPastError ? reader.getCursor() : 1;
		if (cursor >= parseStart && (suggestions == null || !keepSuggestions)) {
			ParseResults<ClientSuggestionProvider> parse = currentParse;
			CompletableFuture<Suggestions> pending = dispatcher.getCompletionSuggestions((ParseResults) parse, cursor);
			pendingSuggestions = pending;
			pending.thenAccept(result -> {
				if (pendingSuggestions == pending && pending.isDone()) updateUsageInfo(parse, result);
			});
		}
	}
}
