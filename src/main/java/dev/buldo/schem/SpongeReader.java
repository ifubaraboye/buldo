package dev.buldo.schem;

import dev.buldo.nbt.NbtU;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sponge schematic format, versions 1 to 3.
 *
 * v1: Palette + Blocks (byte ids) + AddBlocks (high bits)
 * v2: Palette + BlockData (varints)
 * v3: Blocks{Palette, Data, BlockEntities}
 */
final class SpongeReader {
	private SpongeReader() {
	}

	static Blueprint read(CompoundTag root, String name, String file, String hash) throws IOException {
		CompoundTag s = root.getCompoundOrEmpty("Schematic");
		if (s.isEmpty()) {
			throw new IOException("not a Sponge schematic");
		}
		int version = s.getIntOr("Version", 0);
		int sx = s.getIntOr("Width", 0);
		int sy = s.getIntOr("Height", 0);
		int sz = s.getIntOr("Length", 0);
		if (sx <= 0 || sy <= 0 || sz <= 0) {
			throw new IOException("invalid size " + sx + "x" + sy + "x" + sz);
		}
		int volume = sx * sy * sz;
		int[] offset = NbtU.readInt3(s, "Offset");
		int ox = offset == null ? 0 : offset[0];
		int oy = offset == null ? 0 : offset[1];
		int oz = offset == null ? 0 : offset[2];

		Map<String, Integer> paletteMap;
		int[] cellPalette = new int[volume];
		ListTag blockEntities;

		if (version >= 3) {
			CompoundTag blocks = s.getCompoundOrEmpty("Blocks");
			paletteMap = readPalette(blocks.getCompoundOrEmpty("Palette"));
			byte[] data = blocks.getByteArray("Data").orElse(null);
			if (data == null) {
				throw new IOException("version 3 schematic has no block data");
			}
			decodeVarints(data, cellPalette);
			blockEntities = blocks.getListOrEmpty("BlockEntities");
			if (blockEntities.size() == 0) {
				blockEntities = s.getListOrEmpty("BlockEntities");
			}
		} else if (version == 2) {
			paletteMap = readPalette(s.getCompoundOrEmpty("Palette"));
			byte[] data = s.getByteArray("BlockData").orElse(null);
			if (data == null) {
				throw new IOException("version 2 schematic has no block data");
			}
			decodeVarints(data, cellPalette);
			blockEntities = s.getListOrEmpty("BlockEntities");
		} else {
			paletteMap = readPalette(s.getCompoundOrEmpty("Palette"));
			byte[] data = s.getByteArray("Blocks").orElse(null);
			if (data == null) {
				throw new IOException("version 1 schematic has no block data");
			}
			byte[] add = s.getByteArray("AddBlocks").orElse(null);
			for (int i = 0; i < volume; i++) {
				int value = data[i] & 0xFF;
				if (add != null && (i >> 1) < add.length) {
					int high = (add[i >> 1] >> ((i & 1) * 4)) & 0x0F;
					value |= high << 8;
				}
				cellPalette[i] = value;
			}
			blockEntities = s.getListOrEmpty("BlockEntities");
		}

		List<String> palette = invertPalette(paletteMap);
		Map<Integer, CompoundTag> blockEntityMap = readBlockEntities(blockEntities, sx, sy, sz);

		return new Blueprint(name, file, "sponge-v" + version, sx, sy, sz, ox, oy, oz,
				palette, cellPalette, blockEntityMap, new ArrayList<>(), s.getIntOr("DataVersion", 0), hash);
	}

	private static Map<String, Integer> readPalette(CompoundTag tag) {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (String key : tag.keySet()) {
			map.put(key, tag.getIntOr(key, -1));
		}
		return map;
	}

	private static List<String> invertPalette(Map<String, Integer> map) {
		int max = -1;
		for (int value : map.values()) {
			if (value > max) {
				max = value;
			}
		}
		if (max < 0) {
			return new ArrayList<>(List.of("minecraft:air"));
		}
		List<String> palette = new ArrayList<>(Collections.nCopies(max + 1, "minecraft:air"));
		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			int idx = entry.getValue();
			if (idx >= 0 && idx <= max && entry.getKey() != null) {
				palette.set(idx, entry.getKey());
			}
		}
		return palette;
	}

	private static Map<Integer, CompoundTag> readBlockEntities(ListTag list, int sx, int sy, int sz) {
		Map<Integer, CompoundTag> map = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompoundOrEmpty(i);
			int[] pos = NbtU.readInt3(entry, "Pos");
			if (pos == null || pos.length < 3) {
				continue;
			}
			int x = pos[0];
			int y = pos[1];
			int z = pos[2];
			if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
				continue;
			}
			CompoundTag data = entry.getCompoundOrEmpty("Data").copy();
			String id = entry.getStringOr("Id", data.getStringOr("id", ""));
			if (!id.isEmpty() && !data.contains("id")) {
				data.putString("id", id);
			}
			if (!data.contains("id")) {
				continue;
			}
			map.put((y * sz + z) * sx + x, data);
		}
		return map;
	}

	private static void decodeVarints(byte[] data, int[] out) throws IOException {
		int i = 0;
		int p = 0;
		int value = 0;
		int shift = 0;
		while (p < out.length) {
			if (i >= data.length) {
				throw new IOException("block data ended after " + p + " of " + out.length + " cells");
			}
			int b = data[i++] & 0xFF;
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) != 0) {
				shift += 7;
				if (shift > 35) {
					throw new IOException("malformed varint");
				}
			} else {
				out[p++] = value;
				value = 0;
				shift = 0;
			}
		}
	}
}
