package dev.buldo.plan;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Set;

/**
 * How many items a block state costs.
 *
 * Rules:
 *   - air and configured free blocks cost nothing
 *   - a double slab costs two slabs
 *   - the upper half of a door or tall plant, and the head of a bed, are free
 *     (the lower half / foot pays for the pair)
 *   - everything else costs one of the item the block is made from
 *
 * A block with no item at all is reported by the plan as "not obtainable"
 * instead of being guessed at.
 */
public final class CostTable {
	private CostTable() {
	}

	public record Cost(Item item, int count) {
		public boolean free() {
			return count <= 0;
		}
	}

	public static final Cost FREE = new Cost(null, 0);

	public static Cost cost(BlockState state, Set<String> freeBlocks) {
		if (state.isAir()) {
			return FREE;
		}
		if (freeBlocks.contains(key(state))) {
			return FREE;
		}
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
			return FREE;
		}
		if (state.hasProperty(BlockStateProperties.BED_PART)
				&& state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
			return FREE;
		}
		if (state.is(Blocks.STRUCTURE_VOID)) {
			return FREE;
		}

		Item item = state.getBlock().asItem();
		if (item == Items.AIR) {
			item = substitute(state);
			if (item == null) {
				return FREE;
			}
		}

		int count = 1;
		if (state.hasProperty(BlockStateProperties.SLAB_TYPE)
				&& state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
			count = 2;
		}
		return new Cost(item, count);
	}

	/** Blocks that have no item but are clearly made from something. */
	private static Item substitute(BlockState state) {
		if (state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)) {
			return Items.DIRT;
		}
		if (state.is(Blocks.BUDDING_AMETHYST)) {
			return Items.AMETHYST_BLOCK;
		}
		if (state.is(Blocks.CHORUS_PLANT)) {
			return Items.CHORUS_FLOWER;
		}
		if (state.is(Blocks.CAVE_VINES_PLANT)) {
			return Items.GLOW_BERRIES;
		}
		if (state.is(Blocks.WEEPING_VINES_PLANT)) {
			return Items.WEEPING_VINES;
		}
		if (state.is(Blocks.TWISTING_VINES_PLANT)) {
			return Items.TWISTING_VINES;
		}
		if (state.is(Blocks.KELP_PLANT)) {
			return Items.KELP;
		}
		return null;
	}

	public static String key(BlockState state) {
		var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id == null ? "minecraft:air" : id.toString();
	}
}
