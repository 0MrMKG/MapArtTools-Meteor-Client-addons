package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Direction.Axis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.world.RaycastContext;

/** Places missing blocks from the active Litematica projection. */
public final class LitematicaPrinterModule extends Module {
    private static final int ROTATION_PRIORITY = 50;
    private static final int ROTATION_DISPATCH_TIMEOUT_TICKS = 5;
    private static final int MAX_BACKOFF_MULTIPLIER = 3;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgRendering = settings.createGroup("Rendering");

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
        .description("Maximum placements per pass. Server confirmation keeps one placement request in flight.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("Order in which eligible projection blocks are printed.")
        .defaultValue(SortMode.BOTTOM_UP)
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

    private final Setting<Boolean> stateAwarePlacement = sgPlacement.add(new BoolSetting.Builder()
        .name("state-aware-placement")
        .description("Use the projection's orientation, axis, and top or bottom placement state when placing blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> confirmationTimeout = sgPlacement.add(new IntSetting.Builder()
        .name("server-confirmation-timeout")
        .description("Ticks to wait for the server's block update before treating a placement as failed.")
        .defaultValue(20)
        .range(6, 100)
        .sliderRange(6, 40)
        .build()
    );

    private final Setting<Boolean> sneakOnInteractable = sgPlacement.add(new BoolSetting.Builder()
        .name("sneak-on-interactable")
        .description("Temporarily sneak when clicking an interactable support block, such as a chest or crafting table.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> retryFailedPlacements = sgPlacement.add(new BoolSetting.Builder()
        .name("retry-failed-placements")
        .description("Confirm placements, then retry failed interactions with another valid support face.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> retryDelay = sgPlacement.add(new IntSetting.Builder()
        .name("retry-delay")
        .description("Base ticks to wait before retrying a placement that was not confirmed by the server.")
        .defaultValue(8)
        .range(2, 100)
        .sliderRange(2, 40)
        .visible(retryFailedPlacements::get)
        .build()
    );

    private final Setting<Integer> retriesBeforeRecheck = sgPlacement.add(new IntSetting.Builder()
        .name("retries-before-recheck")
        .description("Retries before a longer cooldown and a fresh projection recheck.")
        .defaultValue(4)
        .range(1, 12)
        .sliderRange(1, 8)
        .visible(retryFailedPlacements::get)
        .build()
    );

    private final Setting<Integer> recheckDelay = sgPlacement.add(new IntSetting.Builder()
        .name("recheck-delay")
        .description("Ticks to wait after repeated failures before trying the position again.")
        .defaultValue(60)
        .range(20, 600)
        .sliderRange(20, 200)
        .visible(retryFailedPlacements::get)
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

    private final Setting<Boolean> renderStatus = sgRendering.add(new BoolSetting.Builder()
        .name("render-status")
        .description("Render nearby non-air projection blocks by their placement status.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> readyColor = sgRendering.add(new ColorSetting.Builder()
        .name("not-placed-color")
        .description("Blue: the projection block is ready to be placed.")
        .defaultValue(new SettingColor(55, 135, 255, 80))
        .visible(renderStatus::get)
        .build()
    );

    private final Setting<SettingColor> placedColor = sgRendering.add(new ColorSetting.Builder()
        .name("placed-color")
        .description("Green: the world matches the relevant placement state of the projection block.")
        .defaultValue(new SettingColor(70, 210, 95, 80))
        .visible(renderStatus::get)
        .build()
    );

    private final Setting<SettingColor> blockedColor = sgRendering.add(new ColorSetting.Builder()
        .name("blocked-color")
        .description("Red: the block cannot currently be placed or has an incorrect placement state.")
        .defaultValue(new SettingColor(235, 65, 65, 95))
        .visible(renderStatus::get)
        .build()
    );

    private final List<BlockPos> candidates = new ArrayList<>();
    private final Map<BlockPos, ProjectionStatus> renderStatuses = new HashMap<>();
    private final Map<BlockPos, RetryState> failedPlacements = new HashMap<>();
    private InFlightPlacement inFlightPlacement;
    private int delayTicks;
    private long tick;
    private boolean missingSchematicReported;

    public LitematicaPrinterModule() {
        super(BuilderToolboxAddon.CATEGORY, "place-litematica", "自动放置当前 Litematica 投影中缺失的方块。");
    }

    @Override
    public void onActivate() {
        candidates.clear();
        renderStatuses.clear();
        failedPlacements.clear();
        inFlightPlacement = null;
        delayTicks = 0;
        tick = 0;
        missingSchematicReported = false;
    }

    @Override
    public void onDeactivate() {
        candidates.clear();
        renderStatuses.clear();
        failedPlacements.clear();
        inFlightPlacement = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            renderStatuses.clear();
            failedPlacements.clear();
            inFlightPlacement = null;
            if (!missingSchematicReported) {
                warning("Load a Litematica schematic before enabling the printer.");
                missingSchematicReported = true;
            }
            return;
        }

        missingSchematicReported = false;
        settleInFlightPlacement(schematicWorld);
        if (delayTicks++ < delay.get()) return;
        delayTicks = 0;

        candidates.clear();
        renderStatuses.clear();
        Map<Block, Boolean> materialAvailability = new HashMap<>();
        int scanRange = range.get() + 1;
        BlockIterator.register(scanRange, scanRange, (pos, currentState) -> {
            BlockState requiredState = schematicWorld.getBlockState(pos);
            updateRenderStatus(pos, currentState, requiredState, materialAvailability);
            if (isMissingProjectionBlock(pos, currentState, requiredState)) candidates.add(pos.toImmutable());
        });

        BlockIterator.after(() -> placeCandidates(schematicWorld));
    }

    private boolean isMissingProjectionBlock(BlockPos pos, BlockState currentState, BlockState requiredState) {
        return canReplaceWithRequiredState(currentState, requiredState)
            && !requiredState.isAir()
            && requiredState.getFluidState().isEmpty()
            && !matchesProjection(currentState, requiredState)
            && passesFilter(requiredState.getBlock())
            && mc.player.getBlockPos().isWithinDistance(pos, range.get())
            && !mc.player.getBoundingBox().intersects(new Box(pos))
            && DataManager.getRenderLayerRange().isPositionWithinRange(pos)
            && requiredState.canPlaceAt(mc.world, pos)
            && hasRequiredItem(requiredState)
            && inFlightPlacement == null
            && canRetry(pos);
    }

    private void placeCandidates(WorldSchematic schematicWorld) {
        if (mc.player == null || mc.world == null || inFlightPlacement != null) return;

        sortCandidates();

        int placed = 0;
        for (BlockPos pos : candidates) {
            if (placed >= blocksPerTick.get()) return;
            if (placeBlock(pos, schematicWorld.getBlockState(pos))) placed++;
        }
    }

    private boolean placeBlock(BlockPos pos, BlockState requiredState) {
        if (inFlightPlacement != null) return false;

        BlockState currentState = mc.world.getBlockState(pos);
        if (!canReplaceWithRequiredState(currentState, requiredState)) {
            markPlacementFailed(pos);
            return false;
        }

        Item requiredItem = getRequiredItem(requiredState);
        FindItemResult item = InvUtils.findInHotbar(requiredItem);
        if (!item.found() && moveFromInventory.get()) item = moveItemToHotbar(requiredItem);
        if (!item.found() || !item.isHotbar()) {
            markPlacementFailed(pos);
            return false;
        }

        if (stateAwarePlacement.get()) return placeStateAware(pos, currentState, requiredState, item);

        boolean placed = BlockUtils.place(pos, item, rotate.get(), ROTATION_PRIORITY, swing.get());
        if (placed) beginWaitingForConfirmation(pos, getSupportVariant(pos));
        else markPlacementFailed(pos);
        return placed;
    }

    private FindItemResult moveItemToHotbar(Item requiredItem) {
        FindItemResult inventoryItem = InvUtils.find(requiredItem);
        FindItemResult emptyHotbarSlot = InvUtils.findEmpty();
        if (!inventoryItem.found() || !inventoryItem.isMain()) {
            return inventoryItem;
        }

        int targetSlot = emptyHotbarSlot.found() && emptyHotbarSlot.isHotbar()
            ? emptyHotbarSlot.slot()
            : mc.player.getInventory().getSelectedSlot();
        InvUtils.move().from(inventoryItem.slot()).toHotbar(targetSlot);
        return InvUtils.findInHotbar(requiredItem);
    }

    private Item getRequiredItem(BlockState requiredState) {
        return requiredState.getBlock() == Blocks.GRASS_BLOCK && dirtAsGrass.get()
            ? Items.DIRT
            : requiredState.getBlock().asItem();
    }

    private boolean hasRequiredItem(BlockState requiredState) {
        Item requiredItem = getRequiredItem(requiredState);
        FindItemResult hotbarItem = InvUtils.findInHotbar(requiredItem);
        if (hotbarItem.found()) return true;

        return moveFromInventory.get() && InvUtils.find(requiredItem).isMain();
    }

    private boolean placeStateAware(BlockPos pos, BlockState currentState, BlockState requiredState, FindItemResult item) {
        int supportVariant = getSupportVariant(pos);
        PlacementPlan plan = createPlacementPlan(pos, currentState, requiredState, supportVariant);
        if (plan == null) {
            markPlacementFailed(pos);
            return false;
        }

        inFlightPlacement = new InFlightPlacement(pos.toImmutable(), supportVariant, tick, -1, PlacementPhase.SCHEDULED);

        Runnable interact = () -> {
            if (!isCurrentScheduledPlacement(pos, supportVariant)) return;

            if (!InvUtils.swap(item.slot(), true)) {
                markPlacementFailed(pos);
                return;
            }

            boolean sneaking = shouldSneakFor(plan);
            if (sneaking) setSneaking(true);
            BlockUtils.interact(plan.hitResult(), Hand.MAIN_HAND, swing.get());
            if (sneaking) setSneaking(false);
            InvUtils.swapBack();
            beginWaitingForConfirmation(pos, supportVariant);
        };

        if (rotate.get()) {
            Direction playerFacing = getPlayerFacing(requiredState);
            double yaw = playerFacing == null ? Rotations.getYaw(plan.rotationTarget()) : yawFor(playerFacing);
            Rotations.rotate(yaw, Rotations.getPitch(plan.rotationTarget()), ROTATION_PRIORITY, interact);
        } else {
            interact.run();
        }

        return true;
    }

    private PlacementPlan createPlacementPlan(BlockPos pos, BlockState currentState, BlockState requiredState, int supportVariant) {
        if (canMergeSlab(currentState, requiredState)) {
            Vec3d hitPos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5);
            PlacementPlan plan = new PlacementPlan(new BlockHitResult(hitPos, Direction.UP, pos, false), hitPos);
            return isInteractionVisible(plan) ? plan : null;
        }

        List<Direction> validSupports = findSupportDirections(pos, requiredState);
        for (int offset = 0; offset < validSupports.size(); offset++) {
            Direction supportDirection = validSupports.get(Math.floorMod(supportVariant + offset, validSupports.size()));
            BlockPos supportPos = pos.offset(supportDirection);
            Direction clickedFace = supportDirection.getOpposite();
            Vec3d hitPos = getFaceCenter(supportPos, clickedFace);
            hitPos = adjustHitPositionForState(hitPos, pos, clickedFace, requiredState);

            PlacementPlan plan = new PlacementPlan(new BlockHitResult(hitPos, clickedFace, supportPos, false), hitPos);
            if (isInteractionVisible(plan)) return plan;
        }

        return null;
    }

    private List<Direction> findSupportDirections(BlockPos pos, BlockState requiredState) {
        List<Direction> directions = new ArrayList<>();

        if (requiredState.contains(Properties.AXIS)) addAxisDirections(directions, requiredState.get(Properties.AXIS));

        if (requiredState.contains(Properties.SLAB_TYPE)) {
            SlabType slabType = requiredState.get(Properties.SLAB_TYPE);
            if (slabType == SlabType.TOP) addDirections(directions, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN);
            else addDirections(directions, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP);
        } else if (requiredState.contains(Properties.BLOCK_HALF)) {
            BlockHalf half = requiredState.get(Properties.BLOCK_HALF);
            if (half == BlockHalf.TOP) addDirections(directions, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN);
            else addDirections(directions, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP);
        }

        addDirections(directions, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP);
        List<Direction> validSupports = new ArrayList<>();
        for (Direction direction : directions) {
            if (!mc.world.getBlockState(pos.offset(direction)).isReplaceable()) validSupports.add(direction);
        }

        return validSupports;
    }

    private boolean isInteractionVisible(PlacementPlan plan) {
        HitResult raycast = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(),
            plan.hitResult().getPos(),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        return raycast.getType() == HitResult.Type.BLOCK
            && raycast instanceof BlockHitResult hit
            && hit.getBlockPos().equals(plan.hitResult().getBlockPos())
            && hit.getSide() == plan.hitResult().getSide();
    }

    private boolean shouldSneakFor(PlacementPlan plan) {
        return sneakOnInteractable.get()
            && !mc.player.isSneaking()
            && BlockUtils.isClickable(mc.world.getBlockState(plan.hitResult().getBlockPos()).getBlock());
    }

    private void setSneaking(boolean sneaking) {
        mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
            new PlayerInput(false, false, false, false, false, sneaking, false)
        ));
    }

    private static void addAxisDirections(List<Direction> directions, Axis axis) {
        switch (axis) {
            case X -> addDirections(directions, Direction.WEST, Direction.EAST);
            case Y -> addDirections(directions, Direction.DOWN, Direction.UP);
            case Z -> addDirections(directions, Direction.NORTH, Direction.SOUTH);
        }
    }

    private static void addDirections(List<Direction> directions, Direction... additions) {
        for (Direction direction : additions) {
            if (!directions.contains(direction)) directions.add(direction);
        }
    }

    private static Vec3d getFaceCenter(BlockPos pos, Direction face) {
        return new Vec3d(
            pos.getX() + 0.5 + face.getOffsetX() * 0.5,
            pos.getY() + 0.5 + face.getOffsetY() * 0.5,
            pos.getZ() + 0.5 + face.getOffsetZ() * 0.5
        );
    }

    private static Vec3d adjustHitPositionForState(Vec3d hitPos, BlockPos targetPos, Direction clickedFace, BlockState requiredState) {
        if (!clickedFace.getAxis().isHorizontal()) return hitPos;

        boolean top = requiredState.contains(Properties.SLAB_TYPE) && requiredState.get(Properties.SLAB_TYPE) == SlabType.TOP
            || requiredState.contains(Properties.BLOCK_HALF) && requiredState.get(Properties.BLOCK_HALF) == BlockHalf.TOP;
        if (!requiredState.contains(Properties.SLAB_TYPE) && !requiredState.contains(Properties.BLOCK_HALF)) return hitPos;

        return new Vec3d(hitPos.x, targetPos.getY() + (top ? 0.9 : 0.1), hitPos.z);
    }

    private Direction getPlayerFacing(BlockState requiredState) {
        if (!requiredState.contains(Properties.HORIZONTAL_FACING)) return null;

        Direction targetFacing = requiredState.get(Properties.HORIZONTAL_FACING);
        Block block = requiredState.getBlock();
        if (block instanceof StairsBlock || block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock) {
            return targetFacing;
        }

        return targetFacing.getOpposite();
    }

    private static double yawFor(Direction direction) {
        return switch (direction) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> -90;
            default -> 0;
        };
    }

    private void settleInFlightPlacement(WorldSchematic schematicWorld) {
        if (inFlightPlacement == null) return;

        BlockPos pos = inFlightPlacement.pos();
        if (inFlightPlacement.phase() == PlacementPhase.SCHEDULED) {
            if (tick - inFlightPlacement.scheduledAt() >= ROTATION_DISPATCH_TIMEOUT_TICKS) {
                markPlacementFailed(pos);
            }
            return;
        }

        BlockState requiredState = schematicWorld.getBlockState(pos);
        if (requiredState.isAir() || matchesProjection(mc.world.getBlockState(pos), requiredState)) {
            failedPlacements.remove(pos);
            inFlightPlacement = null;
            return;
        }

        if (tick - inFlightPlacement.sentAt() >= confirmationTimeout.get()) {
            markPlacementFailed(pos);
        }
    }

    private void updateRenderStatus(BlockPos pos, BlockState currentState, BlockState requiredState, Map<Block, Boolean> materialAvailability) {
        if (requiredState.isAir()
            || !mc.player.getBlockPos().isWithinDistance(pos, range.get())
            || !DataManager.getRenderLayerRange().isPositionWithinRange(pos)) return;

        if (matchesProjection(currentState, requiredState)) {
            failedPlacements.remove(pos);
            if (inFlightPlacement != null && inFlightPlacement.pos().equals(pos)) inFlightPlacement = null;
            renderStatuses.put(pos.toImmutable(), ProjectionStatus.PLACED);
            return;
        }

        boolean hasMaterial = materialAvailability.computeIfAbsent(requiredState.getBlock(), ignored -> hasRequiredItem(requiredState));
        boolean blocked = !canReplaceWithRequiredState(currentState, requiredState)
            || !requiredState.canPlaceAt(mc.world, pos)
            || !hasMaterial
            || mc.player.getBoundingBox().intersects(new Box(pos))
            || !canRetry(pos);
        renderStatuses.put(pos.toImmutable(), blocked ? ProjectionStatus.BLOCKED : ProjectionStatus.READY);
    }

    private void markPlacementFailed(BlockPos pos) {
        if (inFlightPlacement != null && inFlightPlacement.pos().equals(pos)) inFlightPlacement = null;
        BlockPos key = pos.toImmutable();
        if (!retryFailedPlacements.get()) {
            failedPlacements.put(key, new RetryState(0, 0, Long.MAX_VALUE));
            return;
        }

        RetryState previous = failedPlacements.get(key);
        int failures = previous == null ? 1 : previous.consecutiveFailures() + 1;
        int supportVariant = previous == null ? 1 : previous.supportVariant() + 1;
        long nextRetryTick;
        if (failures >= retriesBeforeRecheck.get()) {
            failures = 0;
            nextRetryTick = tick + recheckDelay.get();
        } else {
            nextRetryTick = tick + (long) retryDelay.get() * Math.min(failures, MAX_BACKOFF_MULTIPLIER);
        }

        failedPlacements.put(key, new RetryState(failures, supportVariant, nextRetryTick));
    }

    private boolean canRetry(BlockPos pos) {
        RetryState retry = failedPlacements.get(pos);
        return retry == null || retry.nextRetryTick() <= tick;
    }

    private int getSupportVariant(BlockPos pos) {
        RetryState retry = failedPlacements.get(pos);
        return retry == null ? 0 : retry.supportVariant();
    }

    private boolean isCurrentScheduledPlacement(BlockPos pos, int supportVariant) {
        return inFlightPlacement != null
            && inFlightPlacement.phase() == PlacementPhase.SCHEDULED
            && inFlightPlacement.pos().equals(pos)
            && inFlightPlacement.supportVariant() == supportVariant;
    }

    private void beginWaitingForConfirmation(BlockPos pos, int supportVariant) {
        if (inFlightPlacement == null) {
            inFlightPlacement = new InFlightPlacement(pos.toImmutable(), supportVariant, tick, tick, PlacementPhase.WAITING_FOR_SERVER);
            return;
        }

        if (inFlightPlacement.pos().equals(pos)) {
            inFlightPlacement = new InFlightPlacement(pos.toImmutable(), supportVariant, inFlightPlacement.scheduledAt(), tick, PlacementPhase.WAITING_FOR_SERVER);
        }
    }

    private static boolean canReplaceWithRequiredState(BlockState currentState, BlockState requiredState) {
        return currentState.isReplaceable() || canMergeSlab(currentState, requiredState);
    }

    private static boolean canMergeSlab(BlockState currentState, BlockState requiredState) {
        return requiredState.contains(Properties.SLAB_TYPE)
            && requiredState.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
            && currentState.getBlock() == requiredState.getBlock()
            && currentState.contains(Properties.SLAB_TYPE)
            && currentState.get(Properties.SLAB_TYPE) != SlabType.DOUBLE;
    }

    private static boolean matchesProjection(BlockState currentState, BlockState requiredState) {
        return currentState.getBlock() == requiredState.getBlock()
            && matchesProperty(currentState, requiredState, Properties.FACING)
            && matchesProperty(currentState, requiredState, Properties.HOPPER_FACING)
            && matchesProperty(currentState, requiredState, Properties.HORIZONTAL_FACING)
            && matchesProperty(currentState, requiredState, Properties.AXIS)
            && matchesProperty(currentState, requiredState, Properties.HORIZONTAL_AXIS)
            && matchesProperty(currentState, requiredState, Properties.BLOCK_HALF)
            && matchesProperty(currentState, requiredState, Properties.SLAB_TYPE)
            && matchesProperty(currentState, requiredState, Properties.DOUBLE_BLOCK_HALF);
    }

    private static <T extends Comparable<T>> boolean matchesProperty(BlockState currentState, BlockState requiredState, Property<T> property) {
        if (currentState.contains(property) != requiredState.contains(property)) return false;
        return !requiredState.contains(property) || currentState.get(property).equals(requiredState.get(property));
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

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderStatus.get()) return;

        for (Map.Entry<BlockPos, ProjectionStatus> entry : renderStatuses.entrySet()) {
            SettingColor color = switch (entry.getValue()) {
                case READY -> readyColor.get();
                case PLACED -> placedColor.get();
                case BLOCKED -> blockedColor.get();
            };

            event.renderer.box(entry.getKey(), color, color, ShapeMode.Both, 0);
        }
    }

    private record PlacementPlan(BlockHitResult hitResult, Vec3d rotationTarget) {}

    private record InFlightPlacement(BlockPos pos, int supportVariant, long scheduledAt, long sentAt, PlacementPhase phase) {}

    private record RetryState(int consecutiveFailures, int supportVariant, long nextRetryTick) {}

    private enum PlacementPhase {
        SCHEDULED,
        WAITING_FOR_SERVER
    }

    private enum ProjectionStatus {
        READY,
        PLACED,
        BLOCKED
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
