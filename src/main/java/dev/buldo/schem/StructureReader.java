package dev.buldo.schem;

import dev.buldo.nbt.NbtU;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Vanilla structure block .nbt files (structure template format). */
final class StructureReader {
	private StructureReader() {
	}

	static Blueprint read(CompoundTag root, String name, String file, String hash) throws IOException {
		int[] size = NbtU.readInt3(root, "size");
		if (size == null || size.length < 3) {
			throw new IOException("structure has no size");
		}
		int sx = size[0];
		int sy = size[1];
		int sz = size[2];
		if (sx <= 0 || sy <= 0 || sz <= 0) {
			throw new IOException("invalid size " + sx + "x" + sy + "x" + sz);
		}
		int volume = sx * sy * sz;

		ListTag paletteTag = root.getListOrEmpty("palette");
		List<String> palette = new ArrayList<>();
		for (int i = 0; i < paletteTag.size(); i++) {
			palette.add(stateString(paletteTag.getCompoundOrEmpty(i)));
		}
		int air = palette.indexOf("minecraft:air");
		if (air < 0) {
			air = palette.size();
			palette.add("minecraft:air");
		}

		int[] cells = new int[volume];
		Arrays.fill(cells, air);
		Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();

		ListTag blocks = root.getListOrEmpty("blocks");
		for (int i = 0; i < blocks.size(); i++) {
			CompoundTag block = blocks.getCompoundOrEmpty(i);
			int[] pos = NbtU.readInt3(block, "pos");
			if (pos == null || pos.length < 3) {
				continue;
			}
			int x = pos[0];
			int y = pos[1];
			int z = pos[2];
			if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
				continue;
			}
			int index = (y * sz + z) * sx + x;
			cells[index] = Math.max(0, block.getIntOr("state", 0));
			CompoundTag nbt = block.getCompoundOrEmpty("nbt");
			if (!nbt.isEmpty()) {
				blockEntities.put(index, nbt.copy());
			}
		}

		List<CompoundTag> entities = new ArrayList<>();
		ListTag entitiesTag = root.getListOrEmpty("entities");
		for (int i = 0; i < entitiesTag.size(); i++) {
			entities.add(entitiesTag.getCompoundOrEmpty(i).copy());
		}

		int dataVersion = root.getIntOr("DataVersion", 0);
		return new Blueprint(name, file, "structure", sx, sy, sz, 0, 0, 0,
				palette, cells, blockEntities, entities, dataVersion, hash);
	}

	static String stateString(CompoundTag entry) {
		String blockName = entry.getStringOr("Name", "minecraft:air");
		CompoundTag props = entry.getCompoundOrEmpty("Properties");
		if (props.isEmpty()) {
			return blockName;
		}
		StringBuilder sb = new StringBuilder(blockName).append('[');
		boolean first = true;
		for (String key : new TreeSet<>(props.keySet())) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(key).append('=').append(props.getStringOr(key, ""));
		}
		return sb.append(']').toString();
	}
}
