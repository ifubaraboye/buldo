package dev.buldo.site;

import dev.buldo.Buldo;
import dev.buldo.BuldoConfig;
import dev.buldo.plan.BuildPlan;
import dev.buldo.schem.Blueprint;
import dev.buldo.schem.SchematicLibrary;
import dev.buldo.util.Fmt;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns every build site for the running server and drives progress. */
public final class SiteManager {
	private static SiteManager current;

	private final MinecraftServer server;
	private final BuldoConfig config;
	private final SchematicLibrary library;
	private final Map<String, BuildSite> sites = new LinkedHashMap<>();
	private final List<String> queue = new ArrayList<>();
	private boolean dirty;
	private int tickCounter;
	private int saveCooldown;

	private SiteManager(MinecraftServer server) {
		this.server = server;
		this.config = BuldoConfig.get();
		this.library = SchematicLibrary.forServer();
	}

	public static void onServerStarted(MinecraftServer server) {
		current = new SiteManager(server);
		current.load();
		WorkerBuilder.discardOrphans(server, current.sites.keySet());
		Buldo.LOGGER.info("Buldo loaded {} site(s) from {}", current.sites.size(), current.sitesDir());
	}

	public static void onServerStopping(MinecraftServer server) {
		if (current != null) {
			current.save();
			WorkerBuilder.clearRuntime();
			current = null;
		}
	}

	public static void onBeforeSave(MinecraftServer server) {
		if (current != null && current.dirty) {
			current.save();
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (current != null) {
			current.tick();
		}
	}

	public static SiteManager current() {
		return current;
	}

	public BuldoConfig config() {
		return config;
	}

	public SchematicLibrary library() {
		return library;
	}

	public Collection<BuildSite> sites() {
		return sites.values();
	}

	public BuildSite get(String name) {
		if (name == null) {
			return null;
		}
		return sites.get(normalise(name));
	}

	public MinecraftServer server() {
		return server;
	}

	// ---------------------------------------------------------------- storage

	private Path rootDir() {
		return server.getWorldPath(LevelResource.ROOT).resolve("buldo");
	}

	private Path sitesDir() {
		return rootDir().resolve("sites");
	}

	private void load() {
		sites.clear();
		queue.clear();
		Path dir = sitesDir();
		HolderGetter<Block> blocks = blockGetter();
		if (Files.isDirectory(dir)) {
			try (var stream = Files.list(dir)) {
				for (Path path : stream.filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) {
					try {
						CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
						BuildSite site = BuildSite.fromTag(tag, blocks);
						if (site == null || site.name.isEmpty()) {
							continue;
						}
						if (site.state == SiteState.BUILDING) {
							// Never keep building across a restart without a fresh
							// go ahead from the player.
							site.state = SiteState.PAUSED;
						}
						sites.put(site.name, site);
					} catch (Exception e) {
						Buldo.LOGGER.warn("Could not read site {}: {}", path, e.toString());
					}
				}
			} catch (IOException e) {
				Buldo.LOGGER.warn("Could not list {}: {}", dir, e.toString());
			}
		}
		Path queueFile = rootDir().resolve("queue.nbt");
		if (Files.isRegularFile(queueFile)) {
			try {
				CompoundTag tag = NbtIo.readCompressed(queueFile, NbtAccounter.unlimitedHeap());
				ListTag list = tag.getListOrEmpty("queue");
				for (int i = 0; i < list.size(); i++) {
					String name = list.get(i).asString().orElse("");
					if (!name.isEmpty()) {
						queue.add(name);
					}
				}
			} catch (Exception e) {
				Buldo.LOGGER.warn("Could not read queue: {}", e.toString());
			}
		}
		queue.removeIf(name -> !sites.containsKey(name));
	}

	public void save() {
		try {
			Files.createDirectories(sitesDir());
			for (BuildSite site : sites.values()) {
				NbtIo.writeCompressed(site.toTag(), sitesDir().resolve(site.name + ".nbt"));
				site.placedSinceSave = 0;
			}
			CompoundTag queueTag = new CompoundTag();
			ListTag list = new ListTag();
			for (String name : queue) {
				list.add(StringTag.valueOf(name));
			}
			queueTag.put("queue", list);
			NbtIo.writeCompressed(queueTag, rootDir().resolve("queue.nbt"));
			dirty = false;
		} catch (Exception e) {
			Buldo.LOGGER.warn("Could not save sites: {}", e.toString());
		}
	}

	public void markDirty() {
		dirty = true;
	}

	// ---------------------------------------------------------------- helpers

	public HolderLookup<Block> blockLookup() {
		return server.registryAccess().lookupOrThrow(Registries.BLOCK);
	}

	private HolderGetter<Block> blockGetter() {
		return server.registryAccess().lookupOrThrow(Registries.BLOCK);
	}

	public ServerLevel level(BuildSite site) {
		Identifier id = Identifier.tryParse(site.dimension);
		if (id == null) {
			return null;
		}
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	public static String normalise(String name) {
		return SchematicLibrary.slug(name);
	}

	// ---------------------------------------------------------------- sites

	public BuildSite create(String rawName, String schematicArg, ServerLevel level, BlockPos origin,
			Rotation rotation, Mirror mirror, boolean exactReplace) throws IOException {
		String name = normalise(rawName);
		if (name.isEmpty()) {
			throw new IOException("invalid site name");
		}
		if (sites.containsKey(name)) {
			throw new IOException("a site named '" + name + "' already exists");
		}
		if (sites.size() >= config.maxSites) {
			throw new IOException("site limit reached (" + config.maxSites + ")");
		}
		Blueprint blueprint = library.load(schematicArg);
		BuildPlan plan = BuildPlan.build(blueprint, origin, rotation, mirror, exactReplace,
				blockLookup(), config.freeBlockSet(), level);
		if (plan.cells.isEmpty()) {
			throw new IOException("that schematic has no placeable blocks");
		}

		BuildSite site = new BuildSite();
		site.name = name;
		site.schematic = libraryName(schematicArg);
		site.dimension = level.dimension().identifier().toString();
		site.origin = origin;
		site.rotation = rotation;
		site.mirror = mirror;
		site.exactReplace = exactReplace;
		site.ghost = config.ghostDefault;
		site.mode = config.workerBuilding ? BuildMode.WORKERS : BuildMode.INSTANT;
		site.workers = config.workersPerSite;
		site.sourceHash = blueprint.hash;
		site.plan = plan;
		site.state = SiteState.READY;
		sites.put(name, site);
		dirty = true;
		save();
		return site;
	}

	private String libraryName(String schematicArg) {
		Path path = library.find(schematicArg);
		return path == null ? schematicArg : SchematicLibrary.stemOf(path);
	}

	public boolean remove(BuildSite site) {
		if (site.state == SiteState.BUILDING) {
			return false;
		}
		WorkerBuilder.dismiss(this, site);
		sites.remove(site.name);
		queue.remove(site.name);
		try {
			Files.deleteIfExists(sitesDir().resolve(site.name + ".nbt"));
		} catch (IOException e) {
			Buldo.LOGGER.warn("Could not delete site file: {}", e.toString());
		}
		dirty = true;
		save();
		return true;
	}

	public BuildPlan plan(BuildSite site) {
		if (site.plan != null) {
			return site.plan;
		}
		try {
			Blueprint blueprint = library.load(site.schematic);
			if (site.sourceHash != null && !site.sourceHash.isEmpty() && !site.sourceHash.equals(blueprint.hash)) {
				site.sourceHash = blueprint.hash;
				site.cursor = 0;
				site.placed = 0;
				site.undo.clear();
				site.state = SiteState.READY;
				site.lastError = "schematic changed on disk, progress was reset";
			}
			ServerLevel level = level(site);
			if (level == null) {
				level = server.overworld();
			}
			site.plan = BuildPlan.build(blueprint, site.origin, site.rotation, site.mirror,
					site.exactReplace, blockLookup(), config.freeBlockSet(), level);
			if (site.cursor > site.plan.cells.size()) {
				site.cursor = site.plan.cells.size();
			}
			if (site.state == SiteState.EMPTY) {
				site.state = SiteState.READY;
			}
			return site.plan;
		} catch (Exception e) {
			site.plan = null;
			site.state = SiteState.MISSING_SCHEMATIC;
			site.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
			return null;
		}
	}

	/** What is still missing to finish this site, by item. */
	public Map<Item, Integer> missingItems(BuildSite site, BuildPlan plan, ServerLevel level) {
		if (site.free) {
			return new LinkedHashMap<>();
		}
		Map<Item, Integer> need = new LinkedHashMap<>();
		// Workers place out of order, so every cell still has to be looked at.
		int from = site.mode == BuildMode.WORKERS ? 0 : Math.max(0, site.cursor);
		for (int i = from; i < plan.cells.size(); i++) {
			BuildPlan.Cell cell = plan.cells.get(i);
			if (cell.cost <= 0) {
				continue;
			}
			if (level.getBlockState(cell.pos).equals(cell.state)) {
				continue;
			}
			need.merge(cell.item, cell.cost, Integer::sum);
		}
		Map<Item, Integer> missing = new LinkedHashMap<>();
		for (Map.Entry<Item, Integer> entry : need.entrySet()) {
			int have = Containers.count(level, site.containers, entry.getKey());
			if (have < entry.getValue()) {
				missing.put(entry.getKey(), entry.getValue() - have);
			}
		}
		return missing;
	}

	public void storeMissing(BuildSite site, Map<Item, Integer> missing) {
		site.missing.clear();
		for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
			Identifier id = BuiltInRegistries.ITEM.getKey(entry.getKey());
			site.missing.put(id == null ? "unknown" : id.toString(), entry.getValue());
		}
		dirty = true;
	}

	public int haveItem(BuildSite site, Item item, ServerLevel level) {
		return Containers.count(level, site.containers, item);
	}

	// ---------------------------------------------------------------- lifecycle

	public boolean start(BuildSite site, boolean force) {
		BuildPlan plan = plan(site);
		if (plan == null) {
			return false;
		}
		if (site.state == SiteState.BUILDING) {
			return true;
		}
		ServerLevel level = level(site);
		if (level == null) {
			return false;
		}
		if (!force) {
			ServerPlayer inside = playerInside(level, plan);
			if (inside != null) {
				inside.sendSystemMessage(Fmt.err("You are standing inside '" + site.name
						+ "'. Step out (or use /buldo start " + site.name + " force)."));
				return false;
			}
		}
		if (!site.free && site.containers.isEmpty()) {
			notifyNear(site, Fmt.err("Site " + site.name + " has no linked containers."));
			return false;
		}
		Map<Item, Integer> missing = site.free ? new LinkedHashMap<>() : missingItems(site, plan, level);
		storeMissing(site, missing);
		site.startFrame(config.maxUndoFrames);
		site.carry = 0;
		if (!site.free && !missing.isEmpty() && !force) {
			site.state = SiteState.WAITING_MATERIALS;
			notifyNear(site, Fmt.warn("Site " + site.name + " is waiting for " + summarise(missing) + "."));
			save();
			return true;
		}
		site.state = SiteState.BUILDING;
		site.startedTick = level.getGameTime();
		site.lastError = "";
		site.missing.clear();
		if (site.mode == BuildMode.WORKERS) {
			WorkerBuilder.onStart(site);
		}
		dirty = true;
		save();
		notifyNear(site, site.free
				? Fmt.ok("Building " + site.name + " in free mode (" + plan.solidCells + " blocks, no items taken).")
				: Fmt.ok("Building " + site.name + " (" + plan.solidCells + " blocks, "
						+ Fmt.num(plan.cells.size()) + " cells, "
						+ (site.mode == BuildMode.WORKERS ? site.workers + " villager crew" : "instant") + ")."));
		return true;
	}

	public boolean pause(BuildSite site) {
		if (site.state != SiteState.BUILDING && site.state != SiteState.BLOCKED
				&& site.state != SiteState.WAITING_MATERIALS) {
			return false;
		}
		site.state = SiteState.PAUSED;
		WorkerBuilder.onPause(this, site);
		dirty = true;
		save();
		return true;
	}

	public boolean resume(BuildSite site) {
		if (site.state != SiteState.PAUSED && site.state != SiteState.BLOCKED
				&& site.state != SiteState.WAITING_MATERIALS && site.state != SiteState.READY
				&& site.state != SiteState.UNDONE) {
			return false;
		}
		return start(site, false);
	}

	public boolean cancel(BuildSite site) {
		if (!site.state.active()) {
			return false;
		}
		site.state = SiteState.PAUSED;
		site.carry = 0;
		WorkerBuilder.onPause(this, site);
		dirty = true;
		save();
		return true;
	}

	public void setMode(BuildSite site, BuildMode mode) {
		site.mode = mode;
		if (mode == BuildMode.INSTANT) {
			WorkerBuilder.dismiss(this, site);
		} else {
			WorkerBuilder.onStart(site);
		}
		dirty = true;
		save();
	}

	public void setWorkers(BuildSite site, int workers) {
		site.workers = Math.max(1, Math.min(8, workers));
		dirty = true;
		save();
	}

	public boolean undo(BuildSite site) {
		ServerLevel level = level(site);
		if (level == null) {
			return false;
		}
		while (!site.undo.isEmpty() && site.undo.get(site.undo.size() - 1).cells.isEmpty()) {
			site.undo.remove(site.undo.size() - 1);
		}
		if (site.undo.isEmpty()) {
			return false;
		}
		BuildSite.UndoFrame frame = site.undo.remove(site.undo.size() - 1);
		for (int i = frame.cells.size() - 1; i >= 0; i--) {
			BuildSite.UndoCell cell = frame.cells.get(i);
			level.setBlock(cell.pos(), cell.previous(), Block.UPDATE_CLIENTS);
			if (cell.previousBlockEntity() != null) {
				var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
						cell.pos(), cell.previous(), cell.previousBlockEntity(), level.registryAccess());
				if (restored != null) {
					level.setBlockEntity(restored);
				}
			}
			level.sendBlockUpdated(cell.pos(), cell.previous(), cell.previous(), Block.UPDATE_CLIENTS);
		}
		site.cursor = 0;
		site.placed = 0;
		site.skipped = 0;
		site.carry = 0;
		site.plan = null;
		site.state = SiteState.UNDONE;
		WorkerBuilder.dismiss(this, site);
		dirty = true;
		save();
		return true;
	}

	public List<String> queue() {
		return queue;
	}

	public boolean queueAdd(BuildSite site) {
		if (queue.contains(site.name)) {
			return false;
		}
		queue.add(site.name);
		dirty = true;
		save();
		return true;
	}

	public boolean queueRemove(String name) {
		boolean removed = queue.remove(normalise(name));
		if (removed) {
			dirty = true;
			save();
		}
		return removed;
	}

	public void queueClear() {
		queue.clear();
		dirty = true;
		save();
	}

	public void onSiteFinished(BuildSite site) {
		queue.remove(site.name);
		WorkerBuilder.dismiss(this, site);
		save();
		notifyNear(site, Fmt.ok("Finished " + site.name + " (" + site.placed + " blocks placed)."));
		if (!config.autoStartQueue) {
			return;
		}
		while (!queue.isEmpty()) {
			BuildSite next = sites.get(queue.get(0));
			if (next == null) {
				queue.remove(0);
				continue;
			}
			if (next.state == SiteState.DONE || next.state == SiteState.BUILDING) {
				queue.remove(0);
				continue;
			}
			notifyNear(next, Fmt.ok("Starting queued site " + next.name + "."));
			start(next, false);
			return;
		}
	}

	// ---------------------------------------------------------------- ticking

	private void tick() {
		tickCounter++;
		boolean saveNow = false;
		for (BuildSite site : new ArrayList<>(sites.values())) {
			if (site.state == SiteState.BUILDING) {
				if (site.mode == BuildMode.WORKERS) {
					WorkerBuilder.tick(this, site);
				} else {
					Builder.tick(this, site);
				}
				if (site.placedSinceSave >= 200 && config.saveEvery200Blocks) {
					saveNow = true;
				}
			}
			if (tickCounter % 20 == 0 && (site.state == SiteState.BLOCKED || site.state == SiteState.WAITING_MATERIALS)) {
				tryResume(site);
			}
			if (tickCounter % 10 == 0) {
				Ghost.tick(this, site);
			}
			if (tickCounter % 20 == 0) {
				Builder.sampleRate(site);
			}
			if (config.hud && tickCounter % 20 == 0) {
				hud(site);
			}
		}
		saveCooldown++;
		if (saveNow || (dirty && saveCooldown >= 600)) {
			save();
			saveCooldown = 0;
		}
	}

	private void tryResume(BuildSite site) {
		BuildPlan plan = plan(site);
		if (plan == null) {
			return;
		}
		ServerLevel level = level(site);
		if (level == null) {
			return;
		}
		if (site.free) {
			if (site.state == SiteState.WAITING_MATERIALS || site.state == SiteState.BLOCKED) {
				boolean wasWaiting = site.state == SiteState.WAITING_MATERIALS;
				site.state = SiteState.BUILDING;
				if (wasWaiting) {
					site.startedTick = level.getGameTime();
				}
				site.missing.clear();
				site.lastError = "";
				if (site.mode == BuildMode.WORKERS) {
					WorkerBuilder.onStart(site);
				}
				dirty = true;
				notifyNear(site, Fmt.ok("Free mode, building " + site.name + "."));
			}
			return;
		}
		if (site.containers.isEmpty()) {
			return;
		}
		Map<Item, Integer> missing = missingItems(site, plan, level);
		storeMissing(site, missing);
		if (missing.isEmpty()) {
			if (site.state == SiteState.WAITING_MATERIALS) {
				site.state = SiteState.BUILDING;
				site.startedTick = level.getGameTime();
				site.plan = plan;
				if (site.mode == BuildMode.WORKERS) {
					WorkerBuilder.onStart(site);
				}
				notifyNear(site, Fmt.ok("Materials ready, building " + site.name + "."));
			} else if (site.state == SiteState.BLOCKED) {
				site.state = SiteState.BUILDING;
				if (site.mode == BuildMode.WORKERS) {
					WorkerBuilder.onStart(site);
				}
				notifyNear(site, Fmt.ok("Materials resupplied, resuming " + site.name + "."));
			}
			dirty = true;
		}
	}

	private void hud(BuildSite site) {
		if (site.state != SiteState.BUILDING && site.state != SiteState.BLOCKED
				&& site.state != SiteState.WAITING_MATERIALS && site.state != SiteState.PAUSED) {
			return;
		}
		ServerLevel level = level(site);
		if (level == null) {
			return;
		}
		BuildPlan plan = site.plan;
		int total = plan == null || plan.cells.isEmpty() ? 1 : plan.cells.size();
		int percent = Math.min(100, (int) Math.round(100.0 * site.cursor / total));
		StringBuilder sb = new StringBuilder();
		sb.append("Buldo ").append(site.name).append(" · ").append(site.state.label()).append(" · ")
				.append(percent).append("% · ").append(Fmt.num(site.placed)).append('/')
				.append(Fmt.num(plan == null ? 0 : plan.solidCells)).append(" blocks");
		if (site.free) {
			sb.append(" · free");
		}
		if (site.mode == BuildMode.WORKERS) {
			sb.append(" · ").append(site.workers).append(" workers");
		}
		if (site.measuredRate > 0.05 && site.state == SiteState.BUILDING) {
			sb.append(String.format(Locale.ROOT, " · %.1f b/s", site.measuredRate));
		}
		if (site.state == SiteState.BLOCKED && !site.missing.isEmpty()) {
			int totalMissing = 0;
			for (int v : site.missing.values()) {
				totalMissing += v;
			}
			sb.append(" · missing ").append(Fmt.num(totalMissing)).append(" items");
		}
		Component bar = Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(site.origin.getX() + 0.5, site.origin.getY() + 0.5,
					site.origin.getZ() + 0.5) < 96 * 96) {
				player.sendSystemMessage(bar, true);
			}
		}
	}

	public void notifyNear(BuildSite site, Component component) {
		ServerLevel level = level(site);
		if (level == null) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(site.origin.getX() + 0.5, site.origin.getY() + 0.5,
					site.origin.getZ() + 0.5) < 128 * 128) {
				player.sendSystemMessage(component);
			}
		}
	}

	private static ServerPlayer playerInside(ServerLevel level, BuildPlan plan) {
		for (ServerPlayer player : level.players()) {
			BlockPos pos = player.blockPosition();
			if (pos.getX() >= plan.min.getX() && pos.getX() <= plan.max.getX()
					&& pos.getY() >= plan.min.getY() && pos.getY() <= plan.max.getY() + 1
					&& pos.getZ() >= plan.min.getZ() && pos.getZ() <= plan.max.getZ()) {
				return player;
			}
		}
		return null;
	}

	public static String summarise(Map<Item, Integer> missing) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
			if (shown++ == 3) {
				sb.append(", …");
				break;
			}
			if (shown > 1) {
				sb.append(", ");
			}
			sb.append(Fmt.num(entry.getValue())).append(' ').append(Fmt.item(entry.getKey()));
		}
		return sb.toString();
	}

	public Level overworldLevel() {
		return server.overworld();
	}
}
