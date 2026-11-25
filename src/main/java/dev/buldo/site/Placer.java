package dev.buldo.site;

import dev.buldo.plan.BuildPlan;
import dev.buldo.util.Fmt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * The one place every block is written from, whether it is the instant builder
 * or a villager crew. Handles the container ledger, undo recording and block
 * entity data so both modes behave exactly the same.
 */
final class Placer {
	enum Result {
		PLACED,
		SKIPPED,
		BLOCKED,
		FAILED
	}

	private Placer() {
	}

	static Result place(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan,
			BuildPlan.Cell cell) {
		BlockState current = level.getBlockState(cell.pos);
		if (current.equals(cell.state)) {
			return Result.SKIPPED;
		}

		if (cell.cost > 0 && !site.free) {
			int taken = Containers.withdraw(level, site.containers, cell.item, cell.cost);
			if (taken < cell.cost) {
				if (taken > 0) {
					Containers.deposit(level, site.containers, cell.item, taken);
				}
				return Result.BLOCKED;
			}
		}

		CompoundTag previousBe = null;
		BlockEntity existing = level.getBlockEntity(cell.pos);
		if (existing != null) {
			previousBe = existing.saveWithFullMetadata(level.registryAccess());
		}
		if (site.currentFrame().cells.size() >= manager.config().maxUndoBlocksPerFrame) {
			site.startFrame(manager.config().maxUndoFrames);
		}
		site.recordUndo(cell.pos, current, previousBe, manager.config().maxUndoBlocksPerFrame);

		boolean placed = level.setBlock(cell.pos, cell.state,
				Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
		if (!placed) {
			if (cell.cost > 0 && !site.free) {
				Containers.deposit(level, site.containers, cell.item, cell.cost);
			}
			return Result.FAILED;
		}

		if (cell.be != null) {
			BlockEntity blockEntity = BlockEntity.loadStatic(cell.pos, cell.state, cell.be,
					level.registryAccess());
			if (blockEntity != null) {
				level.setBlockEntity(blockEntity);
				level.sendBlockUpdated(cell.pos, cell.state, cell.state, Block.UPDATE_CLIENTS);
			}
		}

		site.placed++;
		site.placedSinceSave++;
		site.blocksThisSecond++;
		return Result.PLACED;
	}

	/** Stop a site because a linked container ran dry, and remember what is missing. */
	static void block(SiteManager manager, BuildSite site, ServerLevel level, BuildPlan plan) {
		Map<Item, Integer> missing = manager.missingItems(site, plan, level);
		manager.storeMissing(site, missing);
		site.state = SiteState.BLOCKED;
		site.lastError = "missing materials";
		site.carry = 0;
		manager.markDirty();
		manager.notifyNear(site, Fmt.warn("Site " + site.name + " is blocked: "
				+ SiteManager.summarise(missing)
				+ ". It resumes on its own once the linked containers have them."));
	}
}
