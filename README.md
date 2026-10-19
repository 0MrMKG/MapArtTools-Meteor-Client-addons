# Builder Toolbox

A client-side Meteor Client addon for Minecraft 1.21.11.

## Requirements

- Java 21 JDK
- Fabric Loader 0.18.2 or newer
- Meteor Client for Minecraft 1.21.11

## Development

Open this directory as a Gradle project in IntelliJ IDEA, then run the `Minecraft Client` run configuration to test the addon.

Build a distributable JAR on Windows with:

```powershell
.\gradlew.bat build
```

The built JAR is written to `build/libs`. Install it in the same `mods` folder as Meteor Client.

## Full Auto Map Art

**map-artmaker-all-auto** is a separate top-level module in **Builder Toolbox**, alongside the original **Map Art Maker**. Its internal name and menu title are both `map-artmaker-all-auto`. It has its own switch, settings, worker, placement engine and helpers; it is not a setting or sub-feature of the original map-art module. The original map-art and schematic-printer implementations are unchanged.

In **Full Auto Batch**, enter a **Schematic Folder** relative to Litematica's actual `schematics` directory (for example `pictures`). This is a text field and accepts **Ctrl+V**. **Validate Directory** in **Schematic Directory Validation** checks the folder and reads its direct `.litematic` files asynchronously, reporting their count, resolved path and first five naturally sorted names. It works with the module disabled, never starts/resets a batch or clears the canvas, and does not modify projections. This checks file access, not blueprint dimensions or map alignment; those are validated when the batch starts. An empty readable directory reports zero files but cannot start a batch. The direct `.litematic` files are snapshotted in natural filename order; nested directories are not scanned. Supported blueprints are one 128x128 layer at relative Y=0. Native Litematica region offsets and negative sizes are preserved, with no rotation, mirroring or automatic recentering. The target bounds must align with a scale-0 map: minimum X and Z are each `128*n - 64`.

Use **Set Canvas Origin**, **Set Clear Lever**, **Set Map Supply**, **Set Cartography Table**, and **Set Product Storage**. Settings hide during selection; right-click to record, Esc returns. The canvas origin is the clicked block's X/Z and Y+1. Other positions are the clicked block itself. Selected positions have yellow outlines only in selection mode. Map supply/product storage accept chests, barrels and shulker boxes without labels; the original labeled **Select Supply Chests** remains responsible for printing colors.

**Start / Resume** executes each picture as: validate file -> lever on 20s/off 20s/on 20s/off 20s -> wait another 10s without inspecting the cleanup result -> snake print and restock -> physically walk the complete snake inspection route without placing -> actual missing + wrong count below 50 -> fetch exactly one empty map and one ordinary glass pane -> activate at the canvas center and hold for approximately 2s -> lock at the cartography table -> confirm the locked map's actual ID in product storage -> next file. These are exactly two dispenser triggers: one dispenses water, the other retrieves it. There is no post-clean scan or automatic repeat flushing. Inspection visits the entire canvas from start to end, reads nearby loaded world cells into a matrix, and reports missing and wrong blocks separately; unread cells cannot count as success. A resumed inspection restarts the inspection walk, not cleanup or printing. The center capture does not wait for complete pixel updates or walk an extra capture route. Two inventory slots are reserved for map materials during restocking. Before the first cleanup pulse, the canvas must be loaded with a solid foundation, two inventory slots must be empty, and any required color not already stocked in sufficient quantity needs a registered supply box.

Checkpoints are atomically saved in `config/builder_toolbox/fullauto-batch.properties` with world/dimension, all five locations, configuration, file hashes, phase, lever intents and map IDs. Resume validates every snapshotted file before interaction; newly added files wait for a new batch. Restarting while printing rescans the world and continues without clearing. Deposits are checked by reopening product storage; uncertain/lost map ownership pauses rather than producing a duplicate or clearing the next image. Locking can change the map ID. A resumed lever wait conservatively waits its full 20s again; an unconfirmed lever edge inconsistent with the actual block requires inspection. **Reset Batch** archives the checkpoint as `.previous` and does not operate the lever. Location/directory/enabled-filter changes require restoring the checkpoint's configuration or resetting the batch. Storage, lever and table need normal walkable floor connections at canvas height. Esc/background operation remains supported.

The original mode has priority on the same client: Full Auto refuses to start while the original is active; enabling the original while Full Auto is running stops only Full Auto first. This prevents both workers from competing for player controls, inventory or pause settings without changing the original module's implementation.

## Auto Craft

The initial recipe chain is **Bone -> Bone Meal -> Bone Block**. In Auto Craft settings, enable **Select Storage Blocks**, right-click each chest, barrel or shulker box, then press Esc. Enable **Select Crafting Table**, right-click the table, then press Esc. Positions are saved; selected storage blocks have yellow outlines and the table has a cyan outline during selection. Labels are not required.

Move preexisting bones, bone meal and bone blocks out of your inventory and leave at least four empty slots. Enable the module or press **Start / Resume**, then close the settings screen. It walks on real floor to each source and the crafting table, processes one source at a time, and returns blocks and final leftover powder to that source. Depending on free inventory space, each trip takes up to three stacks of bones (192 bones produce 64 blocks). Locations must have a walkable route on the player's starting floor. Full storage, unavailable interaction or an unconfirmed inventory operation pauses the job with its materials retained. Free the original storage and press **Start / Resume** to retry. Turning the module off releases its movement key and closes only its own container; a stopped job can resume within the same game session.

## Project layout

- `src/main/java/com/buildertoolbox/BuilderToolboxAddon.java` is the Meteor addon entry point and category registration point.
- `src/main/resources/fabric.mod.json` defines the Fabric and Meteor metadata.
- `gradle/libs.versions.toml` pins Minecraft, Yarn mappings, Fabric Loader, Java, Loom, and Meteor versions.

The project is initialized from the official [Meteor Addon Template](https://github.com/MeteorDevelopment/meteor-addon-template), then adjusted for Minecraft 1.21.11.
