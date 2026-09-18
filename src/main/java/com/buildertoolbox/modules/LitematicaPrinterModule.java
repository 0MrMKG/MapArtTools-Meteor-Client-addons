package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Places missing blocks from the active Litematica projection. */
public final class LitematicaPrinterModule extends Module {
    private static final int ROTATION_PRIORITY = 50;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgFilter = settings.createGroup("Filter");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Maximum distance from the player for projection blocks to be placed.")
        .defaultValue(4)
        .range(1, 6)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait between placement passes.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum number of projection blocks to place per pass.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Order in which eligible projection blocks are printed.")
        .defaultValue(SortMode.NEAREST)
        .build()
    );

    private final Setting<Boolean> dirtAsGrass = sgGeneral.add(new BoolSetting.Builder()
        .name("dirt-as-grass")
        .description("Use dirt for grass-block projections when grass blocks are unavailable.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgPlacement.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate toward the placement before interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgPlacement.add(new BoolSetting.Builder()
        .name("swing")
        .description("Swing the hand after placing a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> moveFromInventory = sgPlacement.add(new BoolSetting.Builder()
        .name("move-from-inventory")
        .description("Move a required block to an empty hotbar slot when it is only in the main inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<FilterMode> filterMode = sgFilter.add(new EnumSetting.Builder<FilterMode>()
        .name("filter-mode")
        .description("Optionally limit printing to a list of projection blocks.")
        .defaultValue(FilterMode.NONE)
        .build()
    );

    private final Setting<List<Block>> filterBlocks = sgFilter.add(new BlockListSetting.Builder()
        .name("filter-blocks")
        .description("Blocks to include in a whitelist or exclude in a blacklist.")
        .visible(() -> filterMode.get() != FilterMode.NONE)
        .build()
    );

    private final List<BlockPos> candidates = new ArrayList<>();
    private int delayTicks;
    private boolean missingSchematicReported;

    public LitematicaPrinterModule() {
        super(BuilderToolboxAddon.CATEGORY, "place-litematica", "自动放置当前 Litematica 投影中缺失的方块。");
    }

    @Override
    public void onActivate() {
        candidates.clear();
        delayTicks = 0;
        missingSchematicReported = false;
    }

    @Override
    public void onDeactivate() {
        candidates.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!missingSchematicReported) {
                warning("Load a Litematica schematic before enabling the printer.");
                missingSchematicReported = true;
            }
            return;
        }

        missingSchematicReported = false;
        if (delayTicks++ < delay.get()) return;
        delayTicks = 0;

        candidates.clear();
        int scanRange = range.get() + 1;
        BlockIterator.register(scanRange, scanRange, (pos, currentState) -> {
            BlockState requiredState = schematicWorld.getBlockState(pos);
            if (isMissingProjectionBlock(pos, currentState, requiredState)) candidates.add(pos.toImmutable());
        });

        BlockIterator.after(() -> placeCandidates(schematicWorld));
    }

    private boolean isMissingProjectionBlock(BlockPos pos, BlockState currentState, BlockState requiredState) {
        return currentState.isReplaceable()
            && !requiredState.isAir()
            && requiredState.getFluidState().isEmpty()
            && currentState.getBlock() != requiredState.getBlock()
            && passesFilter(requiredState.getBlock())
            && mc.player.getBlockPos().isWithinDistance(pos, range.get())
            && !mc.player.getBoundingBox().intersects(new Box(pos))
            && DataManager.getRenderLayerRange().isPositionWithinRange(pos)
            && requiredState.canPlaceAt(mc.world, pos);
    }

    private void placeCandidates(WorldSchematic schematicWorld) {
        if (mc.player == null || mc.world == null) return;

        sortCandidates();

        int placed = 0;
        for (BlockPos pos : candidates) {
            if (placed >= blocksPerTick.get()) return;
            if (placeBlock(pos, schematicWorld.getBlockState(pos))) placed++;
        }
    }

    private boolean placeBlock(BlockPos pos, BlockState requiredState) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;

        Item requiredItem = requiredState.getBlock() == Blocks.GRASS_BLOCK && dirtAsGrass.get()
            ? Items.DIRT
            : requiredState.getBlock().asItem();
        FindItemResult item = InvUtils.findInHotbar(requiredItem);
        if (!item.found() && moveFromInventory.get()) item = moveItemToHotbar(requiredItem);
        if (!item.found() || !item.isHotbar()) return false;

        return BlockUtils.place(pos, item, rotate.get(), ROTATION_PRIORITY, swing.get());
    }

    private FindItemResult moveItemToHotbar(Item requiredItem) {
        FindItemResult inventoryItem = InvUtils.find(requiredItem);
        FindItemResult emptyHotbarSlot = InvUtils.findEmpty();
        if (!inventoryItem.found() || !inventoryItem.isMain() || !emptyHotbarSlot.found() || !emptyHotbarSlot.isHotbar()) {
            return inventoryItem;
        }

        InvUtils.move().from(inventoryItem.slot()).toHotbar(emptyHotbarSlot.slot());
        return InvUtils.findInHotbar(requiredItem);
    }

    private boolean passesFilter(Block requiredBlock) {
        return switch (filterMode.get()) {
            case NONE -> true;
            case WHITELIST -> filterBlocks.get().contains(requiredBlock);
            case BLACKLIST -> !filterBlocks.get().contains(requiredBlock);
        };
    }

    private void sortCandidates() {
        Comparator<BlockPos> nearest = Comparator.comparingDouble(pos -> mc.player.squaredDistanceTo(
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
        ));

        switch (sortMode.get()) {
            case NEAREST -> candidates.sort(nearest);
            case FURTHEST -> candidates.sort(nearest.reversed());
            case BOTTOM_UP -> candidates.sort(Comparator.comparingInt(BlockPos::getY).thenComparing(nearest));
            case TOP_DOWN -> candidates.sort(Comparator.comparingInt(BlockPos::getY).reversed().thenComparing(nearest));
        }
    }

    public enum FilterMode {
        NONE,
        WHITELIST,
        BLACKLIST
    }

    public enum SortMode {
        NEAREST,
        FURTHEST,
        BOTTOM_UP,
        TOP_DOWN
    }
}
