import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Hot-swaps classes compiled since the last swap into the running dev client (JetBrains Runtime with
 * -XX:+AllowEnhancedClassRedefinition, so new methods and fields are fine).
 *
 *   ./dev ./gradlew classes clientClasses devbridgeClasses && ./dev java tools/Hotswap.java
 *
 * Only classes the game has already loaded are redefined; others load fresh when first used.
 */
public class Hotswap {
	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 5010;
		Path root = Path.of(System.getProperty("user.dir"));
		Path stamp = root.resolve("build/.hotswap-stamp-" + port);
		long since = Files.exists(stamp) ? Files.getLastModifiedTime(stamp).toMillis() : 0;
		long now = System.currentTimeMillis();
		Map<String, Path> changed = new HashMap<>();
		for (String dir : List.of("build/classes/java/main", "build/classes/java/devbridge", "infiniterra/build/classes/java/main")) {
			Path base = root.resolve(dir);
			if (!Files.isDirectory(base)) continue;
			try (Stream<Path> files = Files.walk(base)) {
				for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
					if (Files.getLastModifiedTime(f).toMillis() <= since) continue;
					String name = base.relativize(f).toString().replace('/', '.').replace('\\', '.');
					changed.put(name.substring(0, name.length() - ".class".length()), f);
				}
			}
		}
		if (changed.isEmpty()) {
			System.out.println("nothing changed since the last swap");
			return;
		}
		AttachingConnector conn = Bootstrap.virtualMachineManager().attachingConnectors().stream()
				.filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
		Map<String, Connector.Argument> a = conn.defaultArguments();
		a.get("hostname").setValue("127.0.0.1");
		a.get("port").setValue(Integer.toString(port));
		VirtualMachine vm = conn.attach(a);
		try {
			Map<ReferenceType, byte[]> redefine = new HashMap<>();
			int notLoaded = 0;
			for (var e : changed.entrySet()) {
				List<ReferenceType> types = vm.classesByName(e.getKey());
				if (types.isEmpty()) {
					notLoaded++;
					continue;
				}
				byte[] bytes = Files.readAllBytes(e.getValue());
				for (ReferenceType t : types) redefine.put(t, bytes);
			}
			if (!redefine.isEmpty()) vm.redefineClasses(redefine);
			System.out.println("hot-swapped " + redefine.size() + " class(es), " + notLoaded + " not loaded yet");
			Files.createDirectories(stamp.getParent());
			if (!Files.exists(stamp)) Files.createFile(stamp);
			Files.setLastModifiedTime(stamp, java.nio.file.attribute.FileTime.fromMillis(now));
		} finally {
			vm.dispose();
		}
	}
}
