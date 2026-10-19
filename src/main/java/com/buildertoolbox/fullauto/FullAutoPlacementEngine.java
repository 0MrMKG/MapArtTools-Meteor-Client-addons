package com.buildertoolbox.fullauto;

import com.buildertoolbox.BuilderToolboxAddon;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.world.RaycastContext;

/** Private, unregistered placement-engine fork owned only by FullAutoMapArtModule. */
public final class FullAutoPlacementEngine extends Module {
    private static final int ROTATION_PRIORITY = 50;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlatform = settings.createGroup("Platform");
    private final SettingGroup sgAntiSpawn = settings.createGroup("Anti Spawn");
    private final SettingGroup sgRendering = settings.createGroup("Rendering");

    // Labels, groups, defaults and ranges mirror Xiaohe Lotus 18.2 Printer.
    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("Place Range")
        .description("Maximum interaction range. Uses the same limits as Map Art Maker.")
        .defaultValue(3.6)
        .range(2.0, 3.6)
        .sliderRange(2.0, 3.6)
        .build()
    );

    private final Setting<Integer> placementSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("Placement Speed")
        .description("Maximum projection placement attempts per second, matching Map Art Maker.")
        .defaultValue(10)
        .range(1, 20)
        .sliderRange(1, 20)
        .visible(this::isNormalMode)
        .build()
    );

    private final Setting<Integer> printCount = sgGeneral.add(new IntSetting.Builder()
        .name("Placements Per Tick")
        .description("Maximum placement requests per tick.")
        .defaultValue(1)
        .min(1)
        .sliderMax(16)
        .visible(() -> !isNormalMode())
        .build()
    );

    private final Setting<List<Block>> blacklist = sgGeneral.add(new BlockListSetting.Builder()
        .name("Blacklist")
        .description("Blocks that must not be placed.")
        .visible(() -> !isNormalMode())
        .build()
    );

    private final Setting<Integer> placementTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("Placement Timeout")
        .description("Cooldown in ticks after a failed position.")
        .defaultValue(20)
        .min(0)
        .sliderMax(100)
        .visible(() -> !isNormalMode())
        .build()
    );

    private final Setting<Integer> faceValidationLimit = sgGeneral.add(new IntSetting.Builder()
        .name("Face Checks Per Tick")
        .description("Maximum raycast face checks per tick.")
        .defaultValue(16)
        .min(1)
        .sliderMax(64)
        .visible(() -> !isNormalMode())
        .build()
    );

    private final Setting<PrinterPriority> printPriority = sgGeneral.add(new EnumSetting.Builder<PrinterPriority>()
        .name("Priority")
        .description("Placement priority.")
        .defaultValue(PrinterPriority.NEAREST)
        .visible(() -> !isNormalMode())
        .build()
    );

    private final Setting<PrinterMode> printerMode = sgGeneral.add(new EnumSetting.Builder<PrinterMode>()
        .name("Mode")
        .description("Printer mode.")
        .defaultValue(PrinterMode.NORMAL)
        .build()
    );

    private final Setting<List<Block>> platformBlocks = sgPlatform.add(new BlockListSetting.Builder()
        .name("Platform Blocks")
        .description("Blocks used in Platform mode.")
        .defaultValue(Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB)
        .visible(() -> printerMode.get() == PrinterMode.PLATFORM)
        .build()
    );

    private final Setting<Boolean> upperSlab = sgPlatform.add(new BoolSetting.Builder()
        .name("Top Slabs")
        .description("Use top slabs in Platform mode.")
        .defaultValue(true)
        .visible(() -> printerMode.get() == PrinterMode.PLATFORM)
        .build()
    );

    private final Setting<List<Block>> antiSpawnBlocks = sgAntiSpawn.add(new BlockListSetting.Builder()
        .name("Anti Spawn Blocks")
        .description("Blocks used in Anti Spawn mode.")
        .defaultValue(
            Blocks.ACTIVATOR_RAIL,
            Blocks.COBBLESTONE_SLAB,
            Blocks.DETECTOR_RAIL,
            Blocks.POWERED_RAIL,
            Blocks.RAIL,
            Blocks.STONE_BRICK_SLAB,
            Blocks.SMOOTH_STONE_SLAB
        )
        .visible(() -> printerMode.get() == PrinterMode.ANTI_SPAWN)
        .build()
    );

    private final Setting<Boolean> antiSmallMobs = sgAntiSpawn.add(new BoolSetting.Builder()
        .name("Anti Small Mobs")
        .description("Allow anti-spawn placement with less than two blocks of headroom.")
        .defaultValue(false)
        .visible(() -> printerMode.get() == PrinterMode.ANTI_SPAWN)
        .build()
    );

    private final Setting<Integer> renderFadeTicks = sgRendering.add(new IntSetting.Builder()
        .name("Render Fade Ticks")
        .description("Fade time for dispatched placements.")
        .defaultValue(5)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<SettingColor> renderColor = sgRendering.add(new ColorSetting.Builder()
        .name("Render Color")
        .description("Placement render color.")
        .defaultValue(new SettingColor(95, 190, 255))
        .build()
    );

    private final Map<BlockPos, Integer> placementCooldowns = new HashMap<>();
    private final Map<BlockPos, Long> normalPendingPlacements = new HashMap<>();
    private final Set<BlockPos> cancelledPlacements = new HashSet<>();
    private final Map<BlockPos, Object> queuedProjectionPlacements = new HashMap<>();
    private final List<FadeEntry> fadeEntries = new ArrayList<>();
    private final List<PreparedPlacement> preparedPlacements = new ArrayList<>();
    private int scanCursor;
    private long normalTick;
    private long nextNormalPlacementTick;
    private boolean missingSchematicReported;

    public FullAutoPlacementEngine() {
        super(
            BuilderToolboxAddon.CATEGORY,
            "full-auto-map-art-engine",
            "Places nearby Litematica blocks without walking. Normal mode uses the same placement path as Map Art Maker."
        );
    }

    @Override
    public void onActivate() {
        resetRuntime();
        // Older profiles may still contain the previous 4.1-block value.
        if (placeRange.get() > 3.6) placeRange.set(3.6);
    }

    @Override
    public void onDeactivate() {
        resetRuntime();
    }

    private void resetRuntime() {
        placementCooldowns.clear();
        for (BlockPos pos : normalPendingPlacements.keySet()) cancelQueuedProjectionPlacement(pos);
        normalPendingPlacements.clear();
        cancelledPlacements.clear();
        fadeEntries.clear();
        preparedPlacements.clear();
        scanCursor = 0;
        normalTick = 0;
        nextNormalPlacementTick = 0;
        missingSchematicReported = false;
    }

    private boolean isNormalMode() {
        return printerMode != null && printerMode.get() == PrinterMode.NORMAL;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        normalTick++;

        if (mc.currentScreen instanceof Generic3x3ContainerScreen
            && mc.player.currentScreenHandler instanceof Generic3x3ContainerScreenHandler) {
            mc.player.closeHandledScreen();
            return;
        }
        if (mc.currentScreen != null) return;

        tickCooldowns();
        tickFadeEntries();

        if (printerMode.get() == PrinterMode.NORMAL) {
            tickNearbyProjection();
            return;
        }

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        List<Candidate> candidates = switch (printerMode.get()) {
            case NORMAL -> collectProjectionCandidates(schematicWorld);
            case PLATFORM -> collectPlatformCandidates();
            case ANTI_SPAWN -> collectAntiSpawnCandidates();
        };

        if (candidates == null) return;
        missingSchematicReported = false;
        preparePlacements(candidates);
        dispatchPreparedPlacements();
    }

    /** The stationary printer uses precisely the map-art worker's placement entry point. */
    private void tickNearbyProjection() {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            if (!missingSchematicReported) {
                warning("请先加载 Litematica 投影。", new Object[0]);
                missingSchematicReported = true;
            }
            return;
        }
        missingSchematicReported = false;
        normalPendingPlacements.entrySet().removeIf(entry -> {
            if (consumeCancelledPlacement(entry.getKey())) return true;
            BlockState required = schematicWorld.getBlockState(entry.getKey());
            if (mc.world.getBlockState(entry.getKey()).equals(required)) return true;
            if (normalTick - entry.getValue() < 20) return false;
            placementCooldowns.put(entry.getKey(), 20);
            return true;
        });
        if (normalTick < nextNormalPlacementTick) return;

        List<Candidate> candidates = collectProjectionCandidates(schematicWorld);
        if (candidates == null) return;
        // Map Art Maker picks the nearest currently reachable matrix cell.
        // Ignore the old Xiaohe rolling cursor and per-tick packet burst.
        candidates.sort(Comparator.comparingDouble(Candidate::distanceSquared));
        for (Candidate candidate : candidates) {
            BlockPos pos = candidate.pos();
            BlockState required = candidate.desiredStates().getFirst();
            if (required == null || required.isAir()
                || mc.world.getBlockState(pos).equals(required)
                || normalPendingPlacements.containsKey(pos)
                || placementCooldowns.containsKey(pos)) continue;

            ExternalPlacementResult result = placeProjectionBlock(pos, required, placeRange.get());
            if (result == ExternalPlacementResult.DISPATCHED) {
                normalPendingPlacements.put(pos, normalTick);
                nextNormalPlacementTick = normalTick + Math.max(1, (int) Math.ceil(20.0 / placementSpeed.get()));
                fadeEntries.add(new FadeEntry(renderFadeTicks.get(), pos.toImmutable()));
                return;
            }
            if (result == ExternalPlacementResult.BLOCKED) placementCooldowns.put(pos, 20);
        }
    }

    private void tickCooldowns() {
        placementCooldowns.replaceAll((pos, ticks) -> ticks - 1);
        placementCooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private void tickFadeEntries() {
        for (int index = fadeEntries.size() - 1; index >= 0; index--) {
            FadeEntry entry = fadeEntries.get(index);
            entry.ticksRemaining--;
            if (entry.ticksRemaining <= 0) fadeEntries.remove(index);
        }
    }

    /** Xiaohe normal mode: a local cubic Litematica scan. */
    private List<Candidate> collectProjectionCandidates(WorldSchematic schematicWorld) {
        if (schematicWorld == null) {
            if (!missingSchematicReported) {
                warning("请在启用打印机前加载 Litematica 投影。");
                missingSchematicReported = true;
            }
            return null;
        }

        int radius = (int) Math.ceil(placeRange.get());
        double rangeSquared = placeRange.get() * placeRange.get();
        BlockPos origin = mc.player.getBlockPos();
        Vec3d playerPos = getPlayerPos();
        List<Candidate> candidates = new ArrayList<>();

        // Xiaohe scans y from -radius inclusive to radius exclusive, and
        // x/z inclusively. The visual layer filter belongs to this source scan.
        for (int y = -radius; y < radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = origin.add(x, y, z);
                    if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

                    double distance = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (distance > rangeSquared) continue;

                    candidates.add(new Candidate(
                        pos.toImmutable(),
                        distance,
                        List.of(schematicWorld.getBlockState(pos))
                    ));
                }
            }
        }

        sortCandidates(candidates);
        return candidates;
    }

    /** Xiaohe platform mode: build a local floor below the player. */
    private List<Candidate> collectPlatformCandidates() {
        if (platformBlocks.get().isEmpty()) {
            warning("平台方块列表为空，已停止打印机。");
            toggle();
            return null;
        }

        List<BlockState> desiredStates = new ArrayList<>();
        for (Block block : platformBlocks.get()) {
            BlockState state = block.getDefaultState();
            if (state.contains(Properties.SLAB_TYPE)) {
                state = state.with(Properties.SLAB_TYPE, upperSlab.get() ? SlabType.TOP : SlabType.BOTTOM);
            }
            desiredStates.add(state);
        }

        BlockPos base = findPlatformBase();
        int radius = placeRange.get().intValue();
        double rangeSquared = placeRange.get() * placeRange.get();
        Vec3d playerPos = getPlayerPos();
        List<Candidate> candidates = new ArrayList<>();

        for (int x = base.getX() - radius; x <= base.getX() + radius; x++) {
            for (int z = base.getZ() - radius; z <= base.getZ() + radius; z++) {
                BlockPos pos = new BlockPos(x, base.getY(), z);
                double distance = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos));
                if (distance > rangeSquared) continue;
                candidates.add(new Candidate(pos, distance, desiredStates));
            }
        }

        sortCandidates(candidates);
        return candidates;
    }

    /** Mirrors Xiaohe y(): descend up to three cells and select the floor y. */
    private BlockPos findPlatformBase() {
        Vec3d playerPos = getPlayerPos();
        BlockPos base = mc.player.getBlockPos();
        if (playerPos.y - base.getY() >= 0.5) base = base.up();

        for (int depth = 0; depth < 3; depth++) {
            base = base.down();
            boolean west = playerPos.x < base.getX() + 0.5;
            boolean north = playerPos.z < base.getZ() + 0.5;
            int xStep = west ? -1 : 1;
            int zStep = north ? -1 : 1;

            for (BlockPos pos : List.of(
                base,
                base.add(xStep, 0, 0),
                base.add(xStep, 0, zStep),
                base.add(0, 0, zStep)
            )) {
                if (isXiaoheSolid(mc.world.getBlockState(pos))) return base;
            }
        }

        return mc.player.getBlockPos().down();
    }

    /** Xiaohe anti-spawn mode: find one exposed spawnable floor in each column. */
    private List<Candidate> collectAntiSpawnCandidates() {
        if (antiSpawnBlocks.get().isEmpty()) {
            warning("防刷怪方块列表为空，已停止打印机。");
            toggle();
            return null;
        }

        List<BlockState> desiredStates = antiSpawnBlocks.get().stream().map(Block::getDefaultState).toList();
        int radius = (int) Math.ceil(placeRange.get());
        double rangeSquared = placeRange.get() * placeRange.get();
        BlockPos origin = mc.player.getBlockPos();
        Vec3d playerPos = getPlayerPos();
        List<Candidate> candidates = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos found = null;
                for (int y = 1; y >= -radius; y--) {
                    BlockPos pos = origin.add(x, y, z);
                    BlockState current = mc.world.getBlockState(pos);
                    BlockState below = mc.world.getBlockState(pos.down());
                    if (!isEmptyNonFluidCollision(current, pos)
                        || !isXiaoheSolid(below)
                        || !BlockUtils.isValidSpawnBlock(below)) continue;

                    boolean twoBlocksHigh = mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty();
                    if (twoBlocksHigh || antiSmallMobs.get()) {
                        found = pos;
                        break;
                    }
                }

                if (found == null) continue;
                double distance = playerPos.squaredDistanceTo(Vec3d.ofCenter(found));
                if (distance > rangeSquared) continue;
                candidates.add(new Candidate(found.toImmutable(), distance, desiredStates));
            }
        }

        sortCandidates(candidates);
        return candidates;
    }

    private void sortCandidates(List<Candidate> candidates) {
        Comparator<Candidate> comparator = Comparator.comparingDouble(Candidate::distanceSquared);
        candidates.sort(printPriority.get() == PrinterPriority.NEAREST ? comparator : comparator.reversed());
    }

    /** Avoids depending on the getPos() mapping name used by another Yarn version. */
    private Vec3d getPlayerPos() {
        return new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    /** Xiaohe a(List): rolling cursor, strict face-test budget, then direct queue. */
    private void preparePlacements(List<Candidate> candidates) {
        preparedPlacements.clear();
        if (candidates.isEmpty()) return;

        int faceChecksRemaining = faceValidationLimit.get();
        int examined = 0;
        int size = candidates.size();

        while (examined < size && faceChecksRemaining > 0 && preparedPlacements.size() < printCount.get()) {
            Candidate candidate = candidates.get((scanCursor + examined) % size);
            examined++;
            BlockPos pos = candidate.pos();

            // Xiaohe only enforces layer max during this selection phase.
            if (pos.getY() > DataManager.getRenderLayerRange().getLayerMax()) continue;

            BlockState currentState = mc.world.getBlockState(pos);
            if (!isEligibleTarget(currentState)) continue;
            if (placementTimeout.get() != 0 && placementCooldowns.containsKey(pos)) continue;
            if (!currentState.isReplaceable()) continue;

            for (BlockState requiredState : candidate.desiredStates()) {
                if (requiredState == null || requiredState.isAir()) continue;
                Block block = requiredState.getBlock();
                if (blacklist.get().contains(block)) continue;

                Item item = block.asItem();
                if (!(item instanceof BlockItem)) continue;
                if (wouldIntersectAnEntity(pos, requiredState)) continue;

                // Xiaohe only checks inventory existence during candidate
                // selection. Its v() dispatcher moves/selects the stack
                // immediately before the interaction, so selecting several
                // candidates never rearranges inventory while scanning.
                if (!InvUtils.find(item).found()) continue;

                // This exactly corresponds to HeBlockUtils.a(pos, range, 0).
                faceChecksRemaining--;
                PlacementPlan plan = createPlacementPlan(pos, requiredState, placeRange.get());
                if (plan == null) break;

                preparedPlacements.add(new PreparedPlacement(pos, requiredState, item, plan));
                break;
            }
        }

        scanCursor = (scanCursor + examined) % size;
    }

    private boolean isEligibleTarget(BlockState currentState) {
        if (printerMode.get() == PrinterMode.ANTI_SPAWN) {
            if (!currentState.isAir() && !(currentState.getBlock() instanceof PlantBlock)) return false;
            return !antiSpawnBlocks.get().contains(currentState.getBlock());
        }

        // HeBlockUtils.b(BlockState): skip occupied non-fluid blocks.
        return !isXiaoheSolid(currentState);
    }

    private static boolean isXiaoheSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private boolean isEmptyNonFluidCollision(BlockState state, BlockPos pos) {
        return state.getCollisionShape(mc.world, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** Xiaohe HePosUtils.a(pos, state): reject a state colliding with an entity. */
    private boolean wouldIntersectAnEntity(BlockPos pos, BlockState requiredState) {
        VoxelShape shape = requiredState.getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) return false;

        VoxelShape worldShape = shape.offset(pos.getX(), pos.getY(), pos.getZ());
        for (Entity entity : mc.world.getOtherEntities(null, new Box(pos), ignored -> true)) {
            if (VoxelShapes.matchesAnywhere(
                worldShape,
                VoxelShapes.cuboid(entity.getBoundingBox()),
                BooleanBiFunction.AND
            )) return true;
        }
        return false;
    }

    /** Xiaohe v(): select hotbar, rotate and interact without a confirmation queue. */
    private void dispatchPreparedPlacements() {
        for (PreparedPlacement placement : preparedPlacements) {
            BlockPos pos = placement.pos();
            PlacementPlan plan = placement.plan();
            FindItemResult slot = moveToHotbar(placement.item());
            if (!slot.found() || !slot.isHotbar()) continue;
            if (mc.player.getInventory().getSelectedSlot() != slot.slot()) {
                InvUtils.swap(slot.slot(), false);
            }

            PlacementRotation rotation = getXiaohePlacementRotation(plan, placement.requiredState());
            Rotations.rotate(rotation.yaw(), rotation.pitch(), ROTATION_PRIORITY, () ->
                interactWithSupportSneak(plan, pos, placement.item(), false)
            );

            fadeEntries.add(new FadeEntry(renderFadeTicks.get(), pos.toImmutable()));
            placementCooldowns.put(pos.toImmutable(), placementTimeout.get());
            // HeInvUtils.e(): flush the handled-screen inventory action just
            // as Xiaohe does after every dispatched printer interaction.
            mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        }
        preparedPlacements.clear();
    }

    /**
     * Performs one projection placement without changing this module's active
     * state.  The map-art worker uses this entry point so both features share
     * the exact same item selection, support-face, raycast and rotation path,
     * while their switches and progress remain completely independent.
     */
    public ExternalPlacementResult placeProjectionBlock(BlockPos pos, BlockState requiredState, double range) {
        return placeProjectionBlock(pos, requiredState, range, 0);
    }

    public ExternalPlacementResult placeProjectionBlock(BlockPos pos, BlockState requiredState, double range, int attempt) {
        if (mc.player == null || mc.world == null || requiredState == null || requiredState.isAir()) {
            return ExternalPlacementResult.BLOCKED;
        }

        BlockState currentState = mc.world.getBlockState(pos);
        if (currentState.equals(requiredState)) return ExternalPlacementResult.COMPLETE;
        if (!currentState.isReplaceable() || wouldIntersectAnEntity(pos, requiredState)) {
            return ExternalPlacementResult.BLOCKED;
        }

        Item item = requiredState.getBlock().asItem();
        if (!(item instanceof BlockItem)) return ExternalPlacementResult.BLOCKED;

        FindItemResult slot = moveToHotbar(item);
        if (!slot.found()) return ExternalPlacementResult.MISSING_ITEM;
        if (!slot.isHotbar()) return ExternalPlacementResult.BLOCKED;

        PlacementPlan plan = createPlacementPlan(pos, requiredState, range, attempt);
        if (plan == null) return ExternalPlacementResult.BLOCKED;

        if (mc.player.getInventory().getSelectedSlot() != slot.slot()) InvUtils.swap(slot.slot(), false);
        cancelledPlacements.remove(pos);
        PlacementRotation rotation = getXiaohePlacementRotation(plan, requiredState);
        BlockPos immutablePos = pos.toImmutable();
        Object token = new Object();
        queuedProjectionPlacements.put(immutablePos, token);
        Rotations.rotate(rotation.yaw(), rotation.pitch(), ROTATION_PRIORITY, () -> {
            if (queuedProjectionPlacements.get(immutablePos) != token) return;
            queuedProjectionPlacements.remove(immutablePos);
            interactWithSupportSneak(plan, immutablePos, item, true);
        });
        mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
        return ExternalPlacementResult.DISPATCHED;
    }

    public boolean consumeCancelledPlacement(BlockPos pos) {
        return cancelledPlacements.remove(pos);
    }

    public boolean hasQueuedProjectionPlacement() {
        return !queuedProjectionPlacements.isEmpty();
    }

    public void cancelQueuedProjectionPlacement(BlockPos pos) {
        queuedProjectionPlacements.remove(pos);
    }

    private void interactWithSupportSneak(PlacementPlan plan, BlockPos targetPos, Item expectedItem, boolean trackCancellation) {
        if (mc.player == null || mc.world == null) {
            if (trackCancellation) cancelledPlacements.add(targetPos.toImmutable());
            return;
        }
        // Rotation callbacks may run after an inventory change. Never click a
        // container with an empty or wrong hand; let the caller retry instead.
        if (!mc.player.getMainHandStack().isOf(expectedItem)) {
            if (trackCancellation) cancelledPlacements.add(targetPos.toImmutable());
            return;
        }
        BlockPos supportPos = plan.hitResult().getBlockPos();
        boolean clickableSupport = BlockUtils.isClickable(mc.world.getBlockState(supportPos).getBlock());
        boolean temporarySneak = clickableSupport && !mc.player.isSneaking();
        if (clickableSupport) {
            // A dispenser is already covered by BlockWithEntity in Meteor's
            // clickable list. Resend sneak for every such click: local sneak
            // state can be ahead of the server after movement or rotation.
            mc.player.setSneaking(true);
            sendSneaking(true);
        }
        try {
            if (clickableSupport) {
                // Meteor's BlockUtils.interact() forcibly clears the local
                // sneaking flag before clicking. Use the same vanilla click
                // directly here so dispensers/chests do not open instead of
                // accepting the block item placed against them.
                var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, plan.hitResult());
                if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                BlockUtils.interact(plan.hitResult(), Hand.MAIN_HAND, true);
            }
        } finally {
            if (temporarySneak && mc.player != null) {
                mc.player.setSneaking(false);
                sendSneaking(false);
            }
        }
    }

    private void sendSneaking(boolean sneaking) {
        // Preserve the player's current movement inputs. Sending all-false
        // flags here stopped walking for a tick whenever a clickable support
        // (such as a dispenser) needed temporary sneak placement.
        PlayerInput current = mc.player.input.playerInput;
        if (current == null) current = PlayerInput.DEFAULT;
        mc.player.networkHandler.sendPacket(new PlayerInputC2SPacket(
            new PlayerInput(current.forward(), current.backward(), current.left(), current.right(),
                current.jump(), sneaking, current.sprint())
        ));
    }

    /** Xiaohe inventory behavior: hotbar first, then move inventory into hotbar. */
    private FindItemResult moveToHotbar(Item item) {
        FindItemResult hotbar = InvUtils.findInHotbar(item);
        if (hotbar.found()) return hotbar;

        FindItemResult inventory = InvUtils.find(item);
        if (!inventory.found() || !inventory.isMain()) return inventory;

        // Xiaohe HeInvUtils.b(slot) moves a main-inventory stack into the
        // currently selected hotbar slot; it does not search for an empty
        // hotbar slot first.
        int destination = mc.player.getInventory().getSelectedSlot();
        InvUtils.move().from(inventory.slot()).toHotbar(destination);
        return InvUtils.findInHotbar(item);
    }

    private PlacementPlan createPlacementPlan(BlockPos targetPos, BlockState requiredState, double range) {
        return createPlacementPlan(targetPos, requiredState, range, 0);
    }

    private PlacementPlan createPlacementPlan(BlockPos targetPos, BlockState requiredState, double range, int attempt) {
        if (requiredState.getBlock() instanceof CarpetBlock) {
            return createCarpetPlacementPlan(targetPos, range, attempt);
        }

        List<PlacementPlan> plans = new ArrayList<>();
        for (Direction supportDirection : findXiaoheSupportDirections(targetPos, range)) {
            BlockPos supportPos = targetPos.offset(supportDirection);
            Direction clickedFace = supportDirection.getOpposite();
            if (!matchesXiaoheAxisPlacement(clickedFace, requiredState)) continue;

            Vec3d hit = Vec3d.ofCenter(supportPos).add(
                clickedFace.getOffsetX() * 0.45,
                clickedFace.getOffsetY() * 0.45,
                clickedFace.getOffsetZ() * 0.45
            );
            hit = adjustXiaoheHitPosition(targetPos, hit, clickedFace, requiredState);
            plans.add(new PlacementPlan(new BlockHitResult(hit, clickedFace, supportPos, false), hit));
        }

        if (plans.isEmpty()) return null;
        plans.sort(Comparator.comparingInt(plan -> xiaohePlacementPriority(plan, requiredState)));
        int bestPriority = xiaohePlacementPriority(plans.getFirst(), requiredState);
        int alternatives = 1;
        while (alternatives < plans.size()
            && xiaohePlacementPriority(plans.get(alternatives), requiredState) == bestPriority) alternatives++;
        return plans.get(Math.floorMod(attempt, alternatives));
    }

    /** Prefer the floor's top face and try small, validated aim adjustments. */
    private PlacementPlan createCarpetPlacementPlan(BlockPos targetPos, double range, int attempt) {
        BlockPos supportPos = targetPos.down();
        BlockState support = mc.world.getBlockState(supportPos);
        if (support.getCollisionShape(mc.world, supportPos).isEmpty()
            || support.isReplaceable()) return null;

        Vec3d eye = mc.player.getEyePos();
        Vec3d center = getXiaoheSupportFaceCenter(supportPos, Direction.UP);
        Box bounds = support.getCollisionShape(mc.world, supportPos).getBoundingBox();
        double dx = Math.min(0.2, (bounds.maxX - bounds.minX) * 0.25);
        double dz = Math.min(0.2, (bounds.maxZ - bounds.minZ) * 0.25);
        Vec3d[] samples = { center, center.add(dx, 0, 0), center.add(-dx, 0, 0),
            center.add(0, 0, dz), center.add(0, 0, -dz) };
        for (int offset = 0; offset < samples.length; offset++) {
            Vec3d hit = samples[Math.floorMod(attempt + offset, samples.length)];
            if (eye.distanceTo(hit) > range) continue;
            // Extend just inside the surface to require a real hit on this
            // exact face. An intervening carpet or wall remains an obstacle.
            BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, hit.add(0, -0.001, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(supportPos)
                || ray.getSide() != Direction.UP) continue;
            return new PlacementPlan(new BlockHitResult(hit, Direction.UP, supportPos, false), hit);
        }
        return null;
    }

    private List<Direction> findXiaoheSupportDirections(BlockPos targetPos, double range) {
        List<Direction> directions = new ArrayList<>();
        Vec3d eye = mc.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(targetPos);

        for (Direction supportDirection : Direction.values()) {
            Direction clickedFace = supportDirection.getOpposite();
            BlockPos supportPos = targetPos.offset(supportDirection);
            BlockState support = mc.world.getBlockState(supportPos);
            if (!isXiaoheVisibleFace(eye, targetCenter, clickedFace)
                || support.getCollisionShape(mc.world, supportPos).isEmpty()
                || support.isReplaceable()) continue;

            Vec3d supportFace = getXiaoheSupportFaceCenter(supportPos, clickedFace);
            if (!isXiaoheRayClear(eye, supportFace, supportPos) || eye.distanceTo(supportFace) > range) continue;
            directions.add(supportDirection);
        }

        directions.sort(Comparator.comparingDouble(direction -> {
            BlockPos supportPos = targetPos.offset(direction);
            return eye.squaredDistanceTo(getXiaoheSupportFaceCenter(supportPos, direction.getOpposite()));
        }));
        return directions;
    }

    private boolean isXiaoheRayClear(Vec3d eye, Vec3d supportFace, BlockPos expectedSupport) {
        HitResult result = mc.world.raycast(new RaycastContext(
            eye,
            supportFace,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
        // The ray ends on the face we intentionally want to click. Depending
        // on the exact floating-point edge, Minecraft reports that endpoint
        // either as MISS or as a hit on the support itself. Both are clear;
        // only a different intervening block makes the placement unsafe.
        return result == null || result.getType() == HitResult.Type.MISS
            || result instanceof BlockHitResult hit && hit.getBlockPos().equals(expectedSupport);
    }

    private static boolean isXiaoheVisibleFace(Vec3d eye, Vec3d targetCenter, Direction face) {
        return switch (face) {
            case DOWN -> eye.y - targetCenter.y <= 0.5;
            case UP -> eye.y - targetCenter.y >= -0.5;
            case NORTH -> eye.z - targetCenter.z <= 0.5;
            case SOUTH -> eye.z - targetCenter.z >= -0.5;
            case WEST -> eye.x - targetCenter.x <= 0.5;
            case EAST -> eye.x - targetCenter.x >= -0.5;
        };
    }

    private Vec3d getXiaoheSupportFaceCenter(BlockPos supportPos, Direction clickedFace) {
        Box box = mc.world.getBlockState(supportPos).getCollisionShape(mc.world, supportPos).getBoundingBox();
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        return new Vec3d(
            supportPos.getX() + box.minX + halfX + clickedFace.getOffsetX() * halfX,
            supportPos.getY() + box.minY + halfY + clickedFace.getOffsetY() * halfY,
            supportPos.getZ() + box.minZ + halfZ + clickedFace.getOffsetZ() * halfZ
        );
    }

    private PlacementRotation getXiaohePlacementRotation(PlacementPlan plan, BlockState requiredState) {
        Direction requiredLook = getXiaoheRequiredLookDirection(requiredState);
        if (requiredLook == null) {
            return new PlacementRotation(Rotations.getYaw(plan.rotationTarget()), Rotations.getPitch(plan.rotationTarget()));
        }

        return switch (requiredLook) {
            case UP -> new PlacementRotation(0, -90);
            case DOWN -> new PlacementRotation(0, 90);
            case NORTH -> new PlacementRotation(180, 5);
            case SOUTH -> new PlacementRotation(0, 5);
            case WEST -> new PlacementRotation(90, 5);
            case EAST -> new PlacementRotation(-90, 5);
        };
    }

    private static Direction getXiaoheRequiredLookDirection(BlockState requiredState) {
        Direction targetFacing;
        if (requiredState.contains(Properties.FACING)) targetFacing = requiredState.get(Properties.FACING);
        else if (requiredState.contains(Properties.HORIZONTAL_FACING)) targetFacing = requiredState.get(Properties.HORIZONTAL_FACING);
        else if (requiredState.contains(Properties.HOPPER_FACING)) targetFacing = requiredState.get(Properties.HOPPER_FACING);
        else return null;

        return requiredState.getBlock() == Blocks.OBSERVER || requiredState.getBlock() == Blocks.HOPPER
            ? targetFacing
            : targetFacing.getOpposite();
    }

    private static boolean matchesXiaoheAxisPlacement(Direction clickedFace, BlockState requiredState) {
        if (requiredState.contains(Properties.AXIS)) {
            return clickedFace.getAxis() == requiredState.get(Properties.AXIS);
        }
        return !requiredState.contains(Properties.HORIZONTAL_AXIS)
            || clickedFace.getAxis() == requiredState.get(Properties.HORIZONTAL_AXIS);
    }

    private static Vec3d adjustXiaoheHitPosition(
        BlockPos targetPos,
        Vec3d hit,
        Direction clickedFace,
        BlockState requiredState
    ) {
        if (!clickedFace.getAxis().isHorizontal()) return hit;

        if (requiredState.contains(Properties.SLAB_TYPE)) {
            SlabType type = requiredState.get(Properties.SLAB_TYPE);
            if (type == SlabType.TOP) return new Vec3d(hit.x, targetPos.getY() + 0.75, hit.z);
            if (type == SlabType.BOTTOM) return new Vec3d(hit.x, targetPos.getY() + 0.25, hit.z);
        }

        if (requiredState.contains(Properties.BLOCK_HALF)) {
            double y = requiredState.get(Properties.BLOCK_HALF) == BlockHalf.TOP ? 0.75 : 0.25;
            return new Vec3d(hit.x, targetPos.getY() + y, hit.z);
        }
        return hit;
    }

    private static int xiaohePlacementPriority(PlacementPlan plan, BlockState requiredState) {
        Direction clickedFace = plan.hitResult().getSide();
        if (requiredState.contains(Properties.SLAB_TYPE)) {
            SlabType type = requiredState.get(Properties.SLAB_TYPE);
            if (type == SlabType.TOP) return clickedFace == Direction.DOWN ? 0 : clickedFace.getAxis().isHorizontal() ? 1 : 2;
            if (type == SlabType.BOTTOM) return clickedFace == Direction.UP ? 0 : clickedFace.getAxis().isHorizontal() ? 1 : 2;
        }

        if (requiredState.contains(Properties.BLOCK_HALF)) {
            return requiredState.get(Properties.BLOCK_HALF) == BlockHalf.TOP
                ? clickedFace == Direction.DOWN ? 0 : clickedFace.getAxis().isHorizontal() ? 1 : 2
                : clickedFace == Direction.UP ? 0 : clickedFace.getAxis().isHorizontal() ? 1 : 2;
        }
        return 0;
    }

    /** Retained for the independent patrol module's completion scan. */
    public boolean isProjectionSatisfied(BlockState currentState, BlockState requiredState) {
        return currentState.equals(requiredState);
    }

    public enum ExternalPlacementResult {
        COMPLETE,
        DISPATCHED,
        MISSING_ITEM,
        BLOCKED
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        SettingColor configured = renderColor.get();
        for (FadeEntry entry : fadeEntries) {
            int alpha = configured.a * entry.ticksRemaining / renderFadeTicks.get();
            Color color = new Color(configured.r, configured.g, configured.b, alpha);
            event.renderer.box(entry.pos, color, null, ShapeMode.Sides, 0);
        }
    }

    private record Candidate(BlockPos pos, double distanceSquared, List<BlockState> desiredStates) {}

    private record PreparedPlacement(BlockPos pos, BlockState requiredState, Item item, PlacementPlan plan) {}

    private record PlacementPlan(BlockHitResult hitResult, Vec3d rotationTarget) {}

    private record PlacementRotation(double yaw, double pitch) {}

    private static final class FadeEntry {
        private int ticksRemaining;
        private final BlockPos pos;

        private FadeEntry(int ticksRemaining, BlockPos pos) {
            this.ticksRemaining = ticksRemaining;
            this.pos = pos;
        }
    }

    public enum PrinterPriority {
        FURTHEST("最远"),
        NEAREST("最近");

        private final String title;

        PrinterPriority(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum PrinterMode {
        NORMAL("普通"),
        PLATFORM("平台打印"),
        ANTI_SPAWN("防刷怪");

        private final String title;

        PrinterMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
