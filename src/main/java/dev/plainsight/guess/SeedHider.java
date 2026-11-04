package dev.plainsight.guess;

import dev.plainsight.Plainsight;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps world seeds off the screen (for screen sharing): while on, chat, the action bar and titles show
 * known seeds and any long number (9+ digits, like world, structure and hashed seeds from SeedcrackerX or
 * /seed) as dots, hover texts included; this mod shows its seeds the same way. Click actions (e.g. "copy
 * to clipboard") keep the real value. On by default; kept with the Hide Seeds module's settings
 * (config/plainsight/modules/hide_seeds.json; an older config/plainsight/privacy.properties is still read).
 */
public final class SeedHider {
	public static final String MASK = "••••••";
	private static final Pattern LONG_NUMBER = Pattern.compile("-?\\d{9,}");
	private static Boolean enabled;

	private SeedHider() {
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config/plainsight/privacy.properties");
	}

	public static synchronized boolean enabled() {
		if (enabled == null) {
			enabled = true;
			Path f = file();
			if (Files.exists(f)) {
				try (Reader r = Files.newBufferedReader(f)) {
					Properties p = new Properties();
					p.load(r);
					enabled = !"false".equals(p.getProperty("hideSeed"));
				} catch (IOException | IllegalArgumentException e) {
					Plainsight.LOG.warn("Could not read {}", f, e);
				}
			}
		}
		return enabled;
	}

	/** Kept with the Hide Seeds module's settings (see ModuleConfig). */
	public static synchronized void setEnabled(boolean on) {
		enabled = on;
	}

	/** How this mod shows a seed. */
	public static String show(long seed) {
		return enabled() ? MASK : Long.toString(seed);
	}

	public static String mask(String text) {
		if (text == null || !enabled() || !containsDigit(text)) return text;
		return mask(text, SeedStore.knownSeeds());
	}

	/** Shortest known seed that is masked: shorter ones are everyday numbers (and no secret). */
	private static final int MIN_KNOWN_SEED_DIGITS = 5;

	/**
	 * {@code text} with long numbers and the known {@code seeds} as dots; a seed's digits only count as
	 * the whole number, not as part of a decimal ("0.5") or of a longer one.
	 */
	static String mask(String text, long[] seeds) {
		String out = LONG_NUMBER.matcher(text).replaceAll(MASK);
		for (long seed : seeds) {
			String s = Long.toString(seed);
			if (s.replace("-", "").length() < MIN_KNOWN_SEED_DIGITS || !out.contains(s)) continue;
			out = out.replaceAll("(?<![\\d.-])" + Pattern.quote(s) + "(?!\\d|\\.\\d)", Matcher.quoteReplacement(MASK));
		}
		return out;
	}

	/** A copy of {@code c} with seeds masked (or {@code c} itself when nothing needs it). */
	public static Component mask(Component c) {
		if (c == null || !enabled() || !containsDigit(c.getString() + hoverText(c))) return c;
		return maskTree(c);
	}

	private static String hoverText(Component c) {
		StringBuilder sb = new StringBuilder();
		c.visit((style, text) -> {
			if (style.getHoverEvent() instanceof HoverEvent.ShowText show) sb.append(show.value().getString());
			return java.util.Optional.empty();
		}, Style.EMPTY);
		return sb.toString();
	}

	private static MutableComponent maskTree(Component c) {
		MutableComponent out;
		if (c.getContents() instanceof PlainTextContents.LiteralContents(String text)) {
			out = Component.literal(mask(text));
		} else if (c.getContents() instanceof TranslatableContents t) {
			Object[] args = t.getArgs().clone();
			for (int i = 0; i < args.length; i++) {
				if (args[i] instanceof Component a) args[i] = maskTree(a);
				else if (args[i] instanceof String s) args[i] = mask(s);
				else if (args[i] instanceof Number n) args[i] = mask(n.toString());
			}
			out = Component.translatableWithFallback(t.getKey(), t.getFallback(), args);
		} else {
			out = MutableComponent.create(c.getContents());
		}
		Style style = c.getStyle();
		if (style.getHoverEvent() instanceof HoverEvent.ShowText show) style = style.withHoverEvent(new HoverEvent.ShowText(maskTree(show.value())));
		out.setStyle(style);
		for (Component sibling : c.getSiblings()) out.append(maskTree(sibling));
		return out;
	}

	private static boolean containsDigit(String s) {
		for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
		return false;
	}
}
