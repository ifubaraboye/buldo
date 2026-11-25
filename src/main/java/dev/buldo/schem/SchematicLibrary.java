package dev.buldo.schem;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds schematic files on disk and caches the parsed blueprint until the file
 * changes on disk.
 */
public final class SchematicLibrary {
	private static final Set<String> EXTENSIONS = Set.of("schem", "nbt", "litematic");

	private record Cached(Blueprint blueprint, long modified, long size) {
	}

	private final List<Path> dirs;
	private final Map<String, Cached> cache = new LinkedHashMap<>();

	public SchematicLibrary(List<Path> dirs) {
		this.dirs = dirs;
	}

	public static SchematicLibrary forServer() {
		Path game = FabricLoader.getInstance().getGameDir();
		Path config = FabricLoader.getInstance().getConfigDir();
		List<Path> dirs = new ArrayList<>();
		dirs.add(game.resolve("schematics"));
		dirs.add(config.resolve("buldo").resolve("schematics"));
		return new SchematicLibrary(dirs);
	}

	public List<Path> dirs() {
		return dirs;
	}

	public Path importDir() {
		return dirs.get(dirs.size() - 1);
	}

	/** Sorted list of schematic names, without extension. */
	public List<String> names() {
		List<String> names = new ArrayList<>(scan().keySet());
		names.sort(Comparator.naturalOrder());
		return names;
	}

	public boolean exists(String name) {
		return find(name) != null;
	}

	public Path find(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String cleaned = name.trim();
		Path direct = Path.of(cleaned);
		if (direct.isAbsolute() && Files.isRegularFile(direct)) {
			return direct;
		}
		if (cleaned.startsWith("~")) {
			Path home = Path.of(System.getProperty("user.home"));
			Path expanded = home.resolve(cleaned.substring(1).replaceFirst("^[/\\\\]", ""));
			if (Files.isRegularFile(expanded)) {
				return expanded;
			}
		}
		Map<String, Path> found = scan();
		Path exact = found.get(cleaned);
		if (exact != null) {
			return exact;
		}
		String lower = cleaned.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Path> entry : found.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(lower)) {
				return entry.getValue();
			}
			if (entry.getValue().getFileName().toString().equalsIgnoreCase(cleaned)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private Map<String, Path> scan() {
		Map<String, Path> found = new LinkedHashMap<>();
		for (Path dir : dirs) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (var stream = Files.list(dir)) {
				stream.filter(Files::isRegularFile).sorted().forEach(path -> {
					String fileName = path.getFileName().toString();
					String lower = fileName.toLowerCase(Locale.ROOT);
					for (String extension : EXTENSIONS) {
						if (lower.endsWith("." + extension)) {
							String stem = fileName.substring(0, fileName.length() - extension.length() - 1);
							// First directory wins, so the Litematica folder takes priority.
							found.putIfAbsent(stem.toLowerCase(Locale.ROOT), path);
							break;
						}
					}
				});
			} catch (IOException ignored) {
				// Skip unreadable directories.
			}
		}
		return found;
	}

	public Blueprint load(String name) throws IOException {
		Path path = find(name);
		if (path == null) {
			throw new IOException("no schematic named '" + name + "' (looked in " + String.join(", ", dirs.stream().map(Path::toString).toList()) + ")");
		}
		long modified = Files.getLastModifiedTime(path).toMillis();
		long size = Files.size(path);
		Cached cached = cache.get(path.toString());
		if (cached != null && cached.modified == modified && cached.size == size) {
			return cached.blueprint;
		}
		String stem = stemOf(path);
		Blueprint blueprint = SchematicReader.read(path, stem);
		cache.put(path.toString(), new Cached(blueprint, modified, size));
		return blueprint;
	}

	/** Copies an external file into the writable schematic directory. Returns the new name. */
	public String importFile(String rawPath) throws IOException {
		Path source = Path.of(rawPath);
		if (rawPath.startsWith("~")) {
			source = Path.of(System.getProperty("user.home")).resolve(rawPath.substring(1).replaceFirst("^[/\\\\]", ""));
		}
		if (!Files.isRegularFile(source)) {
			throw new IOException("no file at " + rawPath);
		}
		String filename = source.getFileName().toString();
		String lower = filename.toLowerCase(Locale.ROOT);
		boolean supported = false;
		for (String extension : EXTENSIONS) {
			if (lower.endsWith("." + extension)) {
				supported = true;
				break;
			}
		}
		if (!supported) {
			throw new IOException("unsupported file type: " + filename);
		}
		Path dir = importDir();
		Files.createDirectories(dir);
		String base = slug(stemOf(source));
		String extension = filename.substring(filename.lastIndexOf('.'));
		String chosen = base;
		int counter = 2;
		while (Files.exists(dir.resolve(chosen + extension))) {
			chosen = base + "-" + counter++;
		}
		Files.copy(source, dir.resolve(chosen + extension), StandardCopyOption.COPY_ATTRIBUTES);
		return chosen;
	}

	public static String stemOf(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	public static String slug(String input) {
		String slug = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
		if (slug.isEmpty()) {
			slug = "schematic";
		}
		return slug.length() > 48 ? slug.substring(0, 48) : slug;
	}
}
