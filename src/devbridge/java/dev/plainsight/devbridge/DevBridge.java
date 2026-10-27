package dev.plainsight.devbridge;

import com.mojang.blaze3d.platform.NativeImage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Development-only remote control for autonomous testing. Listens on 127.0.0.1 only
 * (port {@code PLAINSIGHT_BRIDGE_PORT}, default 25610). All game access happens on the render thread.
 *
 * <pre>
 * GET  /status                         screen, world, player position
 * POST /command?c=vehicle%20list       run a command as the player (without leading /)
 * POST /chat?t=hello                   send a chat message
 * GET  /chatlog?since=N                received chat/system lines (with indices)
 * GET  /screenshot?path=/abs/out.png   capture the framebuffer
 * POST /key?k=69&action=press|release|tap&mods=0      GLFW key code
 * POST /char?t=text                    type text into the focused widget
 * POST /mouse?x=..&y=..                move (window pixels)
 * POST /click?button=0&action=press|release|tap
 * POST /scroll?dy=1
 * POST /world?name=test&mode=creative  create (if missing) and open a flat world
 * POST /look?yaw=..&pitch=..           set the player's rotation
 * POST /tp?x=..&y=..&z=..              move the player (client side, for framing screenshots)
 * POST /static?class=a.B&method=m      call a public static no-argument method
 * POST /imgui/click?x=..&y=..&button=0  click in Axiom's ImGui (window pixels); also /imgui/move, /imgui/type?t=, /imgui/key?k=(ImGuiKey)
 * </pre>
 */
public final class DevBridge implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("plainsight-devbridge");
	private static final int MAX_LOG = 2000;
	private static final ArrayDeque<String> CHAT = new ArrayDeque<>();
	private static int chatBase;

	@Override
	public void onInitializeClient() {
		ClientReceiveMessageEvents.GAME.register((msg, overlay) -> log((overlay ? "[overlay] " : "") + msg.getString()));
		ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> log(msg.getString()));
		int port = Integer.parseInt(System.getenv().getOrDefault("PLAINSIGHT_BRIDGE_PORT", "25610"));
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
			server.createContext("/", this::handle);
			server.setExecutor(null);
			server.start();
			// its dispatcher thread is not a daemon: left running it keeps the JVM alive after the game quits
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> server.stop(0));
			LOG.info("Dev bridge listening on 127.0.0.1:{}", port);
		} catch (IOException e) {
			LOG.error("Dev bridge could not start on port {}", port, e);
		}
	}

	private static synchronized void log(String line) {
		CHAT.addLast(line);
		while (CHAT.size() > MAX_LOG) {
			CHAT.removeFirst();
			chatBase++;
		}
	}

	private static Map<String, String> query(HttpExchange ex) {
		Map<String, String> out = new HashMap<>();
		String q = ex.getRequestURI().getRawQuery();
		if (q == null) return out;
		for (String kv : q.split("&")) {
			int i = kv.indexOf('=');
			String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
			String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
			out.put(k, v);
		}
		return out;
	}

	/** Runs on the render thread and waits for the result. */
	private static <T> T onMain(Supplier<T> s) throws Exception {
		CompletableFuture<T> f = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				f.complete(s.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f.get(30, TimeUnit.SECONDS);
	}

	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		Map<String, String> q = query(ex);
		String body;
		int code = 200;
		try {
			body = switch (path) {
				case "/status" -> onMain(DevBridge::status);
				case "/command" -> onMain(() -> {
					var conn = Minecraft.getInstance().getConnection();
					if (conn == null) throw new IllegalStateException("not connected");
					String c = q.getOrDefault("c", "");
					conn.sendCommand(c.startsWith("/") ? c.substring(1) : c);
					return "ok";
				});
				case "/chat" -> onMain(() -> {
					var conn = Minecraft.getInstance().getConnection();
					if (conn == null) throw new IllegalStateException("not connected");
					conn.sendChat(q.getOrDefault("t", ""));
					return "ok";
				});
				case "/chatlog" -> chatlog(Integer.parseInt(q.getOrDefault("since", "0")));
				case "/screenshot" -> screenshot(q.getOrDefault("path", ""));
				case "/key" -> onMain(() -> key(Integer.parseInt(q.get("k")), q.getOrDefault("action", "tap"), Integer.parseInt(q.getOrDefault("mods", "0"))));
				case "/char" -> onMain(() -> {
					Minecraft mc = Minecraft.getInstance();
					long h = mc.getWindow().handle();
					q.getOrDefault("t", "").codePoints().forEach(cp -> mc.keyboardHandler.charTyped(h, new net.minecraft.client.input.CharacterEvent(cp)));
					return "ok";
				});
				case "/mouse" -> onMain(() -> {
					Minecraft mc = Minecraft.getInstance();
					double x = Double.parseDouble(q.get("x")), y = Double.parseDouble(q.get("y"));
					// the first move after start is swallowed by design; sending twice is harmless
					mc.mouseHandler.onMove(mc.getWindow().handle(), x, y, 0, 0);
					mc.mouseHandler.onMove(mc.getWindow().handle(), x, y, 0, 0);
					return "ok";
				});
				case "/click" -> onMain(() -> {
					Minecraft mc = Minecraft.getInstance();
					long h = mc.getWindow().handle();
					int b = Integer.parseInt(q.getOrDefault("button", "0"));
					String a = q.getOrDefault("action", "tap");
					MouseButtonInfo info = new MouseButtonInfo(b, 0);
					if (!a.equals("release")) mc.mouseHandler.onButton(h, info, 1);
					if (!a.equals("press")) mc.mouseHandler.onButton(h, info, 0);
					return "ok";
				});
				case "/guiclick" -> onMain(() -> guiClick(Double.parseDouble(q.get("x")), Double.parseDouble(q.get("y")), Integer.parseInt(q.getOrDefault("button", "0"))));
				case "/imgui/move" -> onMain(() -> imgui("addMousePosEvent", new Class<?>[]{float.class, float.class}, Float.parseFloat(q.get("x")), Float.parseFloat(q.get("y"))));
				case "/imgui/click" -> imguiClick(Float.parseFloat(q.get("x")), Float.parseFloat(q.get("y")), Integer.parseInt(q.getOrDefault("button", "0")));
				case "/imgui/drag" -> imguiDrag(Float.parseFloat(q.get("x1")), Float.parseFloat(q.get("y1")), Float.parseFloat(q.get("x2")), Float.parseFloat(q.get("y2")), Integer.parseInt(q.getOrDefault("button", "0")));
				case "/imgui/button" -> onMain(() -> imgui("addMouseButtonEvent", new Class<?>[]{int.class, boolean.class}, Integer.parseInt(q.getOrDefault("button", "0")), Boolean.parseBoolean(q.getOrDefault("down", "true"))));
				case "/imgui/keydown" -> onMain(() -> imgui("addKeyEvent", new Class<?>[]{int.class, boolean.class}, Integer.parseInt(q.get("k")), Boolean.parseBoolean(q.getOrDefault("down", "true"))));
				case "/imgui/type" -> onMain(() -> imgui("addInputCharactersUTF8", new Class<?>[]{String.class}, q.getOrDefault("t", "")));
				case "/imgui/key" -> imguiKey(Integer.parseInt(q.get("k")));
				case "/imgui/scroll" -> imguiScroll(Float.parseFloat(q.get("x")), Float.parseFloat(q.get("y")), Float.parseFloat(q.getOrDefault("dy", "-1")));
				case "/static" -> onMain(() -> invokeStatic(q.get("class"), q.get("method"), q.getOrDefault("args", "")));
				case "/hitboxes" -> onMain(() -> {
					boolean on = Minecraft.getInstance().debugEntries.toggleStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES);
					return on ? "on" : "off";
				});
				case "/scroll" -> onMain(() -> {
					Minecraft mc = Minecraft.getInstance();
					mc.mouseHandler.onScroll(mc.getWindow().handle(), 0, Double.parseDouble(q.getOrDefault("dy", "1")));
					return "ok";
				});
				case "/world" -> onMain(() -> world(q.getOrDefault("name", "plainsight-dev"), q.getOrDefault("mode", "creative")));
				case "/look" -> onMain(() -> {
					LocalPlayer p = player();
					p.setYRot(Float.parseFloat(q.getOrDefault("yaw", "0")));
					p.setXRot(Float.parseFloat(q.getOrDefault("pitch", "0")));
					return "ok";
				});
				case "/tp" -> onMain(() -> {
					LocalPlayer p = player();
					p.setPos(Double.parseDouble(q.get("x")), Double.parseDouble(q.get("y")), Double.parseDouble(q.get("z")));
					return "ok";
				});
				default -> {
					code = 404;
					yield "unknown endpoint";
				}
			};
		} catch (Exception e) {
			code = 500;
			Throwable c = e.getCause() != null ? e.getCause() : e;
			LOG.warn("Dev bridge request {} failed", path, c);
			body = c.toString();
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
		ex.sendResponseHeaders(code, bytes.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(bytes);
		}
	}

	/** Clicks the current screen directly at window pixel coordinates (bypasses the input pipeline). */
	private static String guiClick(double x, double y, int button) {
		Minecraft mc = Minecraft.getInstance();
		var screen = mc.gui.screen();
		if (screen == null) throw new IllegalStateException("no screen open");
		double scale = mc.getWindow().getGuiScale();
		var ev = new net.minecraft.client.input.MouseButtonEvent(x / scale, y / scale, new MouseButtonInfo(button, 0));
		boolean clicked = screen.mouseClicked(ev, false);
		screen.mouseReleased(ev);
		return clicked ? "clicked" : "nothing there";
	}

	/**
	 * Feeds Axiom's Dear ImGui context directly (its windows read input from their own windowing layer,
	 * not from Minecraft's input handlers). Looked up reflectively so the bridge does not depend on Axiom.
	 */
	private static String imgui(String method, Class<?>[] types, Object... args) {
		try {
			Object io = Class.forName("com.moulberry.axiom.editor.EditorUI").getMethod("getIO").invoke(null);
			if (io == null) throw new IllegalStateException("Axiom editor is not initialized");
			io.getClass().getMethod(method, types).invoke(io, args);
			return "ok";
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
		}
	}

	/** Press and release on different frames, as ImGui needs to see the button down for a frame. */
	private static String imguiClick(float x, float y, int button) throws Exception {
		onMain(() -> imgui("addMousePosEvent", new Class<?>[]{float.class, float.class}, x, y));
		Thread.sleep(80);
		onMain(() -> imgui("addMouseButtonEvent", new Class<?>[]{int.class, boolean.class}, button, true));
		Thread.sleep(80);
		return onMain(() -> imgui("addMouseButtonEvent", new Class<?>[]{int.class, boolean.class}, button, false));
	}

	private static String imguiDrag(float x1, float y1, float x2, float y2, int button) throws Exception {
		Class<?>[] pos = {float.class, float.class}, btn = {int.class, boolean.class};
		onMain(() -> imgui("addMousePosEvent", pos, x1, y1));
		Thread.sleep(60);
		onMain(() -> imgui("addMouseButtonEvent", btn, button, true));
		Thread.sleep(60);
		for (int i = 1; i <= 10; i++) {
			float t = i / 10f;
			onMain(() -> imgui("addMousePosEvent", pos, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t));
			Thread.sleep(40);
		}
		Thread.sleep(60);
		return onMain(() -> imgui("addMouseButtonEvent", btn, button, false));
	}

	/** Scrolls the ImGui window under (x, y): negative dy scrolls down. */
	private static String imguiScroll(float x, float y, float dy) throws Exception {
		onMain(() -> imgui("addMousePosEvent", new Class<?>[]{float.class, float.class}, x, y));
		Thread.sleep(60);
		return onMain(() -> imgui("addMouseWheelEvent", new Class<?>[]{float.class, float.class}, 0f, dy));
	}

	/** Presses an ImGuiKey (512..666, e.g. 526 = Escape, 525 = Enter); anything else trips a native assert that aborts the game. */
	private static String imguiKey(int key) throws Exception {
		if (key < 512 || key > 666) return "error: not an ImGuiKey (512..666; Escape is 526, Enter 525) - GLFW codes go to /key";
		onMain(() -> imgui("addKeyEvent", new Class<?>[]{int.class, boolean.class}, key, true));
		Thread.sleep(80);
		return onMain(() -> imgui("addKeyEvent", new Class<?>[]{int.class, boolean.class}, key, false));
	}

	/**
	 * Calls a public static method; {@code args} is a comma-separated list converted to the parameter types
	 * (String, int, long, float, double, boolean) of the overload with that many parameters.
	 */
	private static String invokeStatic(String cls, String method, String args) {
		try {
			Class<?> c = Class.forName(cls, true, DevBridge.class.getClassLoader());
			String[] parts = args.isEmpty() ? new String[0] : args.split(",", -1);
			for (java.lang.reflect.Method m : c.getMethods()) {
				if (!m.getName().equals(method) || m.getParameterCount() != parts.length || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
				Class<?>[] types = m.getParameterTypes();
				Object[] values = new Object[parts.length];
				for (int i = 0; i < parts.length; i++) {
					String v = parts[i].trim();
					Class<?> t = types[i];
					values[i] = t == int.class ? Integer.parseInt(v) : t == long.class ? Long.parseLong(v) : t == float.class ? Float.parseFloat(v)
							: t == double.class ? Double.parseDouble(v) : t == boolean.class ? Boolean.parseBoolean(v) : v;
				}
				return String.valueOf(m.invoke(null, values));
			}
			throw new NoSuchMethodException(cls + "." + method + " with " + parts.length + " parameters");
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
		}
	}

	private static LocalPlayer player() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) throw new IllegalStateException("no player");
		return p;
	}

	private static String status() {
		Minecraft mc = Minecraft.getInstance();
		StringBuilder sb = new StringBuilder();
		sb.append("screen=").append(mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName()).append('\n');
		sb.append("world=").append(mc.level == null ? "none" : mc.level.dimension().identifier()).append('\n');
		sb.append("window=").append(mc.getWindow().getWidth()).append('x').append(mc.getWindow().getHeight()).append('\n');
		if (mc.player != null) {
			sb.append("player=").append(mc.player.position()).append(" yaw=").append(mc.player.getYRot()).append(" pitch=").append(mc.player.getXRot()).append('\n');
		}
		sb.append("fps=").append(mc.getFps()).append('\n');
		sb.append("focused=").append(mc.isWindowActive()).append(" mouse=").append(mc.mouseHandler.xpos()).append(',').append(mc.mouseHandler.ypos())
				.append(" guiScale=").append(mc.getWindow().getGuiScale()).append('\n');
		return sb.toString();
	}

	private static synchronized String chatlog(int since) {
		StringBuilder sb = new StringBuilder();
		int i = chatBase;
		for (String l : CHAT) {
			if (i >= since) sb.append(i).append(": ").append(l).append('\n');
			i++;
		}
		sb.append("next=").append(chatBase + CHAT.size()).append('\n');
		return sb.toString();
	}

	private static String screenshot(String out) throws Exception {
		if (out.isEmpty()) throw new IllegalArgumentException("path required");
		Path file = Path.of(out);
		CompletableFuture<String> f = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), (NativeImage image) -> {
					try (image) {
						Files.createDirectories(file.toAbsolutePath().getParent());
						image.writeToFile(file);
						f.complete(file.toAbsolutePath().toString());
					} catch (Throwable t) {
						f.completeExceptionally(t);
					}
				});
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		return f.get(30, TimeUnit.SECONDS);
	}

	private static String key(int key, String action, int mods) {
		Minecraft mc = Minecraft.getInstance();
		long h = mc.getWindow().handle();
		KeyEvent ev = new KeyEvent(key, 0, mods);
		if (!action.equals("release")) mc.keyboardHandler.keyPress(h, 1, ev);
		if (!action.equals("press")) mc.keyboardHandler.keyPress(h, 0, ev);
		return "ok";
	}

	private static String world(String name, String mode) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) return "already in a world";
		try {
			if (mc.getLevelSource().levelExists(name)) {
				mc.createWorldOpenFlows().openWorld(name, () -> {});
				return "opening " + name;
			}
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		GameType type = mode.equals("survival") ? GameType.SURVIVAL : GameType.CREATIVE;
		LevelSettings settings = new LevelSettings(name, type, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(0, false, false),
				registries -> registries.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
						.getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), null);
		return "creating " + name;
	}
}
