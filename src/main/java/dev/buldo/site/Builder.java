package dev.buldo.site;

import dev.buldo.BuldoConfig;
import dev.buldo.plan.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Places blocks for a site at a fixed rate.
 *
 * Blocks are written with UPDATE_CLIENTS only, so sand does not fall, redstone
 * does not fire and torches do not pop off while the structure is being built.
 * Materials are taken immediately before each block, and a site blocks (and
 * later resumes) when a container runs dry.
 */
final class Builder {
	private Builder() {
	}

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
		BuldoConfig config = manager.config();

		site.carry += config.blocksPerSecond / 20.0;
		int budget = (int) Math.floor(site.carry);
		if (budget <= 0) {
			return;
		}
		site.carry -= budget;

		int placedNow = 0;
		while (budget > 0 && site.cursor < plan.cells.size()) {
			BuildPlan.Cell cell = plan.cells.get(site.cursor);
			Placer.Result result = Placer.place(manager, site, level, plan, cell);
			if (result == Placer.Result.BLOCKED) {
				Placer.block(manager, site, level, plan);
				return;
			}
			if (result == Placer.Result.SKIPPED) {
				site.cursor++;
				site.skipped++;
				continue;
			}
			if (result == Placer.Result.FAILED) {
				site.cursor++;
				budget--;
				continue;
			}
			site.cursor++;
			placedNow++;
			budget--;
		}

		if (placedNow > 0) {
			manager.markDirty();
		}
		if (site.cursor >= plan.cells.size()) {
			finish(manager, site, level, plan);
		}
	}

	static void finish(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan) {
		site.state = SiteState.DONE;
		site.carry = 0;
		site.missing.clear();
		site.lastError = "";
		if (manager.config().settleOnFinish) {
			for (BuildPlan.Cell cell : plan.cells) {
				if (!cell.state.isAir()) {
					level.updateNeighborsAt(cell.pos, cell.state.getBlock());
				}
			}
		}
		// Rate reporting for the HUD.
		site.measuredRate = 0;
		manager.markDirty();
		manager.onSiteFinished(site);
	}

	/** Called once per second from the manager tick. */
	static void sampleRate(BuildSite site) {
		site.measuredRate = site.blocksThisSecond;
		site.blocksThisSecond = 0;
	}

	static BlockPos center(BuildPlan plan) {
		return new BlockPos((plan.min.getX() + plan.max.getX()) / 2,
				(plan.min.getY() + plan.max.getY()) / 2,
				(plan.min.getZ() + plan.max.getZ()) / 2);
	}
}
