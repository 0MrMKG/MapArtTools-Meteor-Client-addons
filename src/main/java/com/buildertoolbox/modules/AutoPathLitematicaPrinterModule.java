package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Walks to incomplete parts of the active Litematica placement and lets Place Litematica print them.
 * Navigation is deliberately local and continuously re-planned from server-visible world state.
 */
public final class AutoPathLitematicaPrinterModule extends Module {
    private static final int MOVEMENT_PRIORITY = 40;
    private static final int MAX_TARGETS = 1_024;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final SettingGroup sgScaffold = settings.createGroup("Scaffold");

    private final Setting<Boolean> enablePrinter = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-place-litematica")
        .description("Enable Place Litematica automatically while this module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> scanBudget = sgGeneral.add(new IntSetting.Builder()
        .name("scan-blocks-per-tick")
        .description("Projection positions inspected each tick while looking for the next unfinished area.")
        .defaultValue(1_024)
        .range(64, 8_192)
        .sliderRange(64, 2_048)
        .build()
    );

    private final Setting<Integer> waitForPrinter = sgGeneral.add(new IntSetting.Builder()
        .name("wait-for-printer")
        .description("Ticks to wait at an in-range target before temporarily skipping it when it cannot be printed.")
        .defaultValue(80)
        .range(20, 400)
        .sliderRange(20, 160)
        .build()
    );

    private final Setting<Integer> deferredTargetDelay = sgGeneral.add(new IntSetting.Builder()
        .name("deferred-target-delay")
        .description("Ticks before a target that could not be reached or printed is considered again.")
        .defaultValue(200)
        .range(40, 2_400)
        .sliderRange(40, 600)
        .build()
    );

    private final Setting<Integer> pathDistance = sgNavigation.add(new IntSetting.Builder()
        .name("path-distance")
        .description("Local look-ahead radius. The route is rebuilt from the latest placed blocks instead of targeting a distant fixed point.")
        .defaultValue(10)
        .range(8, 64)
        .sliderRange(8, 24)
        .build()
    );

    private final Setting<Integer> maxPathNodes = sgNavigation.add(new IntSetting.Builder()
        .name("max-path-nodes")
        .description("A* search limit per route. Higher values handle complex terrain but use more CPU.")
        .defaultValue(4_096)
        .range(256, 20_000)
        .sliderRange(256, 8_192)
        .build()
    );

    private final Setting<Integer> repathDelay = sgNavigation.add(new IntSetting.Builder()
        .name("repath-delay")
        .description("Ticks between local route recalculations after movement or newly placed projection blocks change the terrain.")
        .defaultValue(4)
        .range(2, 60)
        .sliderRange(2, 12)
        .build()
    );

    private final Setting<Boolean> jump = sgNavigation.add(new BoolSetting.Builder()
        .name("jump")
        .description("Jump when the selected route rises by one block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useScaffolding = sgScaffold.add(new BoolSetting.Builder()
        .name("use-scaffolding")
        .description("Only use temporary bridge supports when normal walking and jumping cannot reach the target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Block> scaffoldBlock = sgScaffold.add(new BlockSetting.Builder()
        .name("scaffold-block")
        .description("Block used for temporary bridge supports. Keep this on the hotbar or allow inventory movement below.")
        .defaultValue(Blocks.COBBLESTONE)
        .visible(useScaffolding::get)
        .build()
    );

    private final Setting<Boolean> moveScaffoldFromInventory = sgScaffold.add(new BoolSetting.Builder()
        .name("move-scaffold-from-inventory")
        .description("Move the configured scaffold block into the hotbar when it is only in the main inventory.")
        .defaultValue(true)
        .visible(useScaffolding::get)
        .build()
    );

    private final Setting<Boolean> removeScaffolding = sgScaffold.add(new BoolSetting.Builder()
        .name("remove-temporary-scaffolding")
        .description("Break only scaffolding placed by this module once it no longer supports the route, then print the projection block there.")
        .defaultValue(true)
        .visible(useScaffolding::get)
        .build()
    );

    private final List<ScanBox> scanBoxes = new ArrayList<>();
    private final List<BlockPos> targetCandidates = new ArrayList<>();
    private final Set<BlockPos> queuedTargets = new HashSet<>();
    private final Set<BlockPos> temporaryScaffolds = new HashSet<>();
    private final Map<BlockPos, Long> deferredTargets = new HashMap<>();
    private List<BlockPos> path = List.of();
    private LitematicaPrinterModule printer;
    private BlockPos activeTarget;
    private BlockPos lastScaffoldPos;
    private BlockPos lastCleanupPos;
    private int scanBoxIndex;
    private int scanX;
    private int scanY;
    private int scanZ;
    private int scanMinY;
    private int scanMaxY;
    private int pathIndex;
    private long tick;
    private long activeTargetSince;
    private long lastPathTick;
    private long noProgressSince;
    private long lastScaffoldAttempt;
    private long lastCleanupAttempt;
    private long scanFinishedAt;
    private boolean startedPrinter;
    private boolean missingSchematicReported;
    private boolean printerDisabledReported;
    private boolean missingScaffoldReported;
    private boolean scanLayerComplete;

    public AutoPathLitematicaPrinterModule() {
        super(BuilderToolboxAddon.CATEGORY, "auto-path-place-litematica", "自动寻路、跳搭并使用脚手架完成当前 Litematica 投影。需要 Place Litematica。");
    }

    @Override
    public void onActivate() {
        resetState();
        printer = Modules.get().get(LitematicaPrinterModule.class);
        if (printer == null) {
            error("Place Litematica is not available.");
            disable();
            return;
        }

        if (enablePrinter.get() && !printer.isActive()) {
            printer.enable();
            startedPrinter = true;
        }

        refreshScanBoxes();
        syncPrinterLayer();
    }

    @Override
    public void onDeactivate() {
        releaseMovement();
        if (printer != null) printer.clearAutomaticLayer();
        if (startedPrinter && printer != null && printer.isActive()) printer.disable();
        startedPrinter = false;
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        if (mc.currentScreen != null) {
            releaseMovement();
            return;
        }

        if (printer == null) printer = Modules.get().get(LitematicaPrinterModule.class);
        if (printer == null || !printer.isActive()) {
            releaseMovement();
            if (!printerDisabledReported) {
                warning("Place Litematica must stay enabled while auto path printing.");
                printerDisabledReported = true;
            }
            return;
        }
        printerDisabledReported = false;
        syncPrinterLayer();

        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            releaseMovement();
            if (!missingSchematicReported) {
                warning("Load a Litematica schematic before enabling auto path printing.");
                missingSchematicReported = true;
            }
            return;
        }
        missingSchematicReported = false;

        if (scanBoxes.isEmpty() || scanFinishedAt > 0 && tick - scanFinishedAt >= 200 && activeTarget == null && targetCandidates.isEmpty()) {
            refreshScanBoxes();
        }
        restoreDeferredTargets(schematicWorld);
        scanForTargets(schematicWorld);
        cleanupTemporaryScaffolds(schematicWorld);

        if (activeTarget != null && !isTargetCandidate(activeTarget, schematicWorld)) clearActiveTarget();
        if (activeTarget == null) activeTarget = selectNextTarget(schematicWorld);

        if (activeTarget == null) {
            if (scanLayerComplete && targetCandidates.isEmpty() && !hasDeferredTargetsOnCurrentLayer()) {
                if (advanceBuildLayer()) return;
            }
            releaseMovement();
            return;
        }

        if (mc.player.getBlockPos().isWithinDistance(activeTarget, printer.getPlacementRange())) {
            releaseMovement();
            path = List.of();
            pathIndex = 0;
            if (activeTargetSince == 0) activeTargetSince = tick;
            if (tick - activeTargetSince >= waitForPrinter.get()) deferActiveTarget();
            return;
        }

        activeTargetSince = 0;
        boolean pathFinished = !path.isEmpty() && pathIndex >= path.size();
        if (pathFinished || tick - lastPathTick >= repathDelay.get()) {
            planPath();
        }

        followPath();
    }

    private void resetState() {
        scanBoxes.clear();
        targetCandidates.clear();
        queuedTargets.clear();
        temporaryScaffolds.clear();
        deferredTargets.clear();
        path = List.of();
        activeTarget = null;
        lastScaffoldPos = null;
        lastCleanupPos = null;
        scanBoxIndex = 0;
        scanX = 0;
        scanY = 0;
        scanZ = 0;
        scanMinY = Integer.MAX_VALUE;
        scanMaxY = Integer.MIN_VALUE;
        pathIndex = 0;
        tick = 0;
        activeTargetSince = 0;
        lastPathTick = -1_000;
        noProgressSince = 0;
        lastScaffoldAttempt = Long.MIN_VALUE;
        lastCleanupAttempt = Long.MIN_VALUE;
        scanFinishedAt = 0;
        missingSchematicReported = false;
        printerDisabledReported = false;
        missingScaffoldReported = false;
        scanLayerComplete = false;
    }

    private void refreshScanBoxes() {
        scanBoxes.clear();
        scanMinY = Integer.MAX_VALUE;
        scanMaxY = Integer.MIN_VALUE;
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (!placement.isEnabled() || !placement.isRenderingEnabled()) continue;
            Box box = placement.getEclosingBox();
            if (box == null || box.getPos1() == null || box.getPos2() == null) continue;

            BlockPos first = box.getPos1();
            BlockPos second = box.getPos2();
            ScanBox scanBox = new ScanBox(
                Math.min(first.getX(), second.getX()), Math.max(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.max(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ()), Math.max(first.getZ(), second.getZ())
            );
            scanBoxes.add(scanBox);
            scanMinY = Math.min(scanMinY, scanBox.minY());
            scanMaxY = Math.max(scanMaxY, scanBox.maxY());
        }

        if (!scanBoxes.isEmpty()) {
            scanBoxes.sort(Comparator.comparingInt(ScanBox::minY));
            beginBuildLayer(scanMinY);
        } else {
            scanLayerComplete = true;
        }
        scanFinishedAt = 0;
    }

    private void scanForTargets(WorldSchematic schematicWorld) {
        for (int scanned = 0; scanned < scanBudget.get(); scanned++) {
            if (targetCandidates.size() >= MAX_TARGETS) return;
            BlockPos pos = nextScanPosition();
            if (pos == null) return;
            if (!isTargetCandidate(pos, schematicWorld)) continue;

            BlockPos immutablePos = pos.toImmutable();
            if (queuedTargets.add(immutablePos)) targetCandidates.add(immutablePos);
        }
    }

    private BlockPos nextScanPosition() {
        if (scanLayerComplete || scanBoxIndex >= scanBoxes.size()) return null;

        ScanBox box = scanBoxes.get(scanBoxIndex);
        BlockPos result = new BlockPos(scanX, scanY, scanZ);
        scanX++;
        if (scanX > box.maxX()) {
            scanX = box.minX();
            scanZ++;
            if (scanZ > box.maxZ()) {
                scanBoxIndex++;
                moveToNextBoxInCurrentLayer();
            }
        }

        return result;
    }

    private void beginBuildLayer(int y) {
        scanY = y;
        scanBoxIndex = 0;
        scanLayerComplete = false;
        moveToNextBoxInCurrentLayer();
        syncPrinterLayer();
    }

    private void moveToNextBoxInCurrentLayer() {
        while (scanBoxIndex < scanBoxes.size()) {
            ScanBox box = scanBoxes.get(scanBoxIndex);
            if (scanY >= box.minY() && scanY <= box.maxY()) {
                scanX = box.minX();
                scanZ = box.minZ();
                return;
            }
            scanBoxIndex++;
        }
        scanLayerComplete = true;
    }

    private boolean advanceBuildLayer() {
        if (scanY >= scanMaxY) {
            if (scanFinishedAt == 0) scanFinishedAt = tick;
            return false;
        }
        beginBuildLayer(scanY + 1);
        return true;
    }

    private void syncPrinterLayer() {
        if (printer != null && scanY >= scanMinY && scanY <= scanMaxY) printer.setAutomaticLayer(scanY);
    }

    private boolean isTargetCandidate(BlockPos pos, WorldSchematic schematicWorld) {
        if (isDeferred(pos)) return false;

        BlockState requiredState = schematicWorld.getBlockState(pos);
        if (requiredState.isAir() || !requiredState.getFluidState().isEmpty()) return false;
        if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) return false;

        BlockState currentState = mc.world.getBlockState(pos);
        return currentState.isReplaceable()
            && !printer.isProjectionSatisfied(currentState, requiredState)
            && hasRequiredMaterial(requiredState);
    }

    private boolean hasRequiredMaterial(BlockState requiredState) {
        Item requiredItem = requiredState.getBlock().asItem();
        if (requiredItem != Items.AIR && InvUtils.find(requiredItem).found()) return true;

        return requiredState.getBlock() == Blocks.GRASS_BLOCK
            && printer.acceptsDirtForGrass()
            && InvUtils.find(Items.DIRT).found();
    }

    private BlockPos selectNextTarget(WorldSchematic schematicWorld) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int index = targetCandidates.size() - 1; index >= 0; index--) {
            BlockPos candidate = targetCandidates.get(index);
            if (!isTargetCandidate(candidate, schematicWorld)) {
                targetCandidates.remove(index);
                queuedTargets.remove(candidate);
                continue;
            }

            double distance = mc.player.squaredDistanceTo(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }

        if (best != null) {
            targetCandidates.remove(best);
            queuedTargets.remove(best);
        }
        return best;
    }

    private boolean isDeferred(BlockPos pos) {
        Long until = deferredTargets.get(pos);
        if (until == null) return false;
        if (until > tick) return true;
        deferredTargets.remove(pos);
        return false;
    }

    private void restoreDeferredTargets(WorldSchematic schematicWorld) {
        Iterator<Map.Entry<BlockPos, Long>> iterator = deferredTargets.entrySet().iterator();
        while (iterator.hasNext() && targetCandidates.size() < MAX_TARGETS) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            BlockPos pos = entry.getKey();
            if (pos.getY() != scanY || entry.getValue() > tick) continue;

            iterator.remove();
            if (isTargetCandidate(pos, schematicWorld) && queuedTargets.add(pos)) targetCandidates.add(pos);
        }
    }

    private boolean hasDeferredTargetsOnCurrentLayer() {
        for (Map.Entry<BlockPos, Long> entry : deferredTargets.entrySet()) {
            if (entry.getKey().getY() == scanY && entry.getValue() > tick) return true;
        }
        return false;
    }

    private void deferActiveTarget() {
        if (activeTarget != null) deferredTargets.put(activeTarget, tick + deferredTargetDelay.get());
        clearActiveTarget();
    }

    private void clearActiveTarget() {
        activeTarget = null;
        activeTargetSince = 0;
        path = List.of();
        pathIndex = 0;
        noProgressSince = 0;
        lastPathTick = -1_000;
    }

    private void planPath() {
        lastPathTick = tick;
        BlockPos start = mc.player.getBlockPos();
        List<BlockPos> newPath = findProgressPath(start, activeTarget, false);

        // Only fall back to temporary supports when ordinary local walking and jumping make no progress.
        if (newPath.isEmpty() && useScaffolding.get()) newPath = findProgressPath(start, activeTarget, true);
        if (newPath.isEmpty()) {
            path = List.of();
            pathIndex = 0;
            if (noProgressSince == 0) noProgressSince = tick;
            if (tick - noProgressSince >= Math.max(40, repathDelay.get() * 8L)) deferActiveTarget();
            return;
        }

        noProgressSince = 0;
        path = newPath;
        pathIndex = path.size() > 1 ? 1 : 0;
    }

    private List<BlockPos> findProgressPath(BlockPos start, BlockPos target, boolean allowScaffolding) {
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingInt(PathNode::score));
        Map<BlockPos, Integer> costs = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        int startDistance = estimateCost(start, target);
        PathNode bestProgress = null;
        int bestDistance = startDistance;
        open.add(new PathNode(start, 0, startDistance));
        costs.put(start, 0);
        int explored = 0;

        while (!open.isEmpty() && explored++ < maxPathNodes.get()) {
            PathNode node = open.poll();
            Integer currentCost = costs.get(node.pos());
            if (currentCost == null || currentCost != node.cost()) continue;

            int remainingDistance = estimateCost(node.pos(), target);
            if (!node.pos().equals(start) && remainingDistance < bestDistance) {
                bestProgress = node;
                bestDistance = remainingDistance;
            }
            if (!node.pos().equals(start) && node.pos().isWithinDistance(target, printer.getPlacementRange())) {
                return reconstructPath(previous, node.pos());
            }

            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                for (int yOffset = 1; yOffset >= -1; yOffset--) {
                    BlockPos next = node.pos().add(direction.getOffsetX(), yOffset, direction.getOffsetZ());
                    if (!isInsideSearchArea(start, next) || !isTraversable(next, allowScaffolding)) continue;

                    int nextCost = node.cost() + 10 + (yOffset > 0 ? 4 : 0) + (needsScaffold(next) ? 8 : 0);
                    Integer knownCost = costs.get(next);
                    if (knownCost != null && knownCost <= nextCost) continue;

                    BlockPos immutableNext = next.toImmutable();
                    costs.put(immutableNext, nextCost);
                    previous.put(immutableNext, node.pos());
                    open.add(new PathNode(immutableNext, nextCost, nextCost + estimateCost(next, target)));
                }
            }
        }

        return bestProgress == null ? List.of() : reconstructPath(previous, bestProgress.pos());
    }

    private boolean isInsideSearchArea(BlockPos start, BlockPos pos) {
        int limit = pathDistance.get();
        return Math.abs(pos.getX() - start.getX()) <= limit
            && Math.abs(pos.getY() - start.getY()) <= limit / 2
            && Math.abs(pos.getZ() - start.getZ()) <= limit;
    }

    private static int estimateCost(BlockPos from, BlockPos to) {
        return (Math.abs(from.getX() - to.getX()) + Math.abs(from.getZ() - to.getZ())) * 10
            + Math.abs(from.getY() - to.getY()) * 14;
    }

    private static List<BlockPos> reconstructPath(Map<BlockPos, BlockPos> previous, BlockPos goal) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos current = goal;
        result.add(current);
        while (previous.containsKey(current)) {
            current = previous.get(current);
            result.add(current);
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private boolean isTraversable(BlockPos pos, boolean allowScaffolding) {
        if (isWalkable(pos)) return true;
        return allowScaffolding && isFreeForPlayer(pos) && mc.world.getBlockState(pos.down()).isReplaceable();
    }

    private boolean isWalkable(BlockPos pos) {
        BlockState support = mc.world.getBlockState(pos.down());
        return isFreeForPlayer(pos) && !support.isReplaceable() && support.getFluidState().isEmpty();
    }

    private boolean isFreeForPlayer(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable() && mc.world.getBlockState(pos.up()).isReplaceable();
    }

    private void followPath() {
        if (path.isEmpty() || pathIndex >= path.size()) {
            releaseMovement();
            return;
        }

        BlockPos next = path.get(pathIndex);
        if (hasReached(next)) {
            pathIndex++;
            return;
        }

        if (needsScaffold(next)) {
            releaseMovement();
            ensureScaffold(next.down());
            return;
        }

        faceToward(next);
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(jump.get() && next.getY() > mc.player.getBlockPos().getY());
    }

    private boolean hasReached(BlockPos pos) {
        double x = pos.getX() + 0.5 - mc.player.getX();
        double z = pos.getZ() + 0.5 - mc.player.getZ();
        return x * x + z * z < 0.16 && Math.abs(pos.getY() - mc.player.getY()) < 1.2;
    }

    private boolean needsScaffold(BlockPos next) {
        return mc.world.getBlockState(next.down()).isReplaceable();
    }

    private void ensureScaffold(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return;
        if (!useScaffolding.get()) {
            deferActiveTarget();
            return;
        }
        if (pos.equals(lastScaffoldPos) && tick - lastScaffoldAttempt < 8) return;

        Item item = scaffoldBlock.get().asItem();
        FindItemResult result = InvUtils.findInHotbar(item);
        if (!result.found() && moveScaffoldFromInventory.get()) result = moveItemToHotbar(item);
        if (!result.found() || !result.isHotbar()) {
            if (!missingScaffoldReported) {
                warning("No %s available for scaffold bridging.", scaffoldBlock.get().getName().getString());
                missingScaffoldReported = true;
            }
            deferActiveTarget();
            return;
        }

        missingScaffoldReported = false;
        lastScaffoldPos = pos.toImmutable();
        lastScaffoldAttempt = tick;
        if (BlockUtils.place(pos, result, true, MOVEMENT_PRIORITY, true)) {
            temporaryScaffolds.add(pos.toImmutable());
        } else {
            deferActiveTarget();
        }
    }

    private void cleanupTemporaryScaffolds(WorldSchematic schematicWorld) {
        if (!removeScaffolding.get() || temporaryScaffolds.isEmpty()) return;

        Iterator<BlockPos> iterator = temporaryScaffolds.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            BlockState currentState = mc.world.getBlockState(pos);
            BlockState requiredState = schematicWorld.getBlockState(pos);
            if (printer.isProjectionSatisfied(currentState, requiredState)) {
                iterator.remove();
                continue;
            }
            if (currentState.getBlock() != scaffoldBlock.get()) {
                iterator.remove();
                continue;
            }
            if (!canSafelyRemoveScaffold(pos)) continue;
            if (pos.equals(lastCleanupPos) && tick - lastCleanupAttempt < 8) return;

            lastCleanupPos = pos.toImmutable();
            lastCleanupAttempt = tick;
            BlockUtils.breakBlock(pos, true);
            return;
        }
    }

    private boolean canSafelyRemoveScaffold(BlockPos pos) {
        if (!mc.player.getBlockPos().isWithinDistance(pos, printer.getPlacementRange())) return false;
        if (mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(pos))) return false;

        for (int index = pathIndex; index < path.size(); index++) {
            if (path.get(index).down().equals(pos)) return false;
        }
        return true;
    }

    private FindItemResult moveItemToHotbar(Item item) {
        FindItemResult inventoryItem = InvUtils.find(item);
        FindItemResult emptyHotbarSlot = InvUtils.findEmpty();
        if (!inventoryItem.found() || !inventoryItem.isMain()) return inventoryItem;

        int targetSlot = emptyHotbarSlot.found() && emptyHotbarSlot.isHotbar()
            ? emptyHotbarSlot.slot()
            : mc.player.getInventory().getSelectedSlot();
        InvUtils.move().from(inventoryItem.slot()).toHotbar(targetSlot);
        return InvUtils.findInHotbar(item);
    }

    private void faceToward(BlockPos pos) {
        Vec3d target = new Vec3d(pos.getX() + 0.5, mc.player.getY(), pos.getZ() + 0.5);
        double deltaX = target.x - mc.player.getX();
        double deltaZ = target.z - mc.player.getZ();
        mc.player.setYaw((float) (MathHelper.atan2(deltaZ, deltaX) * 180.0 / Math.PI - 90.0));
    }

    private void releaseMovement() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private record ScanBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {}

    private record PathNode(BlockPos pos, int cost, int score) {}
}
