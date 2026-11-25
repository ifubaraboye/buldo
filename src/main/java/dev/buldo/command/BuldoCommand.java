package dev.buldo.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.buldo.BuldoConfig;
import dev.buldo.plan.BuildPlan;
import dev.buldo.schem.Blueprint;
import dev.buldo.schem.SchematicLibrary;
import dev.buldo.site.BuildSite;
import dev.buldo.site.Containers;
import dev.buldo.site.SiteManager;
import dev.buldo.site.SiteState;
import dev.buldo.util.Fmt;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BuldoCommand {
	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SITE_NAMES =
			(context, builder) -> {
				SiteManager manager = SiteManager.current();
				if (manager == null) {
					return builder.buildFuture();
				}
				List<String> names = new ArrayList<>();
				for (BuildSite site : manager.sites()) {
					names.add(site.name);
				}
				return SharedSuggestionProvider.suggest(names, builder);
			};

	private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> SCHEMATIC_NAMES =
			(context, builder) -> {
				SiteManager manager = SiteManager.current();
				if (manager == null) {
					return builder.buildFuture();
				}
				return SharedSuggestionProvider.suggest(manager.library().names(), builder);
			};

	private BuldoCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("buldo")
				.executes(BuldoCommand::help)
				.then(Commands.literal("help").executes(BuldoCommand::help))
				.then(Commands.literal("config").executes(BuldoCommand::config))
				.then(Commands.literal("schematics")
						.requires(src -> true)
						.executes(BuldoCommand::schematics))
				.then(Commands.literal("inspect")
						.then(Commands.argument("file", StringArgumentType.greedyString())
								.suggests(SCHEMATIC_NAMES)
								.executes(BuldoCommand::inspect)))
				.then(Commands.literal("import")
						.requires(BuldoCommand::op)
						.then(Commands.argument("path", StringArgumentType.greedyString())
								.executes(BuldoCommand::importFile)))
				.then(Commands.literal("create")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.then(Commands.argument("schematic", StringArgumentType.string())
										.suggests(SCHEMATIC_NAMES)
										.executes(ctx -> create(ctx, "none", "none"))
										.then(Commands.argument("rotation", StringArgumentType.word())
												.executes(ctx -> create(ctx,
														StringArgumentType.getString(ctx, "rotation"), "none"))
												.then(Commands.argument("mirror", StringArgumentType.word())
														.executes(ctx -> create(ctx,
																StringArgumentType.getString(ctx, "rotation"),
																StringArgumentType.getString(ctx, "mirror"))))))))
				.then(Commands.literal("sites").executes(BuldoCommand::listSites))
				.then(Commands.literal("status").executes(BuldoCommand::listSites))
				.then(Commands.literal("info")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::info)))
				.then(Commands.literal("remove")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::remove)))
				.then(Commands.literal("move")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::move)))
				.then(Commands.literal("shift")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.then(Commands.argument("dx", IntegerArgumentType.integer(-512, 512))
										.then(Commands.argument("dy", IntegerArgumentType.integer(-512, 512))
												.then(Commands.argument("dz", IntegerArgumentType.integer(-512, 512))
														.executes(BuldoCommand::shift))))))
				.then(Commands.literal("tp")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::teleport)))
				.then(Commands.literal("start")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(ctx -> start(ctx, false))
								.then(Commands.literal("force").executes(ctx -> start(ctx, true)))))
				.then(Commands.literal("pause")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::pause)))
				.then(Commands.literal("resume")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::resume)))
				.then(Commands.literal("cancel")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::cancel)))
				.then(Commands.literal("undo")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::undo)))
				.then(Commands.literal("free")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.then(Commands.literal("on").executes(ctx -> free(ctx, true)))
								.then(Commands.literal("off").executes(ctx -> free(ctx, false)))))
				.then(Commands.literal("materials")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(BuldoCommand::materials)))
				.then(previewNode("preview"))
				.then(previewNode("ghost"))
				.then(Commands.literal("link")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(ctx -> link(ctx, null))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(ctx -> link(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))))))
				.then(Commands.literal("unlink")
						.requires(BuldoCommand::op)
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(SITE_NAMES)
								.executes(ctx -> unlink(ctx, null))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(ctx -> unlink(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))))))
				.then(Commands.literal("queue")
						.then(Commands.literal("list").executes(BuldoCommand::queueList))
						.then(Commands.literal("add")
								.requires(BuldoCommand::op)
								.then(Commands.argument("name", StringArgumentType.word())
										.suggests(SITE_NAMES)
										.executes(BuldoCommand::queueAdd)))
						.then(Commands.literal("remove")
								.requires(BuldoCommand::op)
								.then(Commands.argument("name", StringArgumentType.word())
										.suggests(SITE_NAMES)
										.executes(BuldoCommand::queueRemove)))
						.then(Commands.literal("clear")
								.requires(BuldoCommand::op)
								.executes(BuldoCommand::queueClear)))
				.then(Commands.literal("speed")
						.requires(BuldoCommand::op)
						.then(Commands.argument("blocksPerSecond", DoubleArgumentType.doubleArg(1.0, 40.0))
								.executes(BuldoCommand::speed)))
				.then(Commands.literal("debug")
						.requires(BuldoCommand::op)
						.then(Commands.literal("plantest")
								.then(Commands.argument("schematic", StringArgumentType.string())
										.suggests(SCHEMATIC_NAMES)
										.executes(ctx -> plantest(ctx, null))
										.then(Commands.argument("pos", BlockPosArgument.blockPos())
												.executes(ctx -> plantest(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))))))));
	}

	public static boolean op(CommandSourceStack source) {
		// Level 2 permissions: console always, players when they are op / cheats are on.
		return source.getEntity() == null
				|| source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	private static SiteManager manager(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = SiteManager.current();
		if (manager == null) {
			Fmt.send(ctx.getSource(), Fmt.err("No server is running."));
		}
		return manager;
	}

	private static LiteralArgumentBuilder<CommandSourceStack> previewNode(String literal) {
		return Commands.literal(literal)
				.then(Commands.argument("name", StringArgumentType.word())
						.suggests(SITE_NAMES)
						.then(Commands.argument("mode", StringArgumentType.word())
								.executes(BuldoCommand::preview)));
	}

	private static BuildSite site(CommandContext<CommandSourceStack> ctx, String argument) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return null;
		}
		BuildSite site = manager.get(StringArgumentType.getString(ctx, argument));
		if (site == null) {
			Fmt.send(ctx.getSource(), Fmt.err("No site named '" + StringArgumentType.getString(ctx, argument) + "'."));
		}
		return site;
	}

	// ---------------------------------------------------------------- read only

	private static int help(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		Fmt.send(src, Component.literal("Buldo ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal("— builds schematics block by block with exact items").withStyle(ChatFormatting.GRAY)));
		String[] lines = {
				"/buldo schematics — list files",
				"/buldo inspect <file> — size, materials, unknown blocks",
				"/buldo import <path> — copy a .schem/.nbt/.litematic into the schematic folder",
				"/buldo create <name> <file> [rotation] [mirror] — site at your feet",
				"/buldo link <name> — link the container you are looking at",
				"/buldo materials <name> — what is still needed",
				"/buldo start <name> [force] — start building",
				"/buldo free <name> on|off — build without items (creative only)",
				"/buldo pause|resume|cancel|undo <name>",
				"/buldo preview <name> on|off — particle preview before building",
				"/buldo move <name> · /buldo shift <name> <dx> <dy> <dz> — reposition a site",
				"/buldo queue add|remove|list|clear [name]",
				"/buldo speed <1-40> — blocks per second",
				"/buldo info <name> · /buldo sites · /buldo tp <name>",
		};
		for (String line : lines) {
			Fmt.send(src, line);
		}
		return 1;
	}

	private static int config(CommandContext<CommandSourceStack> ctx) {
		BuldoConfig cfg = BuldoConfig.get();
		Fmt.send(ctx.getSource(), "blocksPerSecond=" + cfg.blocksPerSecond
				+ " exactReplaceDefault=" + cfg.exactReplaceDefault
				+ " ghostDefault=" + cfg.ghostDefault
				+ " hud=" + cfg.hud
				+ " maxSites=" + cfg.maxSites
				+ " maxUndoFrames=" + cfg.maxUndoFrames
				+ " autoStartQueue=" + cfg.autoStartQueue);
		return 1;
	}

	private static int schematics(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		List<String> names = manager.library().names();
		if (names.isEmpty()) {
			Fmt.send(ctx.getSource(), Fmt.warn("No schematics found. Put files in:"));
			for (var dir : manager.library().dirs()) {
				Fmt.send(ctx.getSource(), "  " + dir);
			}
			return 1;
		}
		Fmt.send(ctx.getSource(), "Schematics (" + names.size() + "): " + String.join(", ", names));
		return 1;
	}

	private static int inspect(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		String file = StringArgumentType.getString(ctx, "file");
		try {
			Blueprint blueprint = manager.library().load(file);
			ServerLevel level = ctx.getSource().getLevel();
			BuildPlan plan = BuildPlan.build(blueprint, new BlockPos(0, level.getMinY() + 1, 0),
					Rotation.NONE, Mirror.NONE, true, manager.blockLookup(),
					manager.config().freeBlockSet(), level);
			Fmt.send(ctx.getSource(), Component.literal(blueprint.name).withStyle(ChatFormatting.AQUA)
					.append(Component.literal(" (" + blueprint.format + ", DV " + blueprint.dataVersion + ")").withStyle(ChatFormatting.DARK_GRAY)));
			Fmt.send(ctx.getSource(), "size " + blueprint.sizeX + " x " + blueprint.sizeY + " x " + blueprint.sizeZ
					+ " (" + Fmt.num(blueprint.volume()) + " cells, " + Fmt.num(plan.solidCells) + " blocks, "
					+ Fmt.num(plan.airCells) + " air)"
					+ (blueprint.offsetX != 0 || blueprint.offsetY != 0 || blueprint.offsetZ != 0
							? "  offset " + blueprint.offsetX + "," + blueprint.offsetY + "," + blueprint.offsetZ : ""));
			if (!blueprint.blockEntities.isEmpty()) {
				Fmt.send(ctx.getSource(), "block entities: " + blueprint.blockEntities.size()
						+ "  entities: " + blueprint.entities.size());
			}
			int totalItems = 0;
			for (int v : plan.required.values()) {
				totalItems += v;
			}
			Fmt.send(ctx.getSource(), "materials: " + Fmt.num(totalItems) + " items across " + plan.required.size() + " kinds");
			List<Map.Entry<Item, Integer>> entries = new ArrayList<>(plan.required.entrySet());
			entries.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
			int shown = 0;
			for (Map.Entry<Item, Integer> entry : entries) {
				if (shown++ >= 12) {
					Fmt.send(ctx.getSource(), "  … and " + (entries.size() - 12) + " more");
					break;
				}
				Fmt.send(ctx.getSource(), "  " + Fmt.num(entry.getValue()) + " x " + Fmt.item(entry.getKey()));
			}
			if (!plan.unknown.isEmpty()) {
				Fmt.send(ctx.getSource(), Fmt.warn("unknown block states: " + plan.unknown.size() + " kinds"));
				int count = 0;
				for (Map.Entry<String, Integer> entry : plan.unknown.entrySet()) {
					if (count++ >= 6) {
						break;
					}
					Fmt.send(ctx.getSource(), "  " + entry.getValue() + " x " + entry.getKey());
				}
			}
			if (!plan.unobtainable.isEmpty()) {
				Fmt.send(ctx.getSource(), Fmt.warn("no item (treated as free): " + plan.unobtainable.size() + " kinds"));
			}
			if (plan.outOfBounds > 0) {
				Fmt.send(ctx.getSource(), Fmt.warn(plan.outOfBounds + " cells are outside the world height"));
			}
			return 1;
		} catch (Exception e) {
			Fmt.send(ctx.getSource(), Fmt.err(e.getMessage() == null ? e.toString() : e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- mutations

	private static int importFile(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		String path = StringArgumentType.getString(ctx, "path");
		try {
			String name = manager.library().importFile(path);
			Blueprint blueprint = manager.library().load(name);
			Fmt.send(ctx.getSource(), Fmt.ok("Imported as '" + name + "' (" + blueprint.sizeX + "x"
					+ blueprint.sizeY + "x" + blueprint.sizeZ + ", " + blueprint.format + ")."));
			return 1;
		} catch (Exception e) {
			Fmt.send(ctx.getSource(), Fmt.err(e.getMessage() == null ? e.toString() : e.getMessage()));
			return 0;
		}
	}

	private static int create(CommandContext<CommandSourceStack> ctx, String rotationArg, String mirrorArg) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "name");
		String schematic = StringArgumentType.getString(ctx, "schematic");
		Rotation rotation = parseRotation(rotationArg);
		Mirror mirror = parseMirror(mirrorArg);
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos origin = BlockPos.containing(ctx.getSource().getPosition());
		if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
			// Put the structure in front of the player instead of on top of them.
			origin = player.blockPosition().relative(player.getDirection(), 3);
		}
		try {
			BuildSite site = manager.create(name, schematic, level, origin, rotation, mirror,
					manager.config().exactReplaceDefault);
			BuildPlan plan = site.plan;
			Fmt.send(ctx.getSource(), Fmt.ok("Created '" + site.name + "' from " + site.schematic + "."));
			Fmt.send(ctx.getSource(), "area " + plan.min.getX() + "," + plan.min.getY() + "," + plan.min.getZ()
					+ " to " + plan.max.getX() + "," + plan.max.getY() + "," + plan.max.getZ()
					+ " · " + Fmt.num(plan.solidCells) + " blocks, " + Fmt.num(plan.required.values().stream().mapToInt(Integer::intValue).sum()) + " items");
			if (!plan.unknown.isEmpty()) {
				Fmt.send(ctx.getSource(), Fmt.warn(plan.unknown.size() + " unknown block state kinds will be skipped (see /buldo inspect " + site.schematic + ")."));
			}
			Fmt.send(ctx.getSource(), "Next: /buldo link " + site.name + " (look at a container) then /buldo start " + site.name);
			return 1;
		} catch (Exception e) {
			Fmt.send(ctx.getSource(), Fmt.err(e.getMessage() == null ? e.toString() : e.getMessage()));
			return 0;
		}
	}

	private static int listSites(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		if (manager.sites().isEmpty()) {
			Fmt.send(ctx.getSource(), Fmt.warn("No sites yet. /buldo create <name> <file>"));
			return 1;
		}
		for (BuildSite site : manager.sites()) {
			Fmt.send(ctx.getSource(), String.format(Locale.ROOT, "%-16s %-20s %3d%%  %s @ %d,%d,%d%s%s%s",
					site.name, site.state.label(), site.progressPercent(), site.schematic,
					site.origin.getX(), site.origin.getY(), site.origin.getZ(),
					site.free ? "  free" : "",
					site.missing.isEmpty() ? "" : "  missing " + site.missing.values().stream().mapToInt(Integer::intValue).sum()));
		}
		if (!manager.queue().isEmpty()) {
			Fmt.send(ctx.getSource(), "queue: " + String.join(" -> ", manager.queue()));
		}
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		BuildPlan plan = manager.plan(site);
		Fmt.send(ctx.getSource(), Component.literal(site.name).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(" · " + site.state.label()).withStyle(ChatFormatting.GRAY)));
		Fmt.send(ctx.getSource(), "schematic " + site.schematic + " in " + site.dimension
				+ " at " + site.origin.getX() + "," + site.origin.getY() + "," + site.origin.getZ()
				+ " rot " + site.rotation.name().toLowerCase(Locale.ROOT)
				+ " mirror " + site.mirror.name().toLowerCase(Locale.ROOT)
				+ (site.exactReplace ? " exact" : ""));
		if (plan != null) {
			Fmt.send(ctx.getSource(), "progress " + site.progressPercent() + "% · placed "
					+ Fmt.num(site.placed) + " · skipped " + Fmt.num(site.skipped)
					+ " · " + Fmt.num(plan.solidCells) + " blocks, " + Fmt.num(plan.cells.size()) + " cells"
					+ (plan.unknown.isEmpty() ? "" : " · unknown " + plan.unknown.size())
					+ (plan.unobtainable.isEmpty() ? "" : " · no item " + plan.unobtainable.size()));
		}
		Fmt.send(ctx.getSource(), "containers " + site.containers.size() + " · undo frames " + site.undo.size()
				+ " · ghost " + (site.ghost ? "on" : "off")
				+ (site.free ? " · FREE MODE" : "")
				+ (site.lastError.isEmpty() ? "" : " · " + site.lastError));
		if (!site.missing.isEmpty()) {
			Fmt.send(ctx.getSource(), Fmt.warn("missing: " + site.missing));
		}
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.remove(site)) {
			Fmt.send(ctx.getSource(), Fmt.err("Pause the site before removing it."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Removed '" + site.name + "'."));
		return 1;
	}

	private static int move(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (site.state.active() || site.state == SiteState.DONE) {
			Fmt.send(ctx.getSource(), Fmt.err("Cannot move a site while it is active or done."));
			return 0;
		}
		reposition(manager, site, BlockPos.containing(ctx.getSource().getPosition()));
		Fmt.send(ctx.getSource(), Fmt.ok("Moved '" + site.name + "' to "
				+ site.origin.getX() + "," + site.origin.getY() + "," + site.origin.getZ() + "."));
		describeArea(ctx, manager, site);
		return 1;
	}

	private static int shift(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (site.state.active() || site.state == SiteState.DONE) {
			Fmt.send(ctx.getSource(), Fmt.err("Cannot move a site while it is active or done."));
			return 0;
		}
		int dx = IntegerArgumentType.getInteger(ctx, "dx");
		int dy = IntegerArgumentType.getInteger(ctx, "dy");
		int dz = IntegerArgumentType.getInteger(ctx, "dz");
		reposition(manager, site, site.origin.offset(dx, dy, dz));
		Fmt.send(ctx.getSource(), Fmt.ok("Shifted '" + site.name + "' by "
				+ dx + " " + dy + " " + dz + "; origin is now "
				+ site.origin.getX() + "," + site.origin.getY() + "," + site.origin.getZ() + "."));
		describeArea(ctx, manager, site);
		return 1;
	}

	/** Moves the anchor of an idle site and clears the cached plan so it is rebuilt. */
	private static void reposition(SiteManager manager, BuildSite site, BlockPos origin) {
		site.origin = origin;
		site.plan = null;
		site.cursor = 0;
		site.placed = 0;
		site.skipped = 0;
		site.state = SiteState.READY;
		manager.markDirty();
		manager.save();
	}

	private static void describeArea(CommandContext<CommandSourceStack> ctx, SiteManager manager, BuildSite site) {
		BuildPlan plan = manager.plan(site);
		if (plan == null) {
			return;
		}
		Fmt.send(ctx.getSource(), "area " + plan.min.getX() + "," + plan.min.getY() + "," + plan.min.getZ()
				+ " to " + plan.max.getX() + "," + plan.max.getY() + "," + plan.max.getZ()
				+ " (" + (plan.max.getX() - plan.min.getX() + 1) + "x"
				+ (plan.max.getY() - plan.min.getY() + 1) + "x"
				+ (plan.max.getZ() - plan.min.getZ() + 1) + ")");
		Fmt.send(ctx.getSource(), "Preview with /buldo preview " + site.name + " on · nudge with /buldo shift "
				+ site.name + " <dx> <dy> <dz>");
	}

	private static int teleport(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		ServerLevel level = manager.level(site);
		if (level == null) {
			Fmt.send(ctx.getSource(), Fmt.err("That dimension is not loaded."));
			return 0;
		}
		try {
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			double x = site.origin.getX() + 0.5;
			double y = site.origin.getY();
			double z = site.origin.getZ() + 0.5;
			if (player.level() == level) {
				player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
			} else {
				player.teleportTo(level, x, y, z, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
			}
			Fmt.send(ctx.getSource(), Fmt.ok("Teleported to '" + site.name + "'."));
			return 1;
		} catch (Exception e) {
			Fmt.send(ctx.getSource(), Fmt.err("Only a player can teleport."));
			return 0;
		}
	}

	private static int start(CommandContext<CommandSourceStack> ctx, boolean force) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.start(site, force)) {
			Fmt.send(ctx.getSource(), Fmt.err("Could not start '" + site.name + "'"
					+ (site.lastError.isEmpty() ? "." : ": " + site.lastError)));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("'" + site.name + "' is " + site.state.label() + "."));
		return 1;
	}

	private static int pause(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.pause(site)) {
			Fmt.send(ctx.getSource(), Fmt.err("'" + site.name + "' is not running."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Paused '" + site.name + "' at " + site.progressPercent() + "%."));
		return 1;
	}

	private static int resume(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.resume(site)) {
			Fmt.send(ctx.getSource(), Fmt.err("Could not resume '" + site.name + "'."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("'" + site.name + "' is " + site.state.label() + "."));
		return 1;
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.cancel(site)) {
			Fmt.send(ctx.getSource(), Fmt.err("'" + site.name + "' is not running."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Cancelled '" + site.name + "'. Unplaced blocks were never taken."));
		return 1;
	}

	private static int undo(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (site.state == SiteState.BUILDING) {
			Fmt.send(ctx.getSource(), Fmt.err("Pause or cancel '" + site.name + "' first."));
			return 0;
		}
		if (!manager.undo(site)) {
			Fmt.send(ctx.getSource(), Fmt.err("Nothing to undo for '" + site.name + "'."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Restored the blocks that '" + site.name + "' had replaced."));
		return 1;
	}

	private static int free(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (enabled) {
			ServerPlayer player = ctx.getSource().getPlayer();
			if (player != null && !player.isCreative()) {
				Fmt.send(ctx.getSource(), Fmt.err("Free mode needs creative mode (or run it from the console)."));
				return 0;
			}
		}
		site.free = enabled;
		site.missing.clear();
		site.lastError = "";
		if (enabled && (site.state == SiteState.WAITING_MATERIALS || site.state == SiteState.BLOCKED)) {
			site.state = SiteState.BUILDING;
			site.carry = 0;
		}
		manager.markDirty();
		manager.save();
		if (enabled) {
			Fmt.send(ctx.getSource(), Fmt.ok("Free mode for '" + site.name
					+ "': blocks are placed without taking any items."));
			Fmt.send(ctx.getSource(), "No containers are needed. It stays free across restarts until /buldo free "
					+ site.name + " off.");
		} else {
			Fmt.send(ctx.getSource(), Fmt.ok("Free mode off for '" + site.name
					+ "': new placements use the linked containers again."));
		}
		return 1;
	}

	private static int link(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		ServerLevel level = manager.level(site);
		if (level == null) {
			Fmt.send(ctx.getSource(), Fmt.err("That dimension is not loaded."));
			return 0;
		}
		if (pos == null) {
			pos = targetPos(ctx.getSource());
		}
		if (!Containers.isContainer(level, pos)) {
			Fmt.send(ctx.getSource(), Fmt.err("No container at " + pos.toShortString() + "."));
			return 0;
		}
		List<BlockPos> expanded = Containers.expand(level, pos);
		int added = 0;
		for (BlockPos candidate : expanded) {
			if (!site.containers.contains(candidate)) {
				site.containers.add(candidate);
				added++;
			}
		}
		manager.markDirty();
		manager.save();
		Fmt.send(ctx.getSource(), Fmt.ok("Linked " + added + " container block(s) to '" + site.name
				+ "' (total " + site.containers.size() + ")."));
		Map<Item, Integer> contents = Containers.snapshot(level, expanded);
		if (!contents.isEmpty()) {
			List<Map.Entry<Item, Integer>> top = new ArrayList<>(contents.entrySet());
			top.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
			StringBuilder sb = new StringBuilder("contains: ");
			int shown = 0;
			for (Map.Entry<Item, Integer> entry : top) {
				if (shown++ >= 6) {
					sb.append(", …");
					break;
				}
				if (shown > 1) {
					sb.append(", ");
				}
				sb.append(Fmt.num(entry.getValue())).append(' ').append(Fmt.item(entry.getKey()));
			}
			Fmt.send(ctx.getSource(), sb.toString());
		}
		return 1;
	}

	private static int unlink(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (pos == null) {
			int count = site.containers.size();
			site.containers.clear();
			manager.markDirty();
			manager.save();
			Fmt.send(ctx.getSource(), Fmt.ok("Unlinked " + count + " container block(s) from '" + site.name + "'."));
			return 1;
		}
		int before = site.containers.size();
		site.containers.removeIf(candidate -> candidate.equals(pos));
		manager.markDirty();
		manager.save();
		Fmt.send(ctx.getSource(), Fmt.ok("Unlinked " + (before - site.containers.size()) + " container block(s)."));
		return 1;
	}

	private static int materials(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		ServerLevel level = manager.level(site);
		BuildPlan plan = manager.plan(site);
		if (level == null || plan == null) {
			Fmt.send(ctx.getSource(), Fmt.err("Cannot evaluate '" + site.name + "': "
					+ (site.lastError.isEmpty() ? "no plan" : site.lastError)));
			return 0;
		}
		if (site.free) {
			int remaining = 0;
			for (int i = Math.max(0, site.cursor); i < plan.cells.size(); i++) {
				BuildPlan.Cell cell = plan.cells.get(i);
				if (!cell.state.isAir() && !level.getBlockState(cell.pos).equals(cell.state)) {
					remaining++;
				}
			}
			Fmt.send(ctx.getSource(), Component.literal(site.name).withStyle(ChatFormatting.AQUA)
					.append(Component.literal(" is in free mode — " + Fmt.num(remaining)
							+ " blocks to go, no items needed.").withStyle(ChatFormatting.GREEN)));
			if (!plan.unknown.isEmpty()) {
				int blocks = plan.unknown.values().stream().mapToInt(Integer::intValue).sum();
				Fmt.send(ctx.getSource(), Fmt.warn("Unknown block states (skipped): " + blocks + " blocks, "
						+ plan.unknown.size() + " kinds."));
			}
			return 1;
		}
		Map<Item, Integer> need = new LinkedHashMap<>();
		for (int i = Math.max(0, site.cursor); i < plan.cells.size(); i++) {
			BuildPlan.Cell cell = plan.cells.get(i);
			if (cell.cost <= 0 || level.getBlockState(cell.pos).equals(cell.state)) {
				continue;
			}
			need.merge(cell.item, cell.cost, Integer::sum);
		}
		int total = need.values().stream().mapToInt(Integer::intValue).sum();
		Fmt.send(ctx.getSource(), Component.literal(site.name).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(" needs " + Fmt.num(total) + " items, "
						+ Fmt.num(plan.solidCells - site.placed) + " blocks to go").withStyle(ChatFormatting.GRAY)));
		List<Map.Entry<Item, Integer>> entries = new ArrayList<>(need.entrySet());
		entries.sort(Comparator.comparingInt((Map.Entry<Item, Integer> e) -> -e.getValue()));
		int shown = 0;
		int totalMissing = 0;
		for (Map.Entry<Item, Integer> entry : entries) {
			int have = manager.haveItem(site, entry.getKey(), level);
			int missing = Math.max(0, entry.getValue() - have);
			totalMissing += missing;
			if (shown++ < 16) {
				Component line = Component.literal("  " + Fmt.num(have) + " / " + Fmt.num(entry.getValue())
						+ "  " + Fmt.item(entry.getKey()))
						.withStyle(missing > 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
				Fmt.send(ctx.getSource(), line);
			}
		}
		if (entries.size() > 16) {
			Fmt.send(ctx.getSource(), "  … and " + (entries.size() - 16) + " more kinds");
		}
		if (!plan.unknown.isEmpty()) {
			int blocks = plan.unknown.values().stream().mapToInt(Integer::intValue).sum();
			Fmt.send(ctx.getSource(), Fmt.warn("Unknown block states (skipped): " + blocks + " blocks, "
					+ plan.unknown.size() + " kinds."));
		}
		if (totalMissing > 0) {
			Fmt.send(ctx.getSource(), Fmt.warn("Short by " + Fmt.num(totalMissing) + " items."));
		} else {
			Fmt.send(ctx.getSource(), Fmt.ok("Everything needed is in the linked containers."));
		}
		return 1;
	}

	private static int preview(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		String mode = StringArgumentType.getString(ctx, "mode");
		site.ghost = mode.equalsIgnoreCase("on") || mode.equalsIgnoreCase("true")
				|| mode.equalsIgnoreCase("yes") || mode.equalsIgnoreCase("1");
		manager.markDirty();
		Fmt.send(ctx.getSource(), Fmt.ok("Preview for '" + site.name + "' is " + (site.ghost ? "on" : "off") + "."));
		if (site.ghost) {
			describeArea(ctx, manager, site);
		}
		return 1;
	}

	private static int queueAdd(CommandContext<CommandSourceStack> ctx) {
		BuildSite site = site(ctx, "name");
		SiteManager manager = manager(ctx);
		if (site == null || manager == null) {
			return 0;
		}
		if (!manager.queueAdd(site)) {
			Fmt.send(ctx.getSource(), Fmt.warn("'" + site.name + "' is already queued."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Queued '" + site.name + "' (" + manager.queue().size() + " in queue)."));
		return 1;
	}

	private static int queueRemove(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "name");
		if (!manager.queueRemove(name)) {
			Fmt.send(ctx.getSource(), Fmt.err("'" + name + "' is not in the queue."));
			return 0;
		}
		Fmt.send(ctx.getSource(), Fmt.ok("Removed '" + name + "' from the queue."));
		return 1;
	}

	private static int queueClear(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		manager.queueClear();
		Fmt.send(ctx.getSource(), Fmt.ok("Queue cleared."));
		return 1;
	}

	private static int queueList(CommandContext<CommandSourceStack> ctx) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		if (manager.queue().isEmpty()) {
			Fmt.send(ctx.getSource(), Fmt.warn("Queue is empty."));
		} else {
			Fmt.send(ctx.getSource(), "Queue: " + String.join(" -> ", manager.queue()));
		}
		return 1;
	}

	private static int speed(CommandContext<CommandSourceStack> ctx) {
		double value = DoubleArgumentType.getDouble(ctx, "blocksPerSecond");
		BuldoConfig cfg = BuldoConfig.get();
		cfg.blocksPerSecond = Math.max(1, Math.min(40, value));
		cfg.save();
		Fmt.send(ctx.getSource(), Fmt.ok("Build speed is " + cfg.blocksPerSecond + " blocks/second."));
		return 1;
	}

	// ---------------------------------------------------------------- debug

	/**
	 * Places a whole schematic at once, checks the world matches, then puts the
	 * original blocks back. Used to verify parsing, placement and undo capture
	 * without a player and without spending materials.
	 */
	private static int plantest(CommandContext<CommandSourceStack> ctx, BlockPos requested) {
		SiteManager manager = manager(ctx);
		if (manager == null) {
			return 0;
		}
		String schematic = StringArgumentType.getString(ctx, "schematic");
		ServerLevel level = ctx.getSource().getLevel();
		BlockPos pos = requested;
		if (pos == null) {
			BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
			pos = new BlockPos(at.getX(), Math.min(level.getMaxY() - 64, Math.max(level.getMinY() + 8, 120)), at.getZ());
		}
		HolderLookup<Block> lookup = manager.blockLookup();
		try {
			Blueprint blueprint = manager.library().load(schematic);
			BuildPlan plan = BuildPlan.build(blueprint, pos, Rotation.NONE, Mirror.NONE, true,
					lookup, manager.config().freeBlockSet(), level);

			Map<Long, BlockState> before = new LinkedHashMap<>();
			Map<Long, CompoundTag> beforeBe = new LinkedHashMap<>();
			for (BuildPlan.Cell cell : plan.cells) {
				long key = cell.pos.asLong();
				before.put(key, level.getBlockState(cell.pos));
				BlockEntity existing = level.getBlockEntity(cell.pos);
				if (existing != null) {
					beforeBe.put(key, existing.saveWithFullMetadata(level.registryAccess()));
				}
			}

			int placed = 0;
			int already = 0;
			int failed = 0;
			long start = System.nanoTime();
			for (BuildPlan.Cell cell : plan.cells) {
				if (level.getBlockState(cell.pos).equals(cell.state)) {
					already++;
					continue;
				}
				boolean ok = level.setBlock(cell.pos, cell.state, Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
				if (ok) {
					placed++;
				} else {
					failed++;
				}
				if (cell.be != null) {
					BlockEntity blockEntity = BlockEntity.loadStatic(cell.pos, cell.state, cell.be, level.registryAccess());
					if (blockEntity != null) {
						level.setBlockEntity(blockEntity);
					}
				}
			}
			long placeMs = (System.nanoTime() - start) / 1_000_000;

			int mismatches = 0;
			for (BuildPlan.Cell cell : plan.cells) {
				if (!level.getBlockState(cell.pos).equals(cell.state)) {
					mismatches++;
				}
			}

			int restored = 0;
			for (int i = plan.cells.size() - 1; i >= 0; i--) {
				BuildPlan.Cell cell = plan.cells.get(i);
				BlockState previous = before.get(cell.pos.asLong());
				if (previous == null) {
					continue;
				}
				level.setBlock(cell.pos, previous, Block.UPDATE_CLIENTS);
				CompoundTag be = beforeBe.get(cell.pos.asLong());
				if (be != null) {
					BlockEntity blockEntity = BlockEntity.loadStatic(cell.pos, previous, be, level.registryAccess());
					if (blockEntity != null) {
						level.setBlockEntity(blockEntity);
					}
				}
				restored++;
			}
			int restoreMismatches = 0;
			for (BuildPlan.Cell cell : plan.cells) {
				BlockState previous = before.get(cell.pos.asLong());
				if (previous != null && !level.getBlockState(cell.pos).equals(previous)) {
					restoreMismatches++;
				}
			}

			Fmt.send(ctx.getSource(), Component.literal("plantest " + blueprint.name).withStyle(ChatFormatting.AQUA));
			Fmt.send(ctx.getSource(), "cells " + plan.cells.size() + " (" + plan.solidCells + " solid, "
					+ plan.airCells + " air) at " + pos.toShortString()
					+ " · placed " + placed + " already " + already + " failed " + failed + " in " + placeMs + "ms");
			Fmt.send(ctx.getSource(), "state mismatches " + mismatches + " · restored " + restored
					+ " · restore mismatches " + restoreMismatches);
			if (!plan.unknown.isEmpty()) {
				Fmt.send(ctx.getSource(), Fmt.warn("unknown " + plan.unknown.size() + " kinds: "
						+ String.join(", ", plan.unknown.keySet())));
			}
			if (!plan.unobtainable.isEmpty()) {
				Fmt.send(ctx.getSource(), "no item: " + String.join(", ", plan.unobtainable.keySet()));
			}
			return mismatches == 0 && restoreMismatches == 0 ? 1 : 0;
		} catch (Exception e) {
			Fmt.send(ctx.getSource(), Fmt.err(e.getMessage() == null ? e.toString() : e.getMessage()));
			return 0;
		}
	}

	// ---------------------------------------------------------------- parsing

	private static BlockPos targetPos(CommandSourceStack src) {
		if (src.getEntity() instanceof ServerPlayer player) {
			HitResult hit = player.pick(8.0, 0.0F, false);
			if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
				return blockHit.getBlockPos();
			}
			return player.blockPosition();
		}
		return BlockPos.containing(src.getPosition());
	}

	private static Rotation parseRotation(String value) {
		if (value == null) {
			return Rotation.NONE;
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "90", "cw90", "clockwise_90" -> Rotation.CLOCKWISE_90;
			case "180", "cw180" -> Rotation.CLOCKWISE_180;
			case "270", "ccw90", "counterclockwise_90" -> Rotation.COUNTERCLOCKWISE_90;
			default -> Rotation.NONE;
		};
	}

	private static Mirror parseMirror(String value) {
		if (value == null) {
			return Mirror.NONE;
		}
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "left_right", "lr" -> Mirror.LEFT_RIGHT;
			case "front_back", "fb" -> Mirror.FRONT_BACK;
			default -> Mirror.NONE;
		};
	}
}
