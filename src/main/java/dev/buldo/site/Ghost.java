package dev.buldo.site;

import dev.buldo.plan.BuildPlan;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The preview. A server side particle hologram of the blocks that are still to
 * be placed, so it needs no client mod, works on vanilla clients and cannot
 * conflict with Iris or Sodium.
 *
 * Only exposed blocks are drawn, only near a player, and the scan rotates so a
 * big structure is still readable without flooding the network.
 */
final class Ghost {
	private static final int MAX_PARTICLES = 700;
	private static final double VIEW_DISTANCE = 48.0 * 48.0;

	private Ghost() {
	}

	static void tick(SiteManager manager, BuildSite site) {
		if (!site.ghost) {
			return;
		}
		if (site.state == SiteState.DONE || site.state == SiteState.UNDONE
				|| site.state == SiteState.MISSING_SCHEMATIC) {
			return;
		}
		ServerLevel level = manager.level(site);
		if (level == null) {
			return;
		}
		if (level.players().isEmpty()) {
			return;
		}
		BuildPlan plan = manager.plan(site);
		if (plan == null || plan.exposedIndices.length == 0) {
			return;
		}

		int[] exposed = plan.exposedIndices;
		int count = exposed.length;
		int start = Math.floorMod(site.ghostCursor, count);
		int sent = 0;

		for (int step = 0; step < count && sent < MAX_PARTICLES; step++) {
			int listIndex = exposed[(start + step) % count];
			if (listIndex < site.cursor) {
				continue;
			}
			BuildPlan.Cell cell = plan.cells.get(listIndex);
			double x = cell.pos.getX() + 0.5;
			double y = cell.pos.getY() + 0.5;
			double z = cell.pos.getZ() + 0.5;

			boolean near = false;
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(x, y, z) < VIEW_DISTANCE) {
					near = true;
					break;
				}
			}
			if (!near) {
				continue;
			}

			int color = cell.state.getMapColor(level, cell.pos).col & 0xFFFFFF;
			level.sendParticles(new DustParticleOptions(color, 1.0f), x, y, z, 1, 0, 0, 0, 0);
			sent++;
		}

		// Move the scanning window along so every part gets drawn eventually.
		site.ghostCursor = (start + count / 3) % count;
	}
}
