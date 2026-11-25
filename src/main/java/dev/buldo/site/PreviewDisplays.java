package dev.buldo.site;

import dev.buldo.plan.BuildPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;

import java.util.ArrayList;

/**
 * Opt-in block-model preview, in the spirit of Litematica's ghost rendering.
 *
 * Litematica draws a whole translucent copy of the schematic client side. Buldo
 * has to work with vanilla clients, so the closest equivalent is a set of
 * server side {@link Display.BlockDisplay} entities: every exposed block of the
 * remaining plan becomes a real block model at its final position. There is no
 * collision and no interaction, and the entities are removed as soon as the
 * site starts building, moves, or turns the preview off.
 *
 * The entities are tagged so they can be cleaned up after a crash, and they are
 * never saved as part of the build itself.
 */
public final class PreviewDisplays {
	/** Tag on every preview entity, so orphans can be found after a restart. */
	public static final String TAG = "buldo_preview";

	/** Keep the entity count sane; bigger previews fall back to particles. */
	public static final int MAX_PER_SITE = 1500;
	private static final int MAX_TOTAL = 3000;

	private static final java.util.Map<String, Placed> SPAWNED = new java.util.HashMap<>();

	private PreviewDisplays() {
	}

	private record Placed(ServerLevel level, long signature, java.util.List<Display.BlockDisplay> entities) {
		boolean alive() {
			if (entities.isEmpty()) {
				return false;
			}
			for (Display.BlockDisplay entity : entities) {
				if (!entity.isAlive()) {
					return false;
				}
			}
			return true;
		}
	}

	static void tick(SiteManager manager, BuildSite site) {
		if (!site.displayPreview) {
			hide(site.name);
			return;
		}
		boolean visible = site.ghost
				&& (site.state == SiteState.READY || site.state == SiteState.PAUSED
						|| site.state == SiteState.BLOCKED || site.state == SiteState.WAITING_MATERIALS);
		ServerLevel level = visible ? manager.level(site) : null;
		BuildPlan plan = visible ? manager.plan(site) : null;
		if (level == null || plan == null) {
			hide(site.name);
			return;
		}
		// Only spawn where the site is actually loaded; otherwise the entities
		// would sit in unloaded chunks and come back as orphans later.
		if (!level.isLoaded(plan.min) || !level.isLoaded(plan.max)) {
			hide(site.name);
			return;
		}

		int count = remainingExposed(plan, site.cursor);
		if (count == 0 || count > MAX_PER_SITE) {
			hide(site.name);
			return;
		}

		long signature = signature(plan, site);
		Placed current = SPAWNED.get(site.name);
		if (current != null && current.level() == level && current.signature() == signature && current.alive()) {
			return;
		}

		hide(site.name);
		if (totalSpawned() + count > MAX_TOTAL) {
			return;
		}
		spawn(site, level, plan, signature);
	}

	/** Number of exposed blocks that would become preview entities. */
	public static int previewCount(BuildPlan plan, int cursor) {
		return remainingExposed(plan, cursor);
	}

	/** Removes the preview of one site. Safe to call at any time. */
	public static void hide(String siteName) {
		Placed placed = SPAWNED.remove(siteName);
		if (placed != null) {
			for (Display.BlockDisplay entity : placed.entities()) {
				entity.discard();
			}
		}
	}

	public static void hideAll() {
		for (String name : new ArrayList<>(SPAWNED.keySet())) {
			hide(name);
		}
	}

	/**
	 * Removes preview entities left in the world by an unclean shutdown. Called
	 * once when the server starts, before any site gets a chance to spawn a new
	 * preview.
	 */
	public static void discardOrphans(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			java.util.List<Entity> orphans = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (entity.entityTags().contains(TAG)) {
					orphans.add(entity);
				}
			}
			for (Entity entity : orphans) {
				entity.discard();
			}
		}
	}

	/**
	 * Removes tagged preview entities that are not owned by the current session.
	 * This catches leftovers from a crash once their chunk actually loads, which
	 * can happen long after {@link #discardOrphans(MinecraftServer)} ran.
	 */
	public static void cleanupOrphans(SiteManager manager) {
		java.util.Set<Entity> tracked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Placed placed : SPAWNED.values()) {
			tracked.addAll(placed.entities());
		}
		for (ServerLevel level : manager.levels()) {
			java.util.List<Entity> orphans = new ArrayList<>();
			for (Entity entity : level.getAllEntities()) {
				if (entity.entityTags().contains(TAG) && !tracked.contains(entity)) {
					orphans.add(entity);
				}
			}
			for (Entity entity : orphans) {
				entity.discard();
			}
		}
	}

	private static int remainingExposed(BuildPlan plan, int cursor) {
		int count = 0;
		for (int index : plan.exposedIndices) {
			if (index >= cursor) {
				count++;
			}
		}
		return count;
	}

	private static long signature(BuildPlan plan, BuildSite site) {
		long hash = site.origin.asLong();
		hash = hash * 31 + site.cursor;
		hash = hash * 31 + System.identityHashCode(plan);
		hash = hash * 31 + site.rotation.ordinal();
		hash = hash * 31 + site.mirror.ordinal();
		return hash;
	}

	private static int totalSpawned() {
		int total = 0;
		for (Placed placed : SPAWNED.values()) {
			total += placed.entities().size();
		}
		return total;
	}

	private static void spawn(BuildSite site, ServerLevel level, BuildPlan plan, long signature) {
		java.util.List<Display.BlockDisplay> entities = new ArrayList<>();
		for (int index : plan.exposedIndices) {
			if (index < site.cursor) {
				continue;
			}
			BuildPlan.Cell cell = plan.cells.get(index);
			Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
			display.setBlockState(cell.state);
			// Block displays render from the position as the block's min corner.
			display.setPos(cell.pos.getX(), cell.pos.getY(), cell.pos.getZ());
			display.setNoGravity(true);
			display.setSilent(true);
			display.setPermanentlyInvulnerable(true);
			display.addTag(TAG);
			if (level.addFreshEntity(display)) {
				entities.add(display);
			}
		}
		if (!entities.isEmpty()) {
			SPAWNED.put(site.name, new Placed(level, signature, entities));
		}
	}
}
