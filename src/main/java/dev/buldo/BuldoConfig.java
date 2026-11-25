package dev.buldo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Plain JSON config at config/buldo.json. */
public final class BuldoConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BuldoConfig instance;

	// Build speed, in blocks per second.
	public double blocksPerSecond = 10.0;
	// Hard limits.
	public int maxSites = 32;
	public int maxUndoFrames = 3;
	public int maxUndoBlocksPerFrame = 40000;

	// Behaviour.
	public boolean exactReplaceDefault = true;
	public boolean ghostDefault = true;
	public boolean hud = true;
	public boolean autoStartQueue = true;
	public boolean settleOnFinish = false;
	public boolean saveEvery200Blocks = true;

	// Blocks that never cost an item and are never taken from containers.
	public List<String> freeBlocks = new ArrayList<>(List.of(
			"minecraft:air",
			"minecraft:cave_air",
			"minecraft:void_air",
			"minecraft:structure_void",
			"minecraft:water",
			"minecraft:lava",
			"minecraft:fire",
			"minecraft:soul_fire",
			"minecraft:light",
			"minecraft:moving_piston",
			"minecraft:piston_head",
			"minecraft:bubble_column",
			"minecraft:nether_portal",
			"minecraft:end_portal",
			"minecraft:end_gateway",
			"minecraft:barrier"
	));

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("buldo.json");
	}

	public static BuldoConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static void reload() {
		instance = load();
	}

	private static BuldoConfig load() {
		Path path = file();
		BuldoConfig cfg = new BuldoConfig();
		if (Files.isRegularFile(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				BuldoConfig read = GSON.fromJson(json, BuldoConfig.class);
				if (read != null) {
					cfg = read;
				}
			} catch (Exception e) {
				Buldo.LOGGER.warn("Could not read {}, using defaults: {}", path, e.toString());
			}
		}
		cfg.sanitise();
		cfg.save();
		return cfg;
	}

	public void sanitise() {
		if (blocksPerSecond < 1) blocksPerSecond = 1;
		if (blocksPerSecond > 40) blocksPerSecond = 40;
		if (maxSites < 1) maxSites = 1;
		if (maxUndoFrames < 0) maxUndoFrames = 0;
		if (maxUndoFrames > 10) maxUndoFrames = 10;
		if (freeBlocks == null) freeBlocks = new ArrayList<>();
	}

	public void save() {
		Path path = file();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Buldo.LOGGER.warn("Could not write {}: {}", path, e.toString());
		}
	}

	public Set<String> freeBlockSet() {
		return new LinkedHashSet<>(freeBlocks);
	}
}
