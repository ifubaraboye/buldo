package dev.buldo.site;

import dev.buldo.plan.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The preview. A server side particle hologram of the blocks that are still to
 * be placed, so it needs no client mod, works on vanilla clients and cannot
 * conflict with Iris or Sodium.
 *
 * Only exposed blocks are drawn, only near a player, and the scan rotates so a
 * big structure is still readable without flooding the network. A gold outline
 * shows the exact footprint, which makes it easy to spot a site that is one
 * block off before starting the build.
 */
final class Ghost {
	private static final int MAX_PARTICLES = 700;
	private static final int MAX_OUTLINE_PARTICLES = 400;
	private static final int OUTLINE_COLOR = 0xFFC94A;
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
		if (plan == null) {
			return;
		}

		drawOutline(level, plan);
		// In block display mode the outline is the only particle layer; the block
		// models themselves come from PreviewDisplays.
		if (site.displayPreview || plan.exposedIndices.length == 0) {
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

			if (!near(level, x, y, z)) {
				continue;
			}

			int color = cell.state.getMapColor(level, cell.pos).col & 0xFFFFFF;
			level.sendParticles(new DustParticleOptions(color, 1.0f), x, y, z, 1, 0, 0, 0, 0);
			sent++;
		}

		// Move the scanning window along so every part gets drawn eventually.
		site.ghostCursor = (start + count / 3) % count;
	}

	/**
	 * Draws the twelve edges of the site bounding box as gold dust, so the exact
	 * position and size of the structure are visible before a single block is
	 * placed. The step grows with the box so big builds stay under the particle
	 * budget.
	 */
	private static void drawOutline(ServerLevel level, BuildPlan plan) {
		BlockPos min = plan.min;
		BlockPos max = plan.max;
		double x0 = min.getX() - 0.05;
		double y0 = min.getY() - 0.05;
		double z0 = min.getZ() - 0.05;
		double x1 = max.getX() + 1.05;
		double y1 = max.getY() + 1.05;
		double z1 = max.getZ() + 1.05;

		double[][] corners = {
				{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
				{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1},
		};
		int[][] edges = {
				{0, 1}, {1, 2}, {2, 3}, {3, 0},
				{4, 5}, {5, 6}, {6, 7}, {7, 4},
				{0, 4}, {1, 5}, {2, 6}, {3, 7},
		};

		double total = 4.0 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
		double step = Math.max(1.0, total / MAX_OUTLINE_PARTICLES);
		DustParticleOptions dust = new DustParticleOptions(OUTLINE_COLOR, 0.9f);

		for (int[] edge : edges) {
			double[] a = corners[edge[0]];
			double[] b = corners[edge[1]];
			double length = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2)
					+ Math.pow(b[2] - a[2], 2));
			int points = Math.max(1, (int) Math.ceil(length / step));
			for (int i = 0; i <= points; i++) {
				double t = points == 0 ? 0 : (double) i / points;
				double x = a[0] + (b[0] - a[0]) * t;
				double y = a[1] + (b[1] - a[1]) * t;
				double z = a[2] + (b[2] - a[2]) * t;
				if (!near(level, x, y, z)) {
					continue;
				}
				level.sendParticles(dust, x, y, z, 1, 0, 0, 0, 0);
			}
		}
	}

	private static boolean near(ServerLevel level, double x, double y, double z) {
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(x, y, z) < VIEW_DISTANCE) {
				return true;
			}
		}
		return false;
	}
}
