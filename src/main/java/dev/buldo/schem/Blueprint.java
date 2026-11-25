package dev.buldo.schem;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;

/**
 * A schematic, normalised to a single dense cell array.
 *
 * Cell order is x fastest, then z, then y:
 *   index = (y * sizeZ + z) * sizeX + x
 * which is what Sponge, vanilla structures and Litematica all use.
 *
 * Block states stay as raw strings here; they are resolved against the running
 * server's block registry when a build plan is made, so an unknown block is a
 * report rather than a load failure.
 */
public final class Blueprint {
	public final String name;
	public final String file;
	public final String format;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final int offsetX;
	public final int offsetY;
	public final int offsetZ;
	/** Full block state strings, e.g. {@code minecraft:brick_stairs[facing=north,...]}. */
	public final List<String> palette;
	/** One palette index per cell, length = volume. */
	public final int[] cells;
	/** Block entity data by cell index. The tag always contains an {@code id}. */
	public final Map<Integer, CompoundTag> blockEntities;
	public final List<CompoundTag> entities;
	public final int dataVersion;
	public final String hash;

	public Blueprint(String name, String file, String format, int sizeX, int sizeY, int sizeZ,
			int offsetX, int offsetY, int offsetZ, List<String> palette, int[] cells,
			Map<Integer, CompoundTag> blockEntities, List<CompoundTag> entities,
			int dataVersion, String hash) {
		this.name = name;
		this.file = file;
		this.format = format;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
		this.offsetZ = offsetZ;
		this.palette = palette;
		this.cells = cells;
		this.blockEntities = blockEntities;
		this.entities = entities;
		this.dataVersion = dataVersion;
		this.hash = hash;
	}

	public int volume() {
		return sizeX * sizeY * sizeZ;
	}

	public int index(int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	public BlockPos size() {
		return new BlockPos(sizeX, sizeY, sizeZ);
	}

	public BlockPos offset() {
		return new BlockPos(offsetX, offsetY, offsetZ);
	}

	public String stateAt(int index) {
		if (index < 0 || index >= cells.length) {
			return null;
		}
		int p = cells[index];
		if (p < 0 || p >= palette.size()) {
			return null;
		}
		return palette.get(p);
	}
}
