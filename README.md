# Buldo

Buldo builds schematic files one block at a time, paid for with the exact items
in containers **you** link to a build site. No substitutions, no magic. It is a
Fabric mod for Minecraft **26.2** (Java 25), MIT licensed.

It runs on the server, so vanilla clients can join; an optional client-side
camera gives you an RTS-style overview of the build.

The mod is server-side first: the preview is drawn with particles, so Iris/Sodium
style render mods are not involved. The overview camera is the only client
feature, and it is optional.

## Install

1. Fabric Loader `>= 0.19.3` for Minecraft 26.2, with Fabric API.
2. Drop `buldo-0.1.0.jar` into `.minecraft/mods` (on the server too).
3. Put schematic files in `.minecraft/schematics` (Litematica's folder) or
   `.minecraft/config/buldo/schematics`.

Supported files: Sponge `.schem` (v1–v3), vanilla structure `.nbt`, and
Litematica `.litematic` (v1–v7, multi region). Unknown block states are
reported and skipped, never guessed.

## Quick start (survival)

```
/buldo schematics                     list files Buldo can see
/buldo inspect house-70               size, materials, unknown blocks
/buldo create myhouse house-70        site 3 blocks in front of you
/buldo link myhouse                   link the container you are looking at
/buldo materials myhouse              what is still missing
/buldo start myhouse                  start placing, one block at a time
```

While it runs: `/buldo pause myhouse`, `/buldo resume myhouse`,
`/buldo cancel myhouse`, `/buldo info myhouse`, `/buldo sites`.
`/buldo undo myhouse` restores the blocks the last build session replaced.

### Commands

| Command | What it does |
| --- | --- |
| `/buldo help` | short command overview |
| `/buldo schematics` | list discovered schematics |
| `/buldo inspect <file>` | size, block/item totals, unknown states |
| `/buldo import <path>` | copy a schematic into `config/buldo/schematics` |
| `/buldo create <name> <file> [rotation] [mirror]` | create a site in front of you |
| `/buldo sites` / `status` | all sites with state and progress |
| `/buldo info <name>` | details for one site |
| `/buldo materials <name>` | have / need per item, and what is short |
| `/buldo link <name> [pos]` | link the container you look at (or a position) |
| `/buldo unlink <name> [pos]` | unlink one container, or all when `pos` is omitted |
| `/buldo start <name> [force]` | start; refuses if you stand inside the site |
| `/buldo pause` / `resume` / `cancel` | control a running site |
| `/buldo free <name> on\|off` | build without taking items (creative only) |
| `/buldo undo <name>` | restore the previous blocks of the last session |
| `/buldo ghost <name> on\|off` | particle preview |
| `/buldo move <name>` | move an idle site to where you stand |
| `/buldo tp <name>` | teleport to a site |
| `/buldo queue add\|remove\|list\|clear [name]` | build sites one after another |
| `/buldo speed <1-40>` | blocks per second (default 10) |

### How it behaves

- Blocks are placed with client updates only, so sand does not fall, redstone
  does not fire and containers do not spill while the structure is being built.
- Before each block, Buldo withdraws its exact cost from the linked containers,
  all or nothing. A double slab costs 2; the top half of a door or tall plant
  and the head of a bed are free.
- When a container runs dry the site becomes **blocked**, tells you what is
  missing, and resumes on its own once the containers are resupplied.
- Air in the schematic is placed too (exact replace), unless you turn that off
  in the config, so the finished box matches the schematic exactly.
- Already identical blocks cost nothing and are skipped.
- Building does not continue across a restart: a site saved as building loads
  as paused and waits for `/buldo resume`.
- Sites and the queue live in `world/buldo/`, the config in `config/buldo.json`.

### Free build mode (creative)

```
/buldo free <name> on
/buldo start <name>
```

Free mode places blocks without taking anything from containers and without
needing any linked containers at all. It still skips blocks that are already
correct, still records undo history, and still uses the ghost/HUD. Turning it on
requires the player to be in creative (console/op can always do it). It is a
per-site setting and survives restarts, so switch it off again with
`/buldo free <name> off` when you go back to survival. `/buldo materials` shows
what is left without listing items while free mode is on.

`/buldo start <name> force` is different: it only skips the up-front materials
check and still stops as soon as an item is actually missing.

## Overview camera (client side)

The mod includes an optional RTS-style camera so you can watch a build from
above. It needs the jar on the client as well; on a dedicated server it is
simply the server side running and the camera is client-only.

Press **F6** to cycle:

| Mode | What it is |
| --- | --- |
| **Orbit** | the camera lifts up and back, looking down at where you stood |
| **Top-down** | near-vertical view, like a map |
| **Off** | back to normal first person, exactly where you were |

While the overview is up:

- **Mouse** orbits / turns the view, **scroll** zooms (3–96 blocks);
- **WASD** pans the camera, **Space** / **Shift** raise and lower it;
- **Left Ctrl** (sprint) pans faster;
- your body stays put, does not move or turn, and cannot mine or use items;
- a short status line shows the mode and zoom. Press F6 again to move on.

The camera pulls itself in when a hill or roof is in the way, so it never ends
up inside terrain. It resets to normal first person when you leave the world.

The keybind can be changed in Options → Controls → Buldo (default F6).

## Config (`config/buldo.json`)

```jsonc
{
  "blocksPerSecond": 10.0,        // 1 - 40
  "maxSites": 32,
  "maxUndoFrames": 3,             // one frame per start, oldest dropped
  "maxUndoBlocksPerFrame": 40000,
  "exactReplaceDefault": true,    // place air from the schematic
  "ghostDefault": true,
  "hud": true,                    // action bar progress
  "autoStartQueue": true,
  "settleOnFinish": false,        // send neighbour updates once finished
  "saveEvery200Blocks": true,
  "freeBlocks": ["minecraft:air", "minecraft:water", "..."]
}
```

## Test recipe

The bundled `buldo debug plantest <file>` command places a whole schematic,
checks every cell, then restores everything, and prints the mismatch counts.
Run it from the server console to validate parsing and placement without a
player, materials or a client.

## Build from source

```
./gradlew build        # build/libs/buldo-0.1.0.jar
```

Java 25 is pinned in `.mise.toml`.
