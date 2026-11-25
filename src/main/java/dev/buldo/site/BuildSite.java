package dev.buldo.site;

import dev.buldo.plan.BuildPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One build site: where it is, what it builds, how far it got and its undo history. */
public final class BuildSite {
	public static final class UndoFrame {
		public final List<UndoCell> cells = new ArrayList<>();
	}

	public record UndoCell(BlockPos pos, BlockState previous, CompoundTag previousBlockEntity) {
	}

	public String name;
	public String schematic;
	public String dimension = "minecraft:overworld";
	public BlockPos origin = BlockPos.ZERO;
	public Rotation rotation = Rotation.NONE;
	public Mirror mirror = Mirror.NONE;
	public boolean exactReplace = true;
	public boolean free = false;
	public BuildMode mode = BuildMode.WORKERS;
	public int workers = 2;
	public SiteState state = SiteState.EMPTY;
	public int cursor;
	public int placed;
	public int skipped;
	public double carry;
	public long startedTick;
	public boolean ghost = true;
	public String sourceHash = "";
	public String lastError = "";
	public final List<BlockPos> containers = new ArrayList<>();
	public final List<UndoFrame> undo = new ArrayList<>();
	public final Map<String, Integer> missing = new LinkedHashMap<>();

	// Runtime only.
	public BuildPlan plan;
	public int ghostCursor;
	public int lastMaterialsCheck;
	public int placedSinceSave;
	public long blocksThisSecond;
	public long blocksLastSecondCount;
	public double measuredRate;

	public BuildSite() {
	}

	public UndoFrame currentFrame() {
		if (undo.isEmpty()) {
			undo.add(new UndoFrame());
		}
		return undo.get(undo.size() - 1);
	}

	public void startFrame(int maxFrames) {
		undo.add(new UndoFrame());
		while (undo.size() > Math.max(1, maxFrames)) {
			undo.remove(0);
		}
	}

	public void recordUndo(BlockPos pos, BlockState previous, CompoundTag previousBlockEntity, int maxCells) {
		if (state == SiteState.DONE) {
			return;
		}
		UndoFrame frame = currentFrame();
		if (frame.cells.size() >= maxCells) {
			return;
		}
		frame.cells.add(new UndoCell(pos.immutable(), previous, previousBlockEntity));
	}

	public int progressPercent() {
		if (state == SiteState.DONE) {
			return 100;
		}
		if (plan == null || plan.cells.isEmpty()) {
			return 0;
		}
		return (int) Math.min(100, Math.round(100.0 * cursor / plan.cells.size()));
	}

	public CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		tag.putString("name", name);
		tag.putString("schematic", schematic);
		tag.putString("dimension", dimension);
		tag.putIntArray("origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
		tag.putInt("rotation", rotation.ordinal());
		tag.putInt("mirror", mirror.ordinal());
		tag.putBoolean("exactReplace", exactReplace);
		tag.putBoolean("free", free);
		tag.putString("mode", mode.id());
		tag.putInt("workers", workers);
		tag.putString("state", state.name());
		tag.putInt("cursor", cursor);
		tag.putInt("placed", placed);
		tag.putInt("skipped", skipped);
		tag.putDouble("carry", carry);
		tag.putLong("startedTick", startedTick);
		tag.putBoolean("ghost", ghost);
		tag.putString("sourceHash", sourceHash == null ? "" : sourceHash);
		tag.putString("lastError", lastError == null ? "" : lastError);

		int[] flat = new int[containers.size() * 3];
		for (int i = 0; i < containers.size(); i++) {
			BlockPos pos = containers.get(i);
			flat[i * 3] = pos.getX();
			flat[i * 3 + 1] = pos.getY();
			flat[i * 3 + 2] = pos.getZ();
		}
		tag.putIntArray("containers", flat);

		ListTag undoTag = new ListTag();
		for (UndoFrame frame : undo) {
			CompoundTag frameTag = new CompoundTag();
			ListTag cellTags = new ListTag();
			for (UndoCell cell : frame.cells) {
				CompoundTag cellTag = new CompoundTag();
				cellTag.putIntArray("p", new int[]{cell.pos().getX(), cell.pos().getY(), cell.pos().getZ()});
				cellTag.put("s", NbtUtils.writeBlockState(cell.previous()));
				if (cell.previousBlockEntity() != null) {
					cellTag.put("be", cell.previousBlockEntity());
				}
				cellTags.add(cellTag);
			}
			frameTag.put("cells", cellTags);
			undoTag.add(frameTag);
		}
		tag.put("undo", undoTag);

		ListTag missingTag = new ListTag();
		for (Map.Entry<String, Integer> entry : missing.entrySet()) {
			CompoundTag m = new CompoundTag();
			m.putString("item", entry.getKey());
			m.putInt("count", entry.getValue());
			missingTag.add(m);
		}
		tag.put("missing", missingTag);
		return tag;
	}

	public static BuildSite fromTag(CompoundTag tag, HolderGetter<Block> blocks) {
		BuildSite site = new BuildSite();
		site.name = tag.getStringOr("name", "");
		if (site.name.isEmpty()) {
			return null;
		}
		site.schematic = tag.getStringOr("schematic", "");
		site.dimension = tag.getStringOr("dimension", "minecraft:overworld");
		int[] origin = tag.getIntArray("origin").orElse(new int[]{0, 0, 0});
		if (origin.length >= 3) {
			site.origin = new BlockPos(origin[0], origin[1], origin[2]);
		}
		int rot = tag.getIntOr("rotation", 0);
		Rotation[] rotations = Rotation.values();
		site.rotation = rotations[Math.max(0, Math.min(rotations.length - 1, rot))];
		int mir = tag.getIntOr("mirror", 0);
		Mirror[] mirrors = Mirror.values();
		site.mirror = mirrors[Math.max(0, Math.min(mirrors.length - 1, mir))];
		site.exactReplace = tag.getBooleanOr("exactReplace", true);
		site.free = tag.getBooleanOr("free", false);
		site.mode = BuildMode.parse(tag.getStringOr("mode", "workers"));
		site.workers = Math.max(1, Math.min(8, tag.getIntOr("workers", 2)));
		try {
			site.state = SiteState.valueOf(tag.getStringOr("state", "READY"));
		} catch (IllegalArgumentException e) {
			site.state = SiteState.READY;
		}
		site.cursor = Math.max(0, tag.getIntOr("cursor", 0));
		site.placed = Math.max(0, tag.getIntOr("placed", 0));
		site.skipped = Math.max(0, tag.getIntOr("skipped", 0));
		site.carry = tag.getDoubleOr("carry", 0.0);
		site.startedTick = tag.getLongOr("startedTick", 0L);
		site.ghost = tag.getBooleanOr("ghost", true);
		site.sourceHash = tag.getStringOr("sourceHash", "");
		site.lastError = tag.getStringOr("lastError", "");

		int[] flat = tag.getIntArray("containers").orElse(new int[0]);
		for (int i = 0; i + 2 < flat.length; i += 3) {
			site.containers.add(new BlockPos(flat[i], flat[i + 1], flat[i + 2]));
		}

		ListTag undoTag = tag.getListOrEmpty("undo");
		for (int f = 0; f < undoTag.size(); f++) {
			UndoFrame frame = new UndoFrame();
			ListTag cellTags = undoTag.getCompoundOrEmpty(f).getListOrEmpty("cells");
			for (int c = 0; c < cellTags.size(); c++) {
				CompoundTag cellTag = cellTags.getCompoundOrEmpty(c);
				int[] p = cellTag.getIntArray("p").orElse(null);
				if (p == null || p.length < 3) {
					continue;
				}
				var stateTag = cellTag.getCompound("s");
				if (stateTag.isEmpty()) {
					continue;
				}
				BlockState previous = NbtUtils.readBlockState(blocks, stateTag.get());
				CompoundTag be = cellTag.getCompound("be").orElse(null);
				frame.cells.add(new UndoCell(new BlockPos(p[0], p[1], p[2]), previous,
						be == null ? null : be.copy()));
			}
			site.undo.add(frame);
		}

		ListTag missingTag = tag.getListOrEmpty("missing");
		for (int i = 0; i < missingTag.size(); i++) {
			CompoundTag m = missingTag.getCompoundOrEmpty(i);
			String item = m.getStringOr("item", "");
			if (!item.isEmpty()) {
				site.missing.put(item, m.getIntOr("count", 0));
			}
		}
		return site;
	}
}
