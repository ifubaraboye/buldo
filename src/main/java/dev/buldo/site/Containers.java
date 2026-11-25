package dev.buldo.site;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reading and taking items from the containers linked to a site. */
public final class Containers {
	private Containers() {
	}

	public static Container at(ServerLevel level, BlockPos pos) {
		level.getChunkAt(pos);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		return blockEntity instanceof Container container ? container : null;
	}

	public static boolean isContainer(ServerLevel level, BlockPos pos) {
		return at(level, pos) != null;
	}

	/** A double chest is two containers; linking one links both halves. */
	public static List<BlockPos> expand(ServerLevel level, BlockPos pos) {
		List<BlockPos> out = new ArrayList<>();
		out.add(pos.immutable());
		BlockState state = level.getBlockState(pos);
		if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos other = pos.relative(direction);
				if (level.getBlockState(other).is(state.getBlock())) {
					out.add(other.immutable());
				}
			}
		}
		return out;
	}

	public static int count(ServerLevel level, List<BlockPos> positions, Item item) {
		int total = 0;
		for (BlockPos pos : positions) {
			Container container = at(level, pos);
			if (container == null) {
				continue;
			}
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty() && stack.getItem() == item) {
					total += stack.getCount();
				}
			}
		}
		return total;
	}

	public static Map<Item, Integer> snapshot(ServerLevel level, List<BlockPos> positions) {
		Map<Item, Integer> totals = new LinkedHashMap<>();
		for (BlockPos pos : positions) {
			Container container = at(level, pos);
			if (container == null) {
				continue;
			}
			for (int slot = 0; slot < container.getContainerSize(); slot++) {
				ItemStack stack = container.getItem(slot);
				if (!stack.isEmpty()) {
					totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
				}
			}
		}
		return totals;
	}

	/** All or nothing: returns 0 when the containers cannot cover the amount. */
	public static int withdraw(ServerLevel level, List<BlockPos> positions, Item item, int amount) {
		if (amount <= 0) {
			return 0;
		}
		if (count(level, positions, item) < amount) {
			return 0;
		}
		int remaining = amount;
		for (BlockPos pos : positions) {
			Container container = at(level, pos);
			if (container == null) {
				continue;
			}
			boolean changed = false;
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || stack.getItem() != item) {
					continue;
				}
				int take = Math.min(remaining, stack.getCount());
				stack.shrink(take);
				remaining -= take;
				changed = true;
				container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
			}
			if (changed) {
				container.setChanged();
			}
		}
		return amount - remaining;
	}

	public static void deposit(ServerLevel level, List<BlockPos> positions, Item item, int amount) {
		int remaining = amount;
		for (BlockPos pos : positions) {
			if (remaining <= 0) {
				return;
			}
			Container container = at(level, pos);
			if (container == null) {
				continue;
			}
			boolean changed = false;
			// Top up existing stacks first.
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = container.getItem(slot);
				if (stack.isEmpty() || stack.getItem() != item) {
					continue;
				}
				int room = stack.getMaxStackSize() - stack.getCount();
				if (room <= 0) {
					continue;
				}
				int add = Math.min(remaining, room);
				stack.grow(add);
				remaining -= add;
				changed = true;
				container.setItem(slot, stack);
			}
			for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
				if (!container.getItem(slot).isEmpty()) {
					continue;
				}
				int add = Math.min(remaining, new ItemStack(item).getMaxStackSize());
				container.setItem(slot, new ItemStack(item, add));
				remaining -= add;
				changed = true;
			}
			if (changed) {
				container.setChanged();
			}
		}
	}
}
