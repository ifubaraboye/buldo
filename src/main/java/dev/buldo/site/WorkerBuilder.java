package dev.buldo.site;

import dev.buldo.Buldo;
import dev.buldo.plan.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The "realistic" builder: a small crew of villagers walks to the site and
 * places the blocks one by one, with a swing, a sound and a little dust.
 *
 * They are ordinary vanilla villagers with their brain stripped, so they have
 * no trades, no wandering and no schedule. Everything else (items, undo,
 * progress, blocking on missing materials) goes through {@link Placer}, the
 * same code the instant builder uses.
 */
final class WorkerBuilder {
	private static final String WORKER_TAG = "buldo_worker";
	private static final String SITE_TAG_PREFIX = "buldo_site:";
	private static final int ADOPT_RADIUS = 64;
	private static final int STUCK_TICKS = 20 * 8;
	private static final int HIGH_STUCK_TICKS = 20 * 3;
	private static final int SCAFFOLD_STEP_TICKS = 3;
	private static final int SCAFFOLD_MAX_HEIGHT = 24;
	private static final int SCAFFOLD_RADIUS = 4;

	private static final Map<String, Crew> CREWS = new HashMap<>();

	private static final class Crew {
		final Set<UUID> cleared = new HashSet<>();
		final Set<Integer> claimed = new HashSet<>();
		final Set<Integer> dead = new HashSet<>();
		final Map<UUID, Task> tasks = new HashMap<>();
		final Map<UUID, Integer> cooldowns = new HashMap<>();
		/** Each worker's own patch of the footprint, so the crew spreads out. */
		final Map<UUID, Integer> regions = new HashMap<>();
		/** Scaffolding tower each worker is standing on, if any. */
		final Map<UUID, Tower> towers = new HashMap<>();
		final Deque<Integer> retry = new ArrayDeque<>();
		final Set<Integer> retrySet = new HashSet<>();
		Set<Long> planPositions;
		BuildPlan positionsFor;
		boolean pickedUp;
		int scan;
		int spawnWait;
		boolean warned;
	}

	/**
	 * A column of scaffolding a worker builds under itself to reach high blocks,
	 * then takes down when it moves on. Temporary, so it is never part of the
	 * build progress or the undo history.
	 */
	private static final class Tower {
		int x;
		int z;
		int base;
		int top;
		int goal;
		int wait;
		int walk;
		int paid;
	}

	private static final class Task {
		int cell;
		BlockPos spot;
		int stuck;
		int repath;
		int bestDistance = Integer.MAX_VALUE;
		int failures;
		int fall;
		double lastY = Double.NaN;
	}

	private WorkerBuilder() {
	}

	// ---------------------------------------------------------------- lifecycle

	/** Called when a site starts or resumes. */
	static void onStart(BuildSite site) {
		Crew crew = CREWS.computeIfAbsent(site.name, key -> new Crew());
		crew.claimed.clear();
		crew.tasks.clear();
		crew.retry.clear();
		crew.retrySet.clear();
		crew.cooldowns.clear();
		crew.scan = 0;
		crew.dead.clear();
		crew.spawnWait = 0;
		crew.warned = false;
		crew.pickedUp = true;
		crew.planPositions = null;
		crew.positionsFor = null;
	}

	/** Called when a site is paused: stop walking, forget assignments. */
	static void onPause(SiteManager manager, BuildSite site) {
		Crew crew = CREWS.get(site.name);
		if (crew == null) {
			return;
		}
		crew.claimed.clear();
		crew.tasks.clear();
		crew.retry.clear();
		crew.retrySet.clear();
		crew.cooldowns.clear();
		ServerLevel level = manager.level(site);
		if (level != null) {
			for (Villager villager : findWorkers(level, site)) {
				villager.getNavigation().stop();
			}
			dismantleAll(level, site, crew);
		}
	}

	/** Remove the crew when a site is finished, undone, removed or cancelled. */
	static void dismiss(SiteManager manager, BuildSite site) {
		Crew crew = CREWS.remove(site.name);
		ServerLevel level = manager.level(site);
		if (level == null) {
			return;
		}
		if (crew != null) {
			dismantleAll(level, site, crew);
		}
		for (Villager villager : findWorkers(level, site)) {
			level.sendParticles(ParticleTypes.POOF, villager.getX(), villager.getY() + 1.0,
					villager.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
			villager.discard();
		}
	}

	/** Forget runtime state (the villagers themselves stay in the world). */
	static void clearRuntime() {
		CREWS.clear();
	}

	/**
	 * Removes crews whose site no longer exists, for example when a site file was
	 * deleted while the villagers were saved in the world.
	 */
	static void discardOrphans(MinecraftServer server, Collection<String> siteNames) {
		for (ServerLevel level : server.getAllLevels()) {
			AABB all = new AABB(-3.0e7, level.getMinY(), -3.0e7, 3.0e7, level.getMaxY() + 1, 3.0e7);
			for (Villager villager : level.getEntitiesOfClass(Villager.class, all)) {
				if (!villager.entityTags().contains(WORKER_TAG)) {
					continue;
				}
				boolean known = false;
				for (String tag : villager.entityTags()) {
					if (tag.startsWith(SITE_TAG_PREFIX)
							&& siteNames.contains(tag.substring(SITE_TAG_PREFIX.length()))) {
						known = true;
						break;
					}
				}
				if (!known) {
					villager.discard();
				}
			}
		}
	}

	// ---------------------------------------------------------------- ticking

	static void tick(SiteManager manager, BuildSite site) {
		ServerLevel level = manager.level(site);
		if (level == null) {
			site.state = SiteState.PAUSED;
			site.lastError = "dimension is not loaded";
			return;
		}
		BuildPlan plan = manager.plan(site);
		if (plan == null) {
			return;
		}
		Crew crew = CREWS.computeIfAbsent(site.name, key -> new Crew());
		List<Villager> live = sync(manager, site, level, plan, crew);
		advanceCursor(site, plan, level);
		if (!crew.pickedUp) {
			// Scaffolding from a previous run (server restart, crash) is never
			// part of the plan, so clear it out before the crew gets to work.
			crew.pickedUp = true;
			removeStrayScaffolds(level, plan, crew);
		}

		int desired = desiredWorkers(manager, site);
		int cooldown = Math.max(1, (int) Math.round(20.0 * desired
				/ Math.max(1.0, manager.config().blocksPerSecond)));

		for (Villager worker : live) {
			if (worker.isRemoved() || !worker.isAlive()) {
				continue;
			}
			try {
				rescue(site, level, crew, worker);
				tickWorker(manager, site, level, plan, crew, worker, cooldown, live.size());
			} catch (RuntimeException e) {
				Buldo.LOGGER.warn("Worker for {} failed a tick: {}", site.name, e.toString());
			}
			if (site.state != SiteState.BUILDING) {
				return;
			}
		}

		forgetMissingWorkers(manager, site, level, crew, live);
		advanceCursor(site, plan, level);
		if (site.cursor >= plan.cells.size()) {
			Builder.finish(manager, site, level, plan);
		}
	}

	private static void tickWorker(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan,
			Crew crew, Villager worker, int cooldown, int crewSize) {
		UUID id = worker.getUUID();
		int cool = crew.cooldowns.getOrDefault(id, 0);
		if (cool > 0) {
			crew.cooldowns.put(id, cool - 1);
			return;
		}

		Task task = crew.tasks.get(id);
		if (task == null) {
			task = assign(crew, plan, level, worker, crewSize);
			if (task == null) {
				worker.getNavigation().stop();
				return;
			}
			crew.tasks.put(id, task);
		}

		BuildPlan.Cell cell = plan.cells.get(task.cell);
		if (level.getBlockState(cell.pos).equals(cell.state)) {
			release(crew, id, task, false);
			return;
		}

		// Standing on scaffolding to reach a high block? Keep climbing or coming
		// down before any walking, otherwise gravity would drop them off.
		Tower tower = crew.towers.get(id);
		if (tower != null) {
			tower.goal = cell.pos.getY();
			if (towerTick(manager, site, level, plan, crew, worker, cell, tower)) {
				return;
			}
		}

		boolean overlapping = worker.getBoundingBox().intersects(
				new AABB(cell.pos.getX(), cell.pos.getY(), cell.pos.getZ(),
						cell.pos.getX() + 1.0, cell.pos.getY() + 1.0, cell.pos.getZ() + 1.0));
		double reach = Math.max(3.0, manager.config().workerReach);
		boolean inReach = !overlapping
				&& worker.getEyePosition().distanceTo(Vec3.atCenterOf(cell.pos)) <= reach;

		if (inReach) {
			worker.getLookControl().setLookAt(cell.pos.getX() + 0.5, cell.pos.getY() + 0.5,
					cell.pos.getZ() + 0.5);
			worker.getNavigation().stop();
			Placer.Result result = Placer.place(manager, site, level, plan, cell);
			worker.swing(InteractionHand.MAIN_HAND);
			if (result == Placer.Result.PLACED) {
				effect(level, cell);
				crew.cooldowns.put(id, cooldown);
				release(crew, id, task, false);
				manager.markDirty();
			} else if (result == Placer.Result.SKIPPED) {
				release(crew, id, task, false);
			} else if (result == Placer.Result.BLOCKED) {
				release(crew, id, task, true);
				Placer.block(manager, site, level, plan);
			} else {
				task.failures++;
				if (task.failures >= 4) {
					crew.dead.add(task.cell);
					release(crew, id, task, false);
					manager.notifyNear(site, net.minecraft.network.chat.Component.literal(
							"Buldo: could not place a block at " + format(cell.pos) + ", skipping it."));
				} else {
					release(crew, id, task, true);
				}
			}
			return;
		}

		// Walk towards a spot from which the block can be reached.
		double distance = worker.position().distanceTo(Vec3.atCenterOf(cell.pos));
		if (distance + 0.25 < task.bestDistance) {
			task.bestDistance = (int) distance;
			task.stuck = 0;
		} else {
			task.stuck++;
		}

		// Walking off the structure is not progress: if the worker drops, look
		// for a way back up at once instead of pushing into the wall below.
		if (!Double.isNaN(task.lastY) && worker.getY() < task.lastY - 0.35) {
			if (++task.fall >= 3) {
				task.fall = 0;
				task.spot = null;
				task.repath = 0;
				task.stuck += 60;
			}
		} else {
			task.fall = 0;
		}
		task.lastY = worker.getY();

		if (task.spot == null || task.repath-- <= 0) {
			task.spot = findSpot(level, cell.pos, worker.position());
			task.repath = 50;
			if (task.spot != null) {
				PathNavigation navigation = worker.getNavigation();
				navigation.moveTo(task.spot.getX() + 0.5, task.spot.getY(), task.spot.getZ() + 0.5, 0.65);
			}
		}

		// Nothing to stand on anywhere near the block and it is well above the
		// worker: this is a scaffolding job, exactly like a real build.
		if (task.spot == null && !crew.towers.containsKey(id) && cell.pos.getY() - worker.getY() > 2.0
				&& startTower(manager, site, level, plan, crew, worker, cell)) {
			return;
		}

		if (worker.getNavigation().isStuck()) {
			task.spot = null;
			task.repath = 0;
			task.stuck += 20;
		}

		// The pathfinder can give up on roofs and odd shapes. Rather than stall
		// the whole site, let a worker place from a little farther away, and as a
		// last resort move them next to the block.
		boolean high = cell.pos.getY() - worker.getY() > 2.0;
		if (task.stuck > (high ? HIGH_STUCK_TICKS : STUCK_TICKS)) {
			if (!high && distance <= 24) {
				worker.getLookControl().setLookAt(cell.pos.getX() + 0.5, cell.pos.getY() + 0.5,
						cell.pos.getZ() + 0.5);
				Placer.Result result = Placer.place(manager, site, level, plan, cell);
				worker.swing(InteractionHand.MAIN_HAND);
				if (result == Placer.Result.PLACED) {
					effect(level, cell);
					crew.cooldowns.put(id, cooldown);
					release(crew, id, task, false);
					manager.markDirty();
				} else if (result == Placer.Result.BLOCKED) {
					release(crew, id, task, true);
					Placer.block(manager, site, level, plan);
				} else if (result == Placer.Result.SKIPPED) {
					release(crew, id, task, false);
				}
			} else {
				// Too high to reach from where it stands: put up scaffolding and
				// climb it instead of placing a block out of thin air.
				boolean climbing = false;
				if (crew.towers.containsKey(id)) {
					// The tower did not get it there: take it down and fall back.
					dismantle(level, site, crew, id);
				} else if (startTower(manager, site, level, plan, crew, worker, cell)) {
					climbing = true;
				}
				if (!climbing) {
					BlockPos near = findSpot(level, cell.pos, worker.position());
					if (near == null) {
						near = findSpawn(level, cell.pos);
					}
					if (near != null) {
						worker.getNavigation().stop();
						worker.teleportTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5);
					}
				}
				task.stuck = 0;
				task.spot = null;
				task.lastY = worker.getY();
			}
		}
	}

	// ---------------------------------------------------------------- scaffolding

	/**
	 * Builds a scaffolding tower under the worker so it can reach a block that
	 * has nothing to stand on. Only used when there is genuinely no floor near
	 * the block; materials come from the site like everything else, and the
	 * tower is taken down again when the worker moves on.
	 */
	private static boolean startTower(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan,
			Crew crew, Villager worker, BuildPlan.Cell cell) {
		UUID id = worker.getUUID();
		if (crew.towers.containsKey(id)) {
			return true;
		}
		if (cell.pos.getY() - worker.getY() <= 1.5) {
			return false;
		}
		if (!site.free && Containers.count(level, site.containers, Items.SCAFFOLDING) <= 0) {
			return false;
		}
		BlockPos ground = findTowerColumn(level, plan, crew, cell.pos,
				Math.max(3.0, manager.config().workerReach));
		if (ground == null) {
			return false;
		}
		Tower tower = new Tower();
		tower.x = ground.getX();
		tower.z = ground.getZ();
		tower.base = ground.getY();
		tower.top = tower.base - 1;
		tower.goal = cell.pos.getY();
		crew.towers.put(id, tower);
		return true;
	}

	/** One tick of putting up, climbing, or taking down scaffolding. */
	private static boolean towerTick(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan,
			Crew crew, Villager worker, BuildPlan.Cell cell, Tower tower) {
		if (tower == null) {
			return false;
		}
		UUID id = worker.getUUID();
		double reach = Math.max(3.0, manager.config().workerReach);
		if (Math.hypot(cell.pos.getX() - tower.x, cell.pos.getZ() - tower.z) > reach - 0.5
				|| cell.pos.getY() < tower.base) {
			dismantle(level, site, crew, id, worker);
			return false;
		}
		if (tower.wait > 0) {
			tower.wait--;
			return true;
		}

		int feet = (int) Math.floor(worker.getY() + 0.05);
		double dx = worker.getX() - (tower.x + 0.5);
		double dz = worker.getZ() - (tower.z + 0.5);
		if (dx * dx + dz * dz > 2.56) {
			if (tower.top >= tower.base) {
				// It got off the tower and cannot walk back up: pull it down.
				dismantle(level, site, crew, id, worker);
				return false;
			}
			tower.walk++;
			if (tower.walk > 200) {
				dismantle(level, site, crew, id, worker);
				return false;
			}
			worker.getNavigation().moveTo(tower.x + 0.5, tower.base, tower.z + 0.5, 0.7);
			return true;
		}
		tower.walk = 0;
		worker.getNavigation().stop();

		if (feet < tower.goal) {
			BlockPos place = new BlockPos(tower.x, feet, tower.z);
			if (!towerFree(level, plan, crew, place) || tower.top - tower.base >= SCAFFOLD_MAX_HEIGHT) {
				dismantle(level, site, crew, id, worker);
				return false;
			}
			if (!site.free) {
				if (Containers.withdraw(level, site.containers, Items.SCAFFOLDING, 1) < 1) {
					dismantle(level, site, crew, id, worker);
					return false;
				}
				tower.paid++;
			}
			level.setBlock(place, scaffoldState(), Block.UPDATE_CLIENTS);
			if (feet > tower.top) {
				tower.top = feet;
			}
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, scaffoldState()),
					tower.x + 0.5, feet + 0.5, tower.z + 0.5, 4, 0.25, 0.25, 0.25, 0.01);
			level.playSound(null, place, Blocks.SCAFFOLDING.defaultBlockState().getSoundType().getPlaceSound(),
					SoundSource.BLOCKS, 0.6f, 1.0f);
			worker.teleportTo(tower.x + 0.5, feet + 1, tower.z + 0.5);
			worker.swing(InteractionHand.MAIN_HAND);
			tower.wait = SCAFFOLD_STEP_TICKS;
			return true;
		}

		if (feet > tower.goal) {
			BlockPos under = new BlockPos(tower.x, feet - 1, tower.z);
			if (!level.getBlockState(under).is(Blocks.SCAFFOLDING)) {
				dismantle(level, site, crew, id, worker);
				return false;
			}
			level.setBlock(under, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			returnPaid(site, level, tower);
			tower.top = Math.min(tower.top, feet - 2);
			worker.teleportTo(tower.x + 0.5, feet - 1, tower.z + 0.5);
			tower.wait = 2;
			return true;
		}
		return false;
	}

	/** Take a whole tower down and give the materials back. */
	private static void dismantle(ServerLevel level, BuildSite site, Crew crew, UUID id, Villager worker) {
		Tower tower = crew.towers.get(id);
		if (tower != null && worker.getY() > tower.base + 0.2) {
			worker.getNavigation().stop();
			worker.teleportTo(tower.x + 0.5, tower.base, tower.z + 0.5);
		}
		dismantle(level, site, crew, id);
	}

	private static void dismantle(ServerLevel level, BuildSite site, Crew crew, UUID id) {
		Tower tower = crew.towers.remove(id);
		if (tower == null) {
			return;
		}
		for (int y = tower.top; y >= tower.base; y--) {
			BlockPos pos = new BlockPos(tower.x, y, tower.z);
			if (!level.getBlockState(pos).is(Blocks.SCAFFOLDING)) {
				continue;
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			returnPaid(site, level, tower);
		}
	}

	private static void dismantleAll(ServerLevel level, BuildSite site, Crew crew) {
		for (UUID id : new ArrayList<>(crew.towers.keySet())) {
			dismantle(level, site, crew, id);
		}
	}

	private static void returnPaid(BuildSite site, ServerLevel level, Tower tower) {
		if (!site.free && tower.paid > 0) {
			Containers.deposit(level, site.containers, Items.SCAFFOLDING, 1);
			tower.paid--;
		}
	}

	/** A column just beside the block with room for scaffolding all the way up. */
	private static BlockPos findTowerColumn(ServerLevel level, BuildPlan plan, Crew crew, BlockPos cell,
			double reach) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int radius = 1; radius <= SCAFFOLD_RADIUS; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
						continue;
					}
					if (Math.hypot(dx, dz) > reach - 0.75) {
						continue;
					}
					BlockPos ground = groundBelow(level, cell.getX() + dx, cell.getZ() + dz,
							cell.getY() - 1);
					if (ground == null) {
						continue;
					}
					int height = cell.getY() - ground.getY();
					if (height <= 0 || height > SCAFFOLD_MAX_HEIGHT) {
						continue;
					}
					if (!columnClear(level, plan, crew, ground.getX(), ground.getY(), cell.getY() - 1,
							ground.getZ())) {
						continue;
					}
					double distance = Math.hypot(dx, dz);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = ground;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	private static BlockPos groundBelow(ServerLevel level, int x, int z, int fromY) {
		int bottom = Math.max(level.getMinY() + 1, fromY - SCAFFOLD_MAX_HEIGHT - 2);
		for (int y = fromY; y >= bottom; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (standable(level, pos)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean columnClear(ServerLevel level, BuildPlan plan, Crew crew, int x, int fromY,
			int toY, int z) {
		Set<Long> planned = planPositions(crew, plan);
		for (int y = fromY; y <= toY; y++) {
			BlockPos pos = new BlockPos(x, y, z);
			if (planned.contains(pos.asLong())) {
				return false;
			}
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static boolean towerFree(ServerLevel level, BuildPlan plan, Crew crew, BlockPos pos) {
		if (planPositions(crew, plan).contains(pos.asLong())) {
			return false;
		}
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static Set<Long> planPositions(Crew crew, BuildPlan plan) {
		if (crew.positionsFor != plan || crew.planPositions == null) {
			Set<Long> positions = new HashSet<>(Math.max(16, plan.cells.size() * 2));
			for (BuildPlan.Cell cell : plan.cells) {
				positions.add(cell.pos.asLong());
			}
			crew.planPositions = positions;
			crew.positionsFor = plan;
		}
		return crew.planPositions;
	}

	/** Scaffolding left behind by an earlier run is never part of the plan. */
	private static void removeStrayScaffolds(ServerLevel level, BuildPlan plan, Crew crew) {
		int pad = SCAFFOLD_RADIUS + 2;
		int bottom = Math.max(level.getMinY() + 1, plan.min.getY() - 1);
		int top = Math.min(level.getMaxY(), plan.max.getY() + 2);
		Set<Long> planned = planPositions(crew, plan);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int removed = 0;
		for (int x = plan.min.getX() - pad; x <= plan.max.getX() + pad; x++) {
			for (int z = plan.min.getZ() - pad; z <= plan.max.getZ() + pad; z++) {
				for (int y = bottom; y <= top; y++) {
					pos.set(x, y, z);
					if (!level.getBlockState(pos).is(Blocks.SCAFFOLDING) || planned.contains(pos.asLong())) {
						continue;
					}
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					removed++;
				}
			}
		}
		if (removed > 0) {
			Buldo.LOGGER.info("Removed {} leftover scaffolding blocks", removed);
		}
	}

	private static BlockState scaffoldState() {
		return Blocks.SCAFFOLDING.defaultBlockState()
				.setValue(ScaffoldingBlock.BOTTOM, true)
				.setValue(ScaffoldingBlock.DISTANCE, 0);
	}

	// ---------------------------------------------------------------- rescue

	/**
	 * A worker that fell out of the world or walked far off the site is put back
	 * on solid ground next to the build. Villagers are invulnerable, so falling
	 * itself never hurts them; this is about not stranding them.
	 */
	private static void rescue(BuildSite site, ServerLevel level, Crew crew, Villager worker) {
		boolean belowWorld = worker.getY() < level.getMinY() + 0.5;
		boolean lost = !area(level, site).contains(worker.position());
		if (!belowWorld && !lost) {
			return;
		}
		BuildPlan plan = site.plan;
		if (plan == null) {
			return;
		}
		BlockPos spot = findCrewSpot(level, plan);
		if (spot == null) {
			return;
		}
		worker.getNavigation().stop();
		worker.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		Task task = crew.tasks.get(worker.getUUID());
		if (task != null) {
			task.stuck = 0;
			task.fall = 0;
			task.spot = null;
			task.repath = 0;
			task.lastY = worker.getY();
		}
	}

	// ---------------------------------------------------------------- assignment

	/**
	 * Picks the next block for a worker. Each worker owns a patch of the
	 * footprint and works it from the ground up, so eight villagers raise eight
	 * different parts of the house instead of all placing the same wall.
	 */
	private static Task assign(Crew crew, BuildPlan plan, ServerLevel level, Villager worker, int crewSize) {
		Integer index = pollRetry(crew, plan, level);
		if (index == null) {
			index = regionScan(crew, plan, level, region(crew, worker), crewSize);
		}
		if (index == null) {
			// This worker's patch is done (or empty): lend a hand anywhere else.
			index = scan(crew, plan, level);
		}
		if (index == null) {
			return null;
		}
		crew.claimed.add(index);
		Task task = new Task();
		task.cell = index;
		return task;
	}

	/** Stable per-worker patch number, reused when a slot is freed. */
	private static int region(Crew crew, Villager worker) {
		Integer known = crew.regions.get(worker.getUUID());
		if (known != null) {
			return known;
		}
		int slot = 0;
		while (crew.regions.containsValue(slot)) {
			slot++;
		}
		crew.regions.put(worker.getUUID(), slot);
		return slot;
	}

	/**
	 * Split the footprint into a grid of roughly square patches, one per worker,
	 * and take the lowest unbuilt block inside this worker's patch.
	 */
	private static Integer regionScan(Crew crew, BuildPlan plan, ServerLevel level, int slot, int crewSize) {
		int count = Math.max(1, crewSize);
		int width = plan.max.getX() - plan.min.getX() + 1;
		int depth = plan.max.getZ() - plan.min.getZ() + 1;
		if (width <= 0 || depth <= 0) {
			return null;
		}
		int columns = count <= 1 ? 1
				: (int) Math.max(1, Math.min(count, Math.round(Math.sqrt((double) count * width / depth))));
		int rows = (int) Math.ceil((double) count / columns);
		if (slot < 0 || slot >= columns * rows) {
			return null;
		}
		int row = slot / columns;
		int column = slot % columns;
		int minX = plan.min.getX();
		int minZ = plan.min.getZ();
		for (int i = 0; i < plan.cells.size(); i++) {
			if (crew.claimed.contains(i) || crew.dead.contains(i)) {
				continue;
			}
			BuildPlan.Cell cell = plan.cells.get(i);
			int cx = Math.min(columns - 1, Math.max(0, (cell.pos.getX() - minX) * columns / width));
			int cz = Math.min(rows - 1, Math.max(0, (cell.pos.getZ() - minZ) * rows / depth));
			if (cx != column || cz != row) {
				continue;
			}
			if (level.getBlockState(cell.pos).equals(cell.state)) {
				continue;
			}
			return i;
		}
		return null;
	}

	private static Integer pollRetry(Crew crew, BuildPlan plan, ServerLevel level) {
		while (!crew.retry.isEmpty()) {
			int index = crew.retry.pollFirst();
			crew.retrySet.remove(index);
			if (index < 0 || index >= plan.cells.size() || crew.claimed.contains(index)
					|| crew.dead.contains(index)) {
				continue;
			}
			BuildPlan.Cell cell = plan.cells.get(index);
			if (level.getBlockState(cell.pos).equals(cell.state)) {
				continue;
			}
			return index;
		}
		return null;
	}

	private static Integer scan(Crew crew, BuildPlan plan, ServerLevel level) {
		while (crew.scan < plan.cells.size()) {
			int index = crew.scan++;
			if (crew.claimed.contains(index) || crew.dead.contains(index)) {
				continue;
			}
			BuildPlan.Cell cell = plan.cells.get(index);
			if (level.getBlockState(cell.pos).equals(cell.state)) {
				continue;
			}
			return index;
		}
		return null;
	}

	private static void release(Crew crew, UUID worker, Task task, boolean retry) {
		crew.tasks.remove(worker);
		crew.claimed.remove(task.cell);
		if (retry && !crew.dead.contains(task.cell) && crew.retrySet.add(task.cell)) {
			crew.retry.addLast(task.cell);
		}
	}

	private static void forgetMissingWorkers(SiteManager manager, BuildSite site, ServerLevel level, Crew crew,
			List<Villager> live) {
		if (crew.tasks.isEmpty() && crew.towers.isEmpty()) {
			return;
		}
		Set<UUID> alive = new HashSet<>();
		for (Villager villager : live) {
			alive.add(villager.getUUID());
		}
		Iterator<Map.Entry<UUID, Task>> iterator = crew.tasks.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Task> entry = iterator.next();
			if (alive.contains(entry.getKey())) {
				continue;
			}
			crew.claimed.remove(entry.getValue().cell);
			crew.regions.remove(entry.getKey());
			dismantle(level, site, crew, entry.getKey());
			iterator.remove();
		}
		for (UUID id : new ArrayList<>(crew.towers.keySet())) {
			if (!alive.contains(id)) {
				dismantle(level, site, crew, id);
			}
		}
	}

	private static void advanceCursor(BuildSite site, BuildPlan plan, ServerLevel level) {
		while (site.cursor < plan.cells.size()) {
			BuildPlan.Cell cell = plan.cells.get(site.cursor);
			if (!level.getBlockState(cell.pos).equals(cell.state)) {
				break;
			}
			site.cursor++;
			site.skipped++;
		}
	}

	// ---------------------------------------------------------------- crew

	private static int desiredWorkers(SiteManager manager, BuildSite site) {
		return Math.max(1, Math.min(site.workers, manager.config().maxWorkersPerSite));
	}

	private static List<Villager> sync(SiteManager manager, BuildSite site, ServerLevel level,
			BuildPlan plan, Crew crew) {
		List<Villager> live = findWorkers(level, site);
		for (Villager villager : live) {
			if (crew.cleared.add(villager.getUUID())) {
				prepare(villager);
			}
		}
		int desired = desiredWorkers(manager, site);
		if (live.size() < desired) {
			if (crew.spawnWait > 0) {
				crew.spawnWait--;
			} else {
				int before = live.size();
				for (int i = live.size(); i < desired; i++) {
					Villager spawned = spawn(level, plan, site, i);
					if (spawned == null) {
						break;
					}
					live.add(spawned);
					crew.cleared.add(spawned.getUUID());
				}
				if (live.size() == before) {
					// Do not re-run an expensive spot search every single tick.
					crew.spawnWait = 40;
					if (!crew.warned) {
						crew.warned = true;
						manager.notifyNear(site, Component.literal(
								"Buldo: no room for the crew near '" + site.name
										+ "', start it somewhere with ground around it."));
						Buldo.LOGGER.warn("No crew spawn spot found for site {}", site.name);
					}
				}
			}
		} else if (live.size() > desired) {
			for (int i = desired; i < live.size(); i++) {
				level.sendParticles(ParticleTypes.POOF, live.get(i).getX(), live.get(i).getY() + 1.0,
						live.get(i).getZ(), 6, 0.3, 0.4, 0.3, 0.02);
				UUID id = live.get(i).getUUID();
				Task task = crew.tasks.remove(id);
				if (task != null) {
					crew.claimed.remove(task.cell);
				}
				crew.regions.remove(id);
				crew.cooldowns.remove(id);
				dismantle(level, site, crew, id);
				live.get(i).discard();
			}
			live = live.subList(0, desired);
		}
		return live;
	}

	private static void prepare(Villager villager) {
		villager.setPersistenceRequired();
		villager.setInvulnerable(true);
		villager.setCustomName(Component.literal("Builder"));
		villager.setCustomNameVisible(false);
		villager.setSilent(true);
		villager.setVillagerDataFinalized(true);
		villager.getBrain().removeAllBehaviors();
	}

	private static Villager spawn(ServerLevel level, BuildPlan plan, BuildSite site, int index) {
		BlockPos pos = findCrewSpot(level, plan);
		if (pos == null) {
			return null;
		}
		Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
		if (villager == null) {
			return null;
		}
		double angle = (index * 1.7) + 0.4;
		double x = pos.getX() + 0.5 + Math.cos(angle) * 0.9;
		double z = pos.getZ() + 0.5 + Math.sin(angle) * 0.9;
		villager.setPos(x, pos.getY(), z);
		villager.setYRot((float) (-angle * 180.0 / Math.PI) + 90.0f);
		villager.setXRot(0);
		villager.setInvulnerable(true);
		prepare(villager);
		villager.addTag(WORKER_TAG);
		villager.addTag(siteTag(site.name));
		if (!level.addFreshEntity(villager)) {
			villager.discard();
			return null;
		}
		level.sendParticles(ParticleTypes.POOF, x, pos.getY() + 1.0, z, 10, 0.3, 0.5, 0.3, 0.02);
		return villager;
	}

	/**
	 * Spawn positions are found from the site plan. The entity carries the site
	 * tag so it is adopted again after a restart.
	 */
	private static void effect(ServerLevel level, BuildPlan.Cell cell) {
		if (cell.state.isAir()) {
			return;
		}
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, cell.state),
				cell.pos.getX() + 0.5, cell.pos.getY() + 0.5, cell.pos.getZ() + 0.5,
				4, 0.25, 0.25, 0.25, 0.01);
		level.playSound(null, cell.pos, cell.state.getSoundType().getPlaceSound(),
				SoundSource.BLOCKS, 0.7f, 0.9f + level.getRandom().nextFloat() * 0.2f);
	}

	private static List<Villager> findWorkers(ServerLevel level, BuildSite site) {
		List<Villager> result = new ArrayList<>();
		for (Villager villager : level.getEntitiesOfClass(Villager.class, area(level, site))) {
			if (villager.entityTags().contains(siteTag(site.name))) {
				result.add(villager);
			}
		}
		return result;
	}

	private static AABB area(ServerLevel level, BuildSite site) {
		BuildPlan plan = site.plan;
		if (plan == null) {
			return new AABB(site.origin.getX() - ADOPT_RADIUS, level.getMinY(),
					site.origin.getZ() - ADOPT_RADIUS,
					site.origin.getX() + ADOPT_RADIUS, level.getMaxY() + 1,
					site.origin.getZ() + ADOPT_RADIUS);
		}
		return new AABB(plan.min.getX() - ADOPT_RADIUS, level.getMinY(), plan.min.getZ() - ADOPT_RADIUS,
				plan.max.getX() + ADOPT_RADIUS + 1, level.getMaxY() + 1,
				plan.max.getZ() + ADOPT_RADIUS + 1);
	}

	private static String siteTag(String name) {
		return SITE_TAG_PREFIX + name;
	}

	// ---------------------------------------------------------------- positions

	/** A safe spot to put a villager, near the given block. */
	static BlockPos findSpawn(ServerLevel level, BlockPos around) {
		return findSpawn(level, around, 64);
	}

	private static BlockPos findSpawn(ServerLevel level, BlockPos around, int depth) {
		int bottom = Math.max(level.getMinY() + 1, around.getY() - depth);
		for (int radius = 1; radius <= 8; radius++) {
			for (int y = around.getY() + 4; y >= bottom; y--) {
				if (y > level.getMaxY()) {
					continue;
				}
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
							continue;
						}
						BlockPos pos = new BlockPos(around.getX() + dx, y, around.getZ() + dz);
						if (standable(level, pos)) {
							return pos;
						}
					}
				}
			}
		}
		BlockPos up = around.above();
		return standable(level, up) ? up : null;
	}

	/**
	 * Where a fresh crew should appear: on the ground just outside the footprint
	 * of the site, never inside the house they are about to build. Returns null
	 * when the site floats and there is no ground underneath at all.
	 */
	static BlockPos findCrewSpot(ServerLevel level, BuildPlan plan) {
		int top = plan.max.getY() + 4;
		int bottom = Math.max(level.getMinY() + 1, plan.min.getY() - 64);
		for (int ring = 1; ring <= 8; ring++) {
			int minX = plan.min.getX() - ring;
			int maxX = plan.max.getX() + ring;
			int minZ = plan.min.getZ() - ring;
			int maxZ = plan.max.getZ() + ring;
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					// Only the edge of the expanded rectangle, not its inside.
					if (x != minX && x != maxX && z != minZ && z != maxZ) {
						continue;
					}
					for (int y = top; y >= bottom; y--) {
						BlockPos pos = new BlockPos(x, y, z);
						if (standable(level, pos)) {
							return pos;
						}
					}
				}
			}
		}
		return null;
	}

	/** Where a worker should stand to reach a given cell. */
	static BlockPos findSpot(ServerLevel level, BlockPos cell, Vec3 worker) {
		int[] dxs = {1, -1, 0, 0, 2, -2, 0, 0, 1, 1, -1, -1, 2, 2, -2, -2};
		int[] dzs = {0, 0, 1, -1, 0, 0, 2, -2, 1, -1, 1, -1, 2, -2, 2, -2};
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dy = 0; dy <= 8; dy++) {
			for (int i = 0; i < dxs.length; i++) {
				BlockPos pos = new BlockPos(cell.getX() + dxs[i], cell.getY() - dy,
						cell.getZ() + dzs[i]);
				if (pos.equals(cell) || !standable(level, pos)) {
					continue;
				}
				double distance = worker.distanceToSqr(pos.getX() + 0.5, pos.getY(),
						pos.getZ() + 0.5);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = pos;
				}
			}
			if (best != null) {
				return best;
			}
		}
		// Last resort: straight below a high cell (roofs and towers).
		for (int dy = 1; dy <= 16; dy++) {
			BlockPos pos = cell.below(dy);
			if (standable(level, pos)) {
				return pos;
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos pos) {
		if (pos.getY() <= level.getMinY() || pos.getY() + 1 > level.getMaxY()) {
			return false;
		}
		BlockState feet = level.getBlockState(pos);
		if (!feet.getFluidState().isEmpty() || !feet.getCollisionShape(level, pos).isEmpty()) {
			return false;
		}
		if (feet.is(Blocks.FIRE) || feet.is(Blocks.SOUL_FIRE) || feet.is(Blocks.CACTUS)
				|| feet.is(Blocks.MAGMA_BLOCK) || feet.is(Blocks.POWDER_SNOW)) {
			return false;
		}
		BlockState head = level.getBlockState(pos.above());
		if (!head.getFluidState().isEmpty() || !head.getCollisionShape(level, pos.above()).isEmpty()) {
			return false;
		}
		if (head.is(Blocks.FIRE) || head.is(Blocks.SOUL_FIRE)) {
			return false;
		}
		BlockState floor = level.getBlockState(pos.below());
		return !floor.getCollisionShape(level, pos.below()).isEmpty();
	}

	private static String format(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}
}
