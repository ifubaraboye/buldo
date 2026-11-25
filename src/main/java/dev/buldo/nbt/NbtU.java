package dev.buldo.nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Optional;

/** Small helpers for the NBT shapes used by schematic formats. */
public final class NbtU {
	private NbtU() {
	}

	/**
	 * Reads a three component vector that may be stored as an int array, as a
	 * list of numbers or as a compound with x/y/z. Returns null when missing.
	 */
	public static int[] readInt3(CompoundTag tag, String key) {
		Optional<int[]> array = tag.getIntArray(key);
		if (array.isPresent()) {
			return array.get();
		}
		Optional<ListTag> list = tag.getList(key);
		if (list.isPresent()) {
			ListTag l = list.get();
			if (l.size() >= 3) {
				return new int[]{l.getIntOr(0, 0), l.getIntOr(1, 0), l.getIntOr(2, 0)};
			}
		}
		Optional<CompoundTag> compound = tag.getCompound(key);
		if (compound.isPresent()) {
			CompoundTag c = compound.get();
			if (c.contains("x") && c.contains("y") && c.contains("z")) {
				return new int[]{c.getIntOr("x", 0), c.getIntOr("y", 0), c.getIntOr("z", 0)};
			}
		}
		return null;
	}

	public static int[] readInt3(CompoundTag tag) {
		int[] direct = readInt3(tag, "Pos");
		if (direct != null) {
			return direct;
		}
		if (tag.contains("x") && tag.contains("y") && tag.contains("z")) {
			return new int[]{tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0)};
		}
		return null;
	}

	public static BlockPos readPos(CompoundTag tag, String key) {
		int[] v = readInt3(tag, key);
		return v == null ? null : new BlockPos(v[0], v[1], v[2]);
	}

	public static void putPos(CompoundTag tag, String key, BlockPos pos) {
		tag.putIntArray(key, new int[]{pos.getX(), pos.getY(), pos.getZ()});
	}

	public static int intAt(ListTag list, int index) {
		if (index < 0 || index >= list.size()) {
			return 0;
		}
		return list.get(index).asInt().orElse(0);
	}

	public static String stringAt(ListTag list, int index) {
		if (index < 0 || index >= list.size()) {
			return "";
		}
		Tag t = list.get(index);
		return t.asString().orElse("");
	}

	public static CompoundTag copy(CompoundTag tag) {
		return tag.copy();
	}
}
