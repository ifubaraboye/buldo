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

/**
 * Litematica .litematic files (versions 1 to 7), including multi region files.
 *
 * Litematica packs palette indices into a contiguous bit stream that is allowed
 * to cross long boundaries, which is different from vanilla's block state
 * container, so the unpacking is done by hand here.
 */
final class LitematicReader {
	private LitematicReader() {
	}

	private record Region(int px, int py, int pz, int sx, int sy, int sz,
			List<String> palette, long[] data, ListTag tileEntities) {
	}

	static Blueprint read(CompoundTag root, String name, String file, String hash) throws IOException {
		CompoundTag regionsTag = root.getCompoundOrEmpty("Regions");
		if (regionsTag.isEmpty()) {
			throw new IOException("litematic has no regions");
		}

		List<Region> regions = new ArrayList<>();
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;

		for (String key : regionsTag.keySet()) {
			CompoundTag region = regionsTag.getCompoundOrEmpty(key);
			int[] pos = NbtU.readInt3(region, "Position");
			int[] size = NbtU.readInt3(region, "Size");
			if (pos == null || size == null || size.length < 3) {
				continue;
			}
			int px = pos[0];
			int py = pos[1];
			int pz = pos[2];
			int sx = size[0];
			int sy = size[1];
			int sz = size[2];
			if (sx < 0) {
				px += sx + 1;
				sx = -sx;
			}
			if (sy < 0) {
				py += sy + 1;
				sy = -sy;
			}
			if (sz < 0) {
				pz += sz + 1;
				sz = -sz;
			}
			if (sx <= 0 || sy <= 0 || sz <= 0) {
				continue;
			}

			ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
			List<String> palette = new ArrayList<>();
			for (int i = 0; i < paletteTag.size(); i++) {
				palette.add(StructureReader.stateString(paletteTag.getCompoundOrEmpty(i)));
			}
			if (palette.isEmpty()) {
				palette.add("minecraft:air");
			}
			long[] data = region.getLongArray("BlockStates").orElse(new long[0]);

			regions.add(new Region(px, py, pz, sx, sy, sz, palette, data, region.getListOrEmpty("TileEntities")));
			minX = Math.min(minX, px);
			minY = Math.min(minY, py);
			minZ = Math.min(minZ, pz);
			maxX = Math.max(maxX, px + sx);
			maxY = Math.max(maxY, py + sy);
			maxZ = Math.max(maxZ, pz + sz);
		}

		if (regions.isEmpty()) {
			throw new IOException("litematic has no usable regions");
		}

		int sx = maxX - minX;
		int sy = maxY - minY;
		int sz = maxZ - minZ;
		int volume = sx * sy * sz;

		// Merge every region palette into one global palette.
		List<String> palette = new ArrayList<>();
		Map<String, Integer> paletteIndex = new LinkedHashMap<>();
		paletteIndex.put("minecraft:air", 0);
		palette.add("minecraft:air");
		int[] cells = new int[volume];
		Arrays.fill(cells, 0);

		for (Region region : regions) {
			int[] localToGlobal = new int[region.palette().size()];
			for (int i = 0; i < region.palette().size(); i++) {
				String state = region.palette().get(i);
				Integer existing = paletteIndex.get(state);
				if (existing == null) {
					existing = palette.size();
					palette.add(state);
					paletteIndex.put(state, existing);
				}
				localToGlobal[i] = existing;
			}

			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, region.palette().size() - 1)));
			int regionVolume = region.sx() * region.sy() * region.sz();
			long[] data = region.data();
			int offsetX = region.px() - minX;
			int offsetY = region.py() - minY;
			int offsetZ = region.pz() - minZ;

			for (int y = 0; y < region.sy(); y++) {
				for (int z = 0; z < region.sz(); z++) {
					for (int x = 0; x < region.sx(); x++) {
						int localIndex = x + z * region.sx() + y * region.sx() * region.sz();
						int paletteIdx = 0;
						if (data.length > 0 && localIndex < regionVolume) {
							paletteIdx = unpack(data, localIndex, bits);
						}
						if (paletteIdx < 0 || paletteIdx >= localToGlobal.length) {
							paletteIdx = 0;
						}
						int wx = offsetX + x;
						int wy = offsetY + y;
						int wz = offsetZ + z;
						cells[(wy * sz + wz) * sx + wx] = localToGlobal[paletteIdx];
					}
				}
			}
		}

		Map<Integer, CompoundTag> blockEntities = new LinkedHashMap<>();
		for (Region region : regions) {
			int offsetX = region.px() - minX;
			int offsetY = region.py() - minY;
			int offsetZ = region.pz() - minZ;
			ListTag tileEntities = region.tileEntities();
			for (int i = 0; i < tileEntities.size(); i++) {
				CompoundTag entry = tileEntities.getCompoundOrEmpty(i).copy();
				int[] pos = NbtU.readInt3(entry);
				if (pos == null || pos.length < 3) {
					continue;
				}
				int rx = pos[0];
				int ry = pos[1];
				int rz = pos[2];
				// Litematica stores region relative positions; be tolerant about
				// files that store them relative to the whole schematic instead.
				int x = offsetX + rx;
				int y = offsetY + ry;
				int z = offsetZ + rz;
				if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
					x = rx;
					y = ry;
					z = rz;
				}
				if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
					continue;
				}
				if (!entry.contains("id")) {
					continue;
				}
				blockEntities.put((y * sz + z) * sx + x, entry);
			}
		}

		return new Blueprint(name, file, "litematica", sx, sy, sz, 0, 0, 0,
				palette, cells, blockEntities, new ArrayList<>(),
				root.getIntOr("MinecraftDataVersion", 0), hash);
	}

	private static int unpack(long[] data, int index, int bits) {
		long startOffset = (long) index * bits;
		int startArray = (int) (startOffset >> 6);
		int endArray = (int) (((long) (index + 1) * bits - 1) >> 6);
		int startBit = (int) (startOffset & 63L);
		long mask = (1L << bits) - 1L;
		if (startArray == endArray) {
			if (startArray < 0 || startArray >= data.length) {
				return 0;
			}
			return (int) ((data[startArray] >>> startBit) & mask);
		}
		if (startArray < 0 || startArray >= data.length || endArray < 0 || endArray >= data.length) {
			return 0;
		}
		long part1 = data[startArray] >>> startBit;
		long part2 = data[endArray] << (64 - startBit);
		return (int) ((part1 | part2) & mask);
	}
}
