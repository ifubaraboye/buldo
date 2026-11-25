package dev.buldo.plan;

import dev.buldo.schem.Blueprint;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A blueprint resolved against the live block registry, rotated/mirrored and
 * anchored at a world position.
 *
 * Cells are ordered bottom up (y, then z, then x) so a build looks like it is
 * growing and so blocks that sit on other blocks are normally placed after
 * their support. Placement itself does not trigger neighbour updates, so the
 * order only matters for the look of it.
 */
public final class BuildPlan {
	public static final class Cell {
		public final int index;
		public final BlockPos pos;
		public final BlockState state;
		public final CompoundTag be;
		public final Item item;
		public final int cost;
		public final boolean air;
		public boolean exposed;

		Cell(int index, BlockPos pos, BlockState state, CompoundTag be, Item item, int cost) {
			this.index = index;
			this.pos = pos;
			this.state = state;
			this.be = be;
			this.item = item;
			this.cost = cost;
			this.air = state.isAir();
		}
	}

	public final List<Cell> cells = new ArrayList<>();
	public final BlockPos min;
	public final BlockPos max;
	public int airCells;
	public int solidCells;
	public int outOfBounds;
	public final Map<Item, Integer> required = new LinkedHashMap<>();
	public final Map<String, Integer> unknown = new LinkedHashMap<>();
	public final Map<String, Integer> unobtainable = new LinkedHashMap<>();
	public int[] exposedIndices = new int[0];

	private BuildPlan(BlockPos min, BlockPos max, int airCells, int solidCells, int outOfBounds) {
		this.min = min;
		this.max = max;
		this.airCells = airCells;
		this.solidCells = solidCells;
		this.outOfBounds = outOfBounds;
	}

	public int size() {
		return cells.size();
	}

	public int nonAirTotal() {
		return solidCells;
	}

	public static BuildPlan build(Blueprint blueprint, BlockPos origin, Rotation rotation, Mirror mirror,
			boolean exactReplace, HolderLookup<Block> blockLookup, Set<String> freeBlocks,
			LevelHeightAccessor height) {
		int sizeX = blueprint.sizeX;
		int sizeZ = blueprint.sizeZ;
		boolean swapped = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
		int rotatedX = swapped ? sizeZ : sizeX;
		int rotatedZ = swapped ? sizeX : sizeZ;

		BlockPos min = origin;
		BlockPos max = new BlockPos(origin.getX() + rotatedX - 1, origin.getY() + blueprint.sizeY - 1, origin.getZ() + rotatedZ - 1);
		BuildPlan plan = new BuildPlan(min, max, 0, 0, 0);

		Map<String, BlockState> resolved = new HashMap<>();
		int air = 0;
		int solid = 0;
		int outOfBounds = 0;

		for (int y = 0; y < blueprint.sizeY; y++) {
			int worldY = origin.getY() + y;
			if (worldY < height.getMinY() || worldY > height.getMaxY()) {
				outOfBounds += sizeX * sizeZ;
				continue;
			}
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					int sourceIndex = blueprint.index(x, y, z);
					String raw = blueprint.stateAt(sourceIndex);
					if (raw == null) {
						continue;
					}
					BlockState base = resolved.get(raw);
					if (base == null && !resolved.containsKey(raw)) {
						base = parse(raw, blockLookup);
						resolved.put(raw, base);
					}
					if (base == null) {
						plan.unknown.merge(raw, 1, Integer::sum);
						continue;
					}

					BlockState state = base.mirror(mirror).rotate(rotation);
					if (state.is(Blocks.STRUCTURE_VOID)) {
						continue;
					}
					if (state.isAir() && !exactReplace) {
						air++;
						continue;
					}

					int[] rotated = rotate(x, z, sizeX, sizeZ, rotation, mirror);
					BlockPos pos = new BlockPos(origin.getX() + rotated[0], worldY, origin.getZ() + rotated[1]);

					CostTable.Cost cost = CostTable.cost(state, freeBlocks);
					if (cost.count() > 0) {
						plan.required.merge(cost.item(), cost.count(), Integer::sum);
					} else if (!state.isAir() && state.getBlock().asItem() == Items.AIR) {
						var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
						plan.unobtainable.merge(id == null ? raw : id.toString(), 1, Integer::sum);
					}

					CompoundTag be = blueprint.blockEntities.get(sourceIndex);
					plan.cells.add(new Cell(plan.cells.size(), pos, state, be, cost.item(), cost.count()));
					if (state.isAir()) {
						air++;
					} else {
						solid++;
					}
				}
			}
		}

		plan.airCells = air;
		plan.solidCells = solid;
		plan.outOfBounds = outOfBounds;
		plan.computeExposed();
		return plan;
	}

	private static BlockState parse(String raw, HolderLookup<Block> lookup) {
		try {
			return BlockStateParser.parseForBlock(lookup, raw, false).blockState();
		} catch (Exception e) {
			return null;
		}
	}

	private void computeExposed() {
		if (cells.isEmpty()) {
			exposedIndices = new int[0];
			return;
		}
		Set<Long> solid = new HashSet<>(Math.max(16, cells.size() * 2));
		for (Cell cell : cells) {
			if (!cell.state.isAir()) {
				solid.add(cell.pos.asLong());
			}
		}
		List<Integer> exposed = new ArrayList<>();
		for (Cell cell : cells) {
			if (cell.state.isAir()) {
				continue;
			}
			int x = cell.pos.getX();
			int y = cell.pos.getY();
			int z = cell.pos.getZ();
			boolean isExposed = !solid.contains(BlockPos.asLong(x + 1, y, z))
					|| !solid.contains(BlockPos.asLong(x - 1, y, z))
					|| !solid.contains(BlockPos.asLong(x, y + 1, z))
					|| !solid.contains(BlockPos.asLong(x, y - 1, z))
					|| !solid.contains(BlockPos.asLong(x, y, z + 1))
					|| !solid.contains(BlockPos.asLong(x, y, z - 1));
			cell.exposed = isExposed;
			if (isExposed) {
				exposed.add(cell.index);
			}
		}
		exposedIndices = new int[exposed.size()];
		for (int i = 0; i < exposed.size(); i++) {
			exposedIndices[i] = exposed.get(i);
		}
	}

	/** Maps a blueprint column to the rotated/mirrored column. */
	public static int[] rotate(int x, int z, int sizeX, int sizeZ, Rotation rotation, Mirror mirror) {
		int mx = switch (mirror) {
			case LEFT_RIGHT -> sizeX - 1 - x;
			case FRONT_BACK -> x;
			default -> x;
		};
		int mz = switch (mirror) {
			case LEFT_RIGHT -> z;
			case FRONT_BACK -> sizeZ - 1 - z;
			default -> z;
		};
		return switch (rotation) {
			case CLOCKWISE_90 -> new int[]{sizeZ - 1 - mz, mx};
			case CLOCKWISE_180 -> new int[]{sizeX - 1 - mx, sizeZ - 1 - mz};
			case COUNTERCLOCKWISE_90 -> new int[]{mz, sizeX - 1 - mx};
			default -> new int[]{mx, mz};
		};
	}

	public static BlockPos sizeAfter(Blueprint blueprint, Rotation rotation) {
		boolean swapped = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
		return new BlockPos(swapped ? blueprint.sizeZ : blueprint.sizeX, blueprint.sizeY,
				swapped ? blueprint.sizeX : blueprint.sizeZ);
	}
}
