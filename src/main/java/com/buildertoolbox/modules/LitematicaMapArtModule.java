package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NavigableMap;
import java.util.TreeMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * A flat-projection map-art worker. Its switch and task state are deliberately
 * independent from {@link LitematicaPrinterModule}; only the low-level block
 * interaction is shared with that module.
 */
public final class LitematicaMapArtModule extends Module {
    private static final long EMPTY_SUPPLY_RECHECK_TICKS = 20L * 60L;
    private static final long PLACEMENT_CONFIRM_TICKS = 20L;
    private static final int WORK_REGION_SIZE = 8;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSupply = settings.createGroup("Supplies");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");

    private final Setting<Boolean> registerSupply = sgSupply.add(new BoolSetting.Builder()
        .name("Select Supply Chests")
        .description("Hides the settings screen: right-click a labeled container to add it, press Esc to return here.")
        .defaultValue(false)
        .onChanged(this::onRegisterSupplyChanged)
        .build()
    );

    private final Setting<List<String>> registeredSupplies = sgSupply.add(new StringListSetting.Builder()
        .name("Supply Chest List")
        .description("Item ID -> X,Y,Z. This list is saved and can be edited before re-registering containers.")
        .defaultValue(new String[0])
        .onChanged(this::restoreSupplyList)
        .build()
    );

    private final Setting<Integer> laneWidth = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Band Width")
        .description("Spacing between serpentine working lines. Each line prints nearby rows; automatically limited by reach.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<WorkMode> workMode = sgGeneral.add(new EnumSetting.Builder<WorkMode>()
        .name("Work Mode")
        .description("Start Outward is the normal efficient route. Switch manually to One Round-Trip Snake to sweep from your current position to the far edge and back once.")
        .defaultValue(WorkMode.START_OUTWARD)
        .onChanged(this::onWorkModeChanged)
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("Place Range")
        .description("Maximum interaction range for map-art targets.")
        .defaultValue(3.6)
        .range(2.0, 3.6)
        .sliderRange(2.0, 3.6)
        .build()
    );

    private final Setting<Integer> placementSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("Placement Speed")
        .description("Maximum map-art placement attempts per second. Server confirmation can make the actual rate lower.")
        .defaultValue(10)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> statusMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Status Messages")
        .description("Shows a chat progress message every 10 seconds and whenever the worker stops.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> routeRetries = sgNavigation.add(new IntSetting.Builder()
        .name("Route Retries")
        .description("Replans before a target or supply chest is treated as unreachable.")
        .defaultValue(2)
        .range(0, 6)
        .sliderRange(0, 4)
        .build()
    );

    private final Setting<Integer> walkPace = sgNavigation.add(new IntSetting.Builder()
        .name("Walk Speed (%)")
        .description("Percentage of normal or sprinting movement time used while following a route. 100% is fastest.")
        .defaultValue(100)
        .range(25, 100)
        .sliderRange(25, 100)
        .build()
    );

    private final Setting<Integer> pathSearchLimit = sgNavigation.add(new IntSetting.Builder()
        .name("Path Search Limit")
        .description("Maximum real standable cells examined per route.")
        .defaultValue(20000)
        .range(1024, 100000)
        .sliderRange(4096, 50000)
        .build()
    );

    private final Setting<Integer> stuckTimeout = sgNavigation.add(new IntSetting.Builder()
        .name("Stuck Timeout")
        .description("Ticks without getting closer before the route is replanned.")
        .defaultValue(80)
        .range(20, 300)
        .sliderRange(40, 160)
        .build()
    );

    private final List<ProjectionTarget> targets = new ArrayList<>();
    private final Map<Item, List<BlockPos>> supplies = new HashMap<>();
    private final Set<BlockPos> blockedTargets = new HashSet<>();
    // The authoritative completion matrix. A cell changes to true only after
    // the real world reports the exact projected state at that coordinate.
    private final Map<BlockPos, Boolean> placementMatrix = new HashMap<>();
    private final Map<BlockPos, Integer> targetIndices = new HashMap<>();
    private final NavigableMap<Integer, ProjectionTarget> remainingTargets = new TreeMap<>();
    private final Map<BlockPos, Long> dispatchedPlacements = new HashMap<>();
    // An empty supply is remembered only briefly. This prevents running back
    // to a known-empty chest for every target while still retrying after a
    // player has had a chance to refill it.
    private final Map<Item, Long> emptySupplyUntil = new HashMap<>();
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> avoidedRouteCells = new HashSet<>();
    private final List<BlockPos> snakeWaypoints = new ArrayList<>();
    private final Set<BlockPos> rejectedSupplyStands = new HashSet<>();

    private LitematicaPrinterModule printer;
    private TaskState state = TaskState.IDLE;
    private int walkY;
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;
    private int workingBandWidth;
    private BlockPos workOrigin;
    private int targetIndex;
    private int verificationIndex;
    private int verificationPass;
    private Item supplyItem;
    private int supplyAmount;
    private int reservedSupplySlots;
    private int supplyChestIndex;
    private BlockPos supplyChest;
    private Item supplyChestLabel;
    private BlockPos supplyStand;
    private BlockPos navigationGoal;
    private ProjectionTarget workGoalTarget;
    private int pathIndex;
    private int pathAttempts;
    private int restockInventoryBefore;
    private int restockTransferAttempts;
    private long restockTransferTick = -1;
    private long supplyOpenTick = -1;
    private long supplyAimTick = -1;
    private int supplyOpenAttempts;
    private int supplyOpenSyncId = -1;
    private int snakeWaypointIndex;
    private boolean snakeReturning;
    private boolean snakeSweepCompleted;
    // The module may only clear a key state it set itself. This keeps idle
    // states, menus and chest registration from cancelling manual movement.
    private boolean ownsForwardKey;
    private double walkPaceAccumulator;
    private long tick;
    private long lastStatusMessageTick;
    private long lastProgressTick;
    private long nextActionTick;
    private long lastPlacementDispatchTick = -100;
    private long lastMovementReleaseTick = -100;
    private double lastPathDistance = Double.POSITIVE_INFINITY;
    private boolean updatingSupplyList;
    private boolean startAfterRegistration;
    private TaskState stateBeforeRegistration = TaskState.IDLE;
    private String activity = "空闲";

    public LitematicaMapArtModule() {
        super(BuilderToolboxAddon.CATEGORY, "Map Art Maker", "Builds the current Litematica layer in serpentine bands and restocks from labeled containers. It never toggles Auto Schematic Printer.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        // Existing Meteor profiles may still contain the old 4.1 value.
        // Keep map-art interactions inside the conservative server-safe cap.
        if (placeRange.get() > 3.6) placeRange.set(3.6);
        if (mc.player == null || mc.world == null) {
            disable();
            return;
        }
        if (supplies.isEmpty() && !registeredSupplies.get().isEmpty()) restoreSupplyList(registeredSupplies.get());
        if (registerSupply.get()) {
            state = TaskState.REGISTERING;
            startAfterRegistration = true;
            activity = "Select chests: right-click to add, Esc to exit";
            return;
        }
        startPrinting();
    }

    private void startPrinting() {
        printer = Modules.get().get(LitematicaPrinterModule.class);
        if (printer == null) {
            error("未找到共享放置引擎。", new Object[0]);
            disable();
            return;
        }
        if (printer.isActive()) {
            warning("自动打印原理图当前也在运行；两个开关不会联动，但同时放置会竞争热键栏和点击。", new Object[0]);
        }
        if (!readFlatProjection()) {
            disable();
            return;
        }
        if (remainingTargets.isEmpty()) {
            info("Map Art: all %d cells already match the projection.", targets.size());
            disable();
            return;
        }
        // Initialisation is explicit, but preserving a previously captured
        // mapping lets the user stop and resume a long map without rescanning.
        if (supplies.isEmpty()) {
            warning("No supply chests registered. Enable Select Supply Chests and right-click a container with a nearby sign.", new Object[0]);
            disable();
            return;
        }
        walkY = mc.player.getBlockPos().getY();
        workOrigin = mc.player.getBlockPos().toImmutable();
        state = TaskState.WORK;
        if (workMode.get() == WorkMode.ONE_ROUND_TRIP_SNAKE) buildSnakeWaypoints();
        activity = "开始蛇形打印";
        info("Map Art resumed: %d already complete, %d remaining, %d material types.",
            completedMatrixCells(), remainingTargets.size(), supplies.size());
        info("Working line spacing: %d blocks (configured: %d).", workingBandWidth, laneWidth.get());
    }

    @Override
    public void onDeactivate() {
        if (state != TaskState.IDLE && statusMessages.get()) {
            info("Map Art stopped: %s (%d/%d verified).", activity, completedMatrixCells(), targets.size());
        }
        releaseMovement();
        resetNavigation();
        if (registerSupply.get()) registerSupply.set(false);
        state = TaskState.IDLE;
        activity = "空闲";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            disable();
            return;
        }
        tick++;

        if (state == TaskState.WORK && mc.currentScreen instanceof Generic3x3ContainerScreen
            && mc.player.currentScreenHandler instanceof Generic3x3ContainerScreenHandler) {
            mc.player.closeHandledScreen();
            releaseMovement();
            activity = "已关闭误开的发射器，继续打印";
            return;
        }
        if (state != TaskState.RESTOCK && mc.currentScreen != null) {
            releaseMovement();
            activity = "界面打开，已暂停";
            sendStatusHeartbeat();
            return;
        }

        switch (state) {
            case WORK -> tickWork();
            case RESTOCK -> tickRestock();
            case VERIFY -> tickVerify();
            case WAITING_SUPPLY_RECHECK -> tickSupplyRecheckWait();
            case PAUSED -> releaseMovement();
            case REGISTERING -> tickRegistration();
            case IDLE -> { }
        }
        sendStatusHeartbeat();
    }

    private void sendStatusHeartbeat() {
        if (!statusMessages.get() || state == TaskState.IDLE || tick - lastStatusMessageTick < 200) return;
        lastStatusMessageTick = tick;
        info("Map Art active: %s (%d/%d verified).", activity, completedMatrixCells(), targets.size());
    }

    private void tickRegistration() {
        if (registerSupply.get()) {
            activity = "Select chests: right-click to add, Esc to exit";
            return;
        }
        if (startAfterRegistration) {
            startAfterRegistration = false;
            startPrinting();
        } else {
            state = stateBeforeRegistration;
            activity = "继续任务";
        }
    }

    private void onRegisterSupplyChanged(boolean enabled) {
        if (!enabled) return;
        if (!isActive()) {
            // The mouse event is delivered only while the module is active.
            // Enabling this setting therefore opens a short-lived registration
            // session even before the first print run.
            toggle();
        } else if (state != TaskState.REGISTERING) {
            // Stop only the module's previous automatic movement once. Do
            // not keep releasing this key while the player is selecting
            // chests, otherwise manual forward movement becomes impossible.
            releaseMovement();
            stateBeforeRegistration = state;
            state = TaskState.REGISTERING;
            startAfterRegistration = false;
        }
        if (mc.player != null) mc.setScreen(null);
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive() || !registerSupply.get() || mc.player == null || mc.world == null
            || event.action != KeyAction.Press) return;

        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        // The registration action must not also open the selected container.
        event.cancel();
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || !isSupplyContainer(hit.getBlockPos())) {
            warning("Right-click a labeled chest, barrel or shulker box.", new Object[0]);
            return;
        }
        registerContainer(hit.getBlockPos());
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || !registerSupply.get() || event.action != KeyAction.Press
            || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        exitRegistration();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || mc.world == null || mc.player == null) return;
        if (!registerSupply.get()) {
            Color red = new Color(255, 55, 55, 220);
            BlockPos playerPos = mc.player.getBlockPos();
            for (int x = Math.max(minX, playerPos.getX() - 24); x <= Math.min(maxX, playerPos.getX() + 24); x++) {
                for (int z = Math.max(minZ, playerPos.getZ() - 24); z <= Math.min(maxZ, playerPos.getZ() + 24); z++) {
                    BlockPos pos = new BlockPos(x, walkY, z);
                    Integer index = targetIndices.get(pos);
                    if (index == null || !remainingTargets.containsKey(index)) continue;
                    BlockState actual = mc.world.getBlockState(pos);
                    if (!actual.isAir() && !actual.equals(targets.get(index).state())) {
                        event.renderer.box(pos, null, red, ShapeMode.Lines, 0);
                    }
                }
            }
            return;
        }
        Color yellow = new Color(255, 220, 45, 255);
        Set<BlockPos> rendered = new HashSet<>();
        for (List<BlockPos> containers : supplies.values()) {
            for (BlockPos pos : containers) {
                if (rendered.add(pos)) event.renderer.box(pos, null, yellow, ShapeMode.Lines, 0);
            }
        }
    }

    private void registerContainer(BlockPos container) {
        Item item = findNearestLabelItem(container, 4);
        if (item == null) {
            warning("No usable item-frame or sign label was found near this container.", new Object[0]);
            return;
        }
        List<BlockPos> containers = supplies.computeIfAbsent(item, ignored -> new ArrayList<>());
        boolean added = !containers.contains(container);
        if (added) containers.add(container.toImmutable());
        emptySupplyUntil.remove(item);
        updateSupplyList();
        if (!added) warning("This container is already registered for that item.", new Object[0]);
        else info("Supply chest registered: %s -> %s", item.getName().getString(), container.toShortString());
    }

    private void exitRegistration() {
        registerSupply.set(false);
        mc.setScreen(GuiThemes.get().moduleScreen(this));
    }

    private Item findNearestLabelItem(BlockPos container, int radius) {
        // A frame mounted directly on the selected container is unambiguous,
        // so it takes priority even if another label happens to be nearby.
        List<ItemFrameEntity> frames = mc.world.getEntitiesByClass(
            ItemFrameEntity.class,
            new net.minecraft.util.math.Box(container).expand(radius),
            frame -> !frame.getHeldItemStack().isEmpty()
        );
        for (ItemFrameEntity frame : frames) {
            if (frame.getAttachedBlockPos().equals(container)) return frame.getHeldItemStack().getItem();
        }

        Item nearestItem = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ItemFrameEntity frame : frames) {
            double dx = frame.getX() - (container.getX() + 0.5);
            double dy = frame.getY() - (container.getY() + 0.5);
            double dz = frame.getZ() - (container.getZ() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearestDistance) {
                nearestItem = frame.getHeldItemStack().getItem();
                nearestDistance = distance;
            }
        }
        for (int y = container.getY() - radius; y <= container.getY() + radius; y++) {
            for (int z = container.getZ() - radius; z <= container.getZ() + radius; z++) {
                for (int x = container.getX() - radius; x <= container.getX() + radius; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity blockEntity = mc.world.getBlockEntity(pos);
                    if (!(blockEntity instanceof SignBlockEntity sign)) continue;
                    Item item = findItemOnSign(sign);
                    if (item == null) continue;
                    double distance = pos.getSquaredDistance(container);
                    if (distance < nearestDistance) {
                        nearestItem = item;
                        nearestDistance = distance;
                    }
                }
            }
        }
        return nearestItem;
    }

    private Item findItemOnSign(SignBlockEntity sign) {
        for (boolean front : List.of(true, false)) {
            for (net.minecraft.text.Text line : sign.getText(front).getMessages(false)) {
                Item item = findItem(line.getString().trim());
                if (item != null) return item;
            }
        }
        return null;
    }

    private Item findItem(String label) {
        if (label.isEmpty()) return null;
        Identifier id = Identifier.tryParse(label);
        if (id != null) {
            Item item = Registries.ITEM.get(id);
            if (item != null && item != Items.AIR) return item;
        }
        String normalized = normalizeItemLabel(label);
        for (Item item : Registries.ITEM) {
            Identifier itemId = Registries.ITEM.getId(item);
            if (item == Items.AIR || itemId == null) continue;
            if (normalizeItemLabel(itemId.toString()).equals(normalized)
                || normalizeItemLabel(itemId.getPath()).equals(normalized)
                || normalizeItemLabel(item.getName().getString()).equals(normalized)) return item;
        }
        return null;
    }

    private static String normalizeItemLabel(String label) {
        return label.toLowerCase(java.util.Locale.ROOT).replaceAll("[ _:-]", "");
    }

    private void updateSupplyList() {
        List<String> entries = new ArrayList<>();
        supplies.forEach((item, containers) -> {
            Identifier id = Registries.ITEM.getId(item);
            for (BlockPos pos : containers) {
                entries.add(id + " -> " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
        });
        entries.sort(String::compareTo);
        updatingSupplyList = true;
        registeredSupplies.set(entries);
        updatingSupplyList = false;
    }

    private void restoreSupplyList(List<String> entries) {
        if (updatingSupplyList) return;
        supplies.clear();
        for (String entry : entries) {
            String[] parts = entry.split(" -> ", 2);
            if (parts.length != 2) continue;
            Identifier id = Identifier.tryParse(parts[0]);
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;
            String[] coordinates = parts[1].split(",", 3);
            if (coordinates.length != 3) continue;
            try {
                BlockPos pos = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2])
                );
                supplies.computeIfAbsent(item, ignored -> new ArrayList<>()).add(pos);
            } catch (NumberFormatException ignored) {
                // Keep malformed manual entries out of the live registry.
            }
        }
    }

    private boolean readFlatProjection() {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            error("请先加载并启用 Litematica 投影。", new Object[0]);
            return false;
        }

        List<Box> boxes = new ArrayList<>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            if (placement.isEnabled()) boxes.addAll(placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values());
        }
        if (boxes.isEmpty()) {
            error("没有启用的 Litematica 投影区域。", new Object[0]);
            return false;
        }

        // A map-art workspace can have platform, water and decoration on
        // several heights. The worker intentionally uses the exact block Y
        // occupied by the player's feet, never the block below the player.
        int workY = mc.player.getBlockPos().getY();
        Set<BlockPos> positions = new HashSet<>();
        for (Box box : boxes) {
            if (box == null || box.getPos1() == null || box.getPos2() == null) continue;
            BlockPos a = box.getPos1();
            BlockPos b = box.getPos2();
            if (workY < Math.min(a.getY(), b.getY()) || workY > Math.max(a.getY(), b.getY())) continue;
            for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()); z++) {
                for (int x = Math.min(a.getX(), b.getX()); x <= Math.max(a.getX(), b.getX()); x++) {
                    BlockPos pos = new BlockPos(x, workY, z);
                    if (!schematic.getBlockState(pos).isAir()) positions.add(pos);
                }
            }
        }
        if (positions.isEmpty()) {
            error("玩家脚部高度 Y=%d 的投影层没有可打印方块。", workY);
            return false;
        }

        minX = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        minZ = Integer.MAX_VALUE;
        maxZ = Integer.MIN_VALUE;
        targets.clear();
        placementMatrix.clear();
        targetIndices.clear();
        remainingTargets.clear();
        dispatchedPlacements.clear();
        for (BlockPos pos : positions) {
            BlockState required = schematic.getBlockState(pos);
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            targets.add(new ProjectionTarget(pos.toImmutable(), required, 0));
            placementMatrix.put(pos.toImmutable(), false);
        }
        orderTargetsBySnakeBands();
        // Recover from the actual world on every activation, including a
        // full game restart. Completed cells never enter the work queue.
        for (int index = 0; index < targets.size(); index++) {
            ProjectionTarget target = targets.get(index);
            targetIndices.put(target.pos(), index);
            isSatisfied(target);
        }
        targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
        return true;
    }

    private void orderTargetsBySnakeBands() {
        // Leave vertical reach and an edge margin for clicking floor faces.
        double eyeHeight = mc.player.getEyePos().y - mc.player.getY() + 0.125;
        int lateralReach = Math.max(0, (int) Math.floor(Math.sqrt(Math.max(0,
            placeRange.get() * placeRange.get() - eyeHeight * eyeHeight)) - 0.25));
        workingBandWidth = Math.min(laneWidth.get(), Math.max(1, lateralReach * 2 + 1));
        int width = workingBandWidth;
        targets.replaceAll(target -> new ProjectionTarget(
            target.pos(), target.state(), (target.pos().getZ() - minZ) / width
        ));
        targets.sort((left, right) -> {
            int lane = Integer.compare(left.lane(), right.lane());
            if (lane != 0) return lane;
            // Traverse the entire band along X, handling its nearby rows at
            // each column. Only reverse X when changing to the next band.
            int x = (left.lane() & 1) == 0
                ? Integer.compare(left.pos().getX(), right.pos().getX())
                : Integer.compare(right.pos().getX(), left.pos().getX());
            return x != 0 ? x : Integer.compare(left.pos().getZ(), right.pos().getZ());
        });
    }

    private void tickWork() {
        checkDispatchedPlacements();
        // A queued rotation/click must finish and the server must show the
        // block before walking again. Otherwise the player can step onto the
        // carpet edge while the placement callback is still in flight.
        if (!dispatchedPlacements.isEmpty()) {
            releaseMovement();
            activity = "等待刚放置的方块确认";
            return;
        }
        if (workMode.get() == WorkMode.ONE_ROUND_TRIP_SNAKE) {
            tickSnakeWork();
            return;
        }
        // First exhaust every confirmed-unfinished cell the player can reach
        // from the current standing position. This is the fast path: walking
        // is deferred until the local placement matrix has no usable cell.
        ProjectionTarget nearby = selectReachableMatrixTarget();
        if (nearby != null) {
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return;
        }
        // A rotation click may still be completing on the next tick. After
        // that, walk during the placement cooldown if nothing nearby needs
        // the current standing position.
        if (tick - lastPlacementDispatchTick < 2) {
            releaseMovement();
            return;
        }

        Integer workGoalIndex = workGoalTarget == null ? null : targetIndices.get(workGoalTarget.pos());
        if (workGoalTarget != null && (workGoalIndex == null || !remainingTargets.containsKey(workGoalIndex)
            || blockedTargets.contains(workGoalTarget.pos()) || !hasItem(workGoalTarget.state().getBlock().asItem()))) {
            workGoalTarget = null;
            workGoalIndex = null;
        }
        // Give the rotation/click a brief moment to settle, then continue to
        // another target instead of waiting here for the full server reply.
        if (workGoalTarget != null && isPlacementPending(workGoalTarget.pos())) {
            long sentAt = dispatchedPlacements.get(workGoalTarget.pos());
            if (tick - sentAt < 2) {
                releaseMovement();
                return;
            }
            workGoalTarget = null;
        }
        WorkChoice stocked = workGoalTarget == null
            ? findStockedTarget() : new WorkChoice(Map.entry(workGoalIndex, workGoalTarget), Double.NEGATIVE_INFINITY);
        WorkChoice missing = workGoalTarget == null ? findRestockTarget() : null;
        if (missing != null && (stocked == null || missing.score() < stocked.score())) {
            if (!dispatchedPlacements.isEmpty()) {
                releaseMovement();
                activity = "等待附近放置确认后补货";
                return;
            }
            targetIndex = missing.target().getKey();
            beginRestock(missing.target().getValue());
            return;
        }
        Map.Entry<Integer, ProjectionTarget> next = stocked == null ? null : stocked.target();
        if (next == null) {
            workGoalTarget = null;
            if (!dispatchedPlacements.isEmpty()) {
                releaseMovement();
                activity = "等待已放置方块的服务器确认";
                return;
            }
            next = findNearestUnblockedTarget();
            if (next != null) {
                targetIndex = next.getKey();
                beginRestock(next.getValue());
                return;
            }
            verificationIndex = 0;
            state = TaskState.VERIFY;
            activity = "全图复查";
            return;
        }

        targetIndex = next.getKey();
        ProjectionTarget target = next.getValue();
        workGoalTarget = target;
        if (isSatisfied(target)) {
            workGoalTarget = null;
            return;
        }
        if (blockedTargets.contains(target.pos())) {
            workGoalTarget = null;
            return;
        }
        Item requiredItem = target.state().getBlock().asItem();
        if (!hasItem(requiredItem)) {
            if (isSupplyTemporarilyEmpty(requiredItem)) {
                deferCurrentTarget(requiredItem);
                return;
            }
            beginRestock(target);
            return;
        }
        // A carpet has a real (thin) collision shape. The player can overlap
        // a neighbouring target even when getBlockPos() differs, so use the
        // actual collision box and step away before placing.
        if (!isWithinPlaceRange(target) || playerIntersectsTarget(target)) {
            BlockPos stand = findPlacementStandNear(target.pos(), 4);
            if (stand == null || navigateTo(stand) == NavigationResult.UNREACHABLE) {
                blockedTargets.add(target.pos());
                workGoalTarget = null;
                warning("无法从真实地面靠近 %s；本轮跳过，最终复查会报告。", target.pos().toShortString());
                resetNavigation();
            } else {
                activity = "前往下一片可放置区域";
            }
            return;
        }

        placeTarget(target);
    }

    private void onWorkModeChanged(WorkMode mode) {
        if (!isActive() || mc.player == null || state == TaskState.IDLE) return;
        resetNavigation();
        workGoalTarget = null;
        if (mode == WorkMode.ONE_ROUND_TRIP_SNAKE) {
            buildSnakeWaypoints();
            activity = "从当前位置开始一次往返折线扫描";
        } else {
            snakeWaypoints.clear();
            snakeSweepCompleted = false;
            activity = "已切回起点向外模式";
        }
    }

    private void buildSnakeWaypoints() {
        snakeWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeReturning = false;
        snakeSweepCompleted = false;
        if (mc.player == null || targets.isEmpty()) return;
        BlockPos start = mc.player.getBlockPos();
        snakeWaypoints.add(start.toImmutable());
        int width = Math.max(1, workingBandWidth);
        List<Integer> lines = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += width) lines.add(Math.min(maxZ, z + (width - 1) / 2));
        int nearestLine = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (Math.abs(lines.get(i) - start.getZ()) < Math.abs(lines.get(nearestLine) - start.getZ())) nearestLine = i;
        }
        List<Integer> lineOrder = new ArrayList<>();
        boolean towardLow = nearestLine <= lines.size() - 1 - nearestLine;
        if (towardLow) {
            for (int i = nearestLine; i >= 0; i--) lineOrder.add(lines.get(i));
            for (int i = 1; i < lines.size(); i++) lineOrder.add(lines.get(i));
        } else {
            for (int i = nearestLine; i < lines.size(); i++) lineOrder.add(lines.get(i));
            for (int i = lines.size() - 2; i >= 0; i--) lineOrder.add(lines.get(i));
        }
        boolean leftToRight = Math.abs(start.getX() - minX) <= Math.abs(start.getX() - maxX);
        for (int z : lineOrder) {
            int firstX = leftToRight ? minX : maxX;
            int lastX = leftToRight ? maxX : minX;
            BlockPos first = findStandNear(new BlockPos(firstX, walkY, z), 2);
            BlockPos last = findStandNear(new BlockPos(lastX, walkY, z), 2);
            if (first != null) snakeWaypoints.add(first);
            if (last != null && !last.equals(first)) snakeWaypoints.add(last);
            leftToRight = !leftToRight;
        }
        // The return leg traverses the same safe line endpoints once, then
        // finishes at the position from which the player switched modes.
        for (int i = snakeWaypoints.size() - 2; i >= 0; i--) snakeWaypoints.add(snakeWaypoints.get(i));
        info("One Round-Trip Snake: %d route points from current position.", snakeWaypoints.size());
    }

    private void tickSnakeWork() {
        ProjectionTarget nearby = selectReachableMatrixTarget();
        if (nearby != null) {
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return;
        }
        if (tick - lastPlacementDispatchTick < 2) {
            releaseMovement();
            return;
        }
        if (snakeWaypointIndex >= snakeWaypoints.size()) {
            if (!dispatchedPlacements.isEmpty()) {
                releaseMovement();
                return;
            }
            snakeSweepCompleted = true;
            verificationIndex = 0;
            state = TaskState.VERIFY;
            activity = "往返扫描结束，复查未完成格";
            return;
        }
        // The route is a physical sweep, not a sequence of target clicks.
        // Missing supplies are fetched before continuing the current leg.
        WorkChoice stocked = findStockedTarget();
        if (stocked == null) {
            WorkChoice missing = findRestockTarget();
            if (missing != null) {
                beginRestock(missing.target().getValue());
                return;
            }
        }
        BlockPos waypoint = snakeWaypoints.get(snakeWaypointIndex);
        NavigationResult result = navigateTo(waypoint);
        if (result == NavigationResult.ARRIVED || result == NavigationResult.UNREACHABLE) {
            if (result == NavigationResult.UNREACHABLE) resetNavigation();
            snakeWaypointIndex++;
            if (!snakeReturning && snakeWaypointIndex >= (snakeWaypoints.size() + 1) / 2) snakeReturning = true;
        }
        activity = snakeReturning ? "折线扫描：返回起点" : "折线扫描：前往远端";
    }

    /** Pick a nearest unfinished matrix cell already in interaction range. */
    private ProjectionTarget selectReachableMatrixTarget() {
        ProjectionTarget best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        double rangeSquared = placeRange.get() * placeRange.get();
        Vec3d eye = mc.player.getEyePos();
        // Look up only coordinates near the player, not the whole blueprint.
        int radius = (int) Math.ceil(placeRange.get());
        BlockPos feet = mc.player.getBlockPos();
        for (int x = feet.getX() - radius; x <= feet.getX() + radius; x++) {
            for (int z = feet.getZ() - radius; z <= feet.getZ() + radius; z++) {
                Integer index = targetIndices.get(new BlockPos(x, walkY, z));
                if (index == null) continue;
                ProjectionTarget candidate = remainingTargets.get(index);
                if (candidate == null) continue;
                if (blockedTargets.contains(candidate.pos()) || playerIntersectsTarget(candidate)) continue;
                if (eye.squaredDistanceTo(placementAim(candidate)) > rangeSquared) continue;
                if (isPlacementPending(candidate.pos()) || isSatisfied(candidate)) continue;
                if (!hasItem(candidate.state().getBlock().asItem())) continue;

                double distance = eye.squaredDistanceTo(Vec3d.ofCenter(candidate.pos()));
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isPlacementPending(BlockPos pos) {
        Long attemptedAt = dispatchedPlacements.get(pos);
        return attemptedAt != null && tick - attemptedAt < PLACEMENT_CONFIRM_TICKS;
    }

    private void placeTarget(ProjectionTarget target) {
        releaseMovement();
        if (tick < nextActionTick || isPlacementPending(target.pos())) return;
        // Releasing the key is not an instantaneous stop: the player retains
        // horizontal velocity for several ticks, especially on thin carpets.
        // Let that motion settle, then re-evaluate collision and reach from
        // the actual position rather than the position that selected target.
        Vec3d velocity = mc.player.getVelocity();
        double horizontalSpeedSquared = velocity.x * velocity.x + velocity.z * velocity.z;
        if (tick - lastMovementReleaseTick < 2 || horizontalSpeedSquared > 0.025 * 0.025
            || !mc.player.isOnGround()) {
            activity = "稳定站位后放置";
            return;
        }
        if (!isWithinPlaceRange(target) || playerIntersectsTarget(target)) {
            workGoalTarget = null;
            return;
        }

        LitematicaPrinterModule.ExternalPlacementResult result = printer.placeProjectionBlock(
            target.pos(), target.state(), placeRange.get()
        );
        nextActionTick = tick + Math.max(1, (int) Math.ceil(20.0 / placementSpeed.get()));
        switch (result) {
            case COMPLETE -> {
                isSatisfied(target);
            }
            case DISPATCHED -> {
                dispatchedPlacements.put(target.pos(), tick);
                lastPlacementDispatchTick = tick;
                resetNavigation();
                activity = "放置矩阵：" + completedMatrixCells() + "/" + targets.size();
            }
            case MISSING_ITEM -> beginRestock(target);
            case BLOCKED -> markPlacementBlocked(target);
        }
    }

    private void checkDispatchedPlacements() {
        for (Map.Entry<BlockPos, Long> entry : new ArrayList<>(dispatchedPlacements.entrySet())) {
            Integer index = targetIndices.get(entry.getKey());
            if (index == null) {
                dispatchedPlacements.remove(entry.getKey());
                continue;
            }
            ProjectionTarget target = targets.get(index);
            if (isSatisfied(target)) continue;
            if (tick - entry.getValue() < PLACEMENT_CONFIRM_TICKS) continue;
            dispatchedPlacements.remove(entry.getKey());
            markPlacementBlocked(target);
        }
    }

    private void markPlacementBlocked(ProjectionTarget target) {
        if (!blockedTargets.add(target.pos())) return;
        if (workGoalTarget != null && workGoalTarget.pos().equals(target.pos())) workGoalTarget = null;
        activity = "已跳过无法放置的位置：" + blockedTargets.size() + " 格";
        if (blockedTargets.size() <= 3 || blockedTargets.size() % 32 == 0) {
            warning("无法放置 %s；已跳过，不再重复点击（累计 %d 格）。", target.pos().toShortString(), blockedTargets.size());
        }
    }

    private void beginRestock(ProjectionTarget current) {
        resetNavigation();
        if (!selectNextSupply(current.state().getBlock().asItem())) {
            if (findStockedTarget() != null) {
                finishRestock();
                return;
            }
            Item requiredItem = current.state().getBlock().asItem();
            if (hasPotentialWorkFrom(targetIndex + 1)) {
                deferCurrentTarget(requiredItem);
                return;
            }
            if (hasPendingSupplyRecheck()) {
                beginSupplyRecheckWait();
                return;
            }
            activity = "暂停：所有剩余颜色均无材料";
            error("No usable supply or inventory space. Free a slot or register/refill the required materials.", new Object[0]);
            state = TaskState.PAUSED;
            return;
        }
        state = TaskState.RESTOCK;
        activity = "前往补给箱";
    }

    private WorkChoice findStockedTarget() {
        Set<Item> heldItems = inventoryItems();
        Map<Long, WorkRegion> regions = new HashMap<>();
        for (Map.Entry<Integer, ProjectionTarget> entry : remainingTargets.entrySet()) {
            ProjectionTarget candidate = entry.getValue();
            if (blockedTargets.contains(candidate.pos()) || isPlacementPending(candidate.pos())
                || !heldItems.contains(candidate.state().getBlock().asItem())) continue;
            regions.computeIfAbsent(workRegionKey(candidate.pos()), ignored -> new WorkRegion())
                .add(entry, workPriority(candidate.pos()));
        }
        WorkChoice best = null;
        for (WorkRegion region : regions.values()) {
            double score = region.score();
            if (best == null || score < best.score()) best = new WorkChoice(region.bestTarget, score);
        }
        return best;
    }

    private WorkChoice findRestockTarget() {
        if (emptyInventorySlots() == 0) return null;
        Set<Item> heldItems = inventoryItems();
        Set<Item> restockable = new HashSet<>();
        for (Item item : supplies.keySet()) {
            if (!heldItems.contains(item) && hasUsableSupply(item)) restockable.add(item);
        }
        Map<Item, Map<Long, WorkRegion>> regionsByItem = new HashMap<>();
        for (Map.Entry<Integer, ProjectionTarget> entry : remainingTargets.entrySet()) {
            ProjectionTarget target = entry.getValue();
            if (blockedTargets.contains(target.pos()) || isPlacementPending(target.pos())) continue;
            Item item = target.state().getBlock().asItem();
            if (!restockable.contains(item)) continue;
            regionsByItem.computeIfAbsent(item, ignored -> new HashMap<>())
                .computeIfAbsent(workRegionKey(target.pos()), ignored -> new WorkRegion())
                .add(entry, workPriority(target.pos()));
        }
        WorkChoice best = null;
        for (Map.Entry<Item, Map<Long, WorkRegion>> material : regionsByItem.entrySet()) {
            double chestDistance = supplies.get(material.getKey()).stream()
                .mapToDouble(pos -> Math.sqrt(distanceToPlayer(pos))).min().orElse(Double.POSITIVE_INFINITY);
            for (WorkRegion region : material.getValue().values()) {
                // Restocking is useful for a nearby cluster, but a long chest
                // trip has a continuous cost rather than a hard cutoff.
                double score = region.score() + 4.0 + chestDistance * 0.35;
                if (best == null || score < best.score()) best = new WorkChoice(region.bestTarget, score);
            }
        }
        return best;
    }

    private long workRegionKey(BlockPos pos) {
        int regionX = Math.floorDiv(pos.getX() - minX, WORK_REGION_SIZE);
        int regionZ = Math.floorDiv(pos.getZ() - minZ, WORK_REGION_SIZE);
        return ((long) regionX << 32) | (regionZ & 0xffffffffL);
    }

    private double workPriority(BlockPos pos) {
        BlockPos origin = workOrigin == null ? mc.player.getBlockPos() : workOrigin;
        double dx = pos.getX() - origin.getX();
        double dz = pos.getZ() - origin.getZ();
        double originDistance = Math.sqrt(dx * dx + dz * dz);
        return originDistance * 0.7 + Math.sqrt(distanceToPlayer(pos)) * 0.3;
    }

    private Map.Entry<Integer, ProjectionTarget> findNearestUnblockedTarget() {
        Map.Entry<Integer, ProjectionTarget> best = null;
        double bestPriority = Double.POSITIVE_INFINITY;
        for (Map.Entry<Integer, ProjectionTarget> entry : remainingTargets.entrySet()) {
            if (blockedTargets.contains(entry.getValue().pos())) continue;
            double priority = workPriority(entry.getValue().pos());
            if (priority < bestPriority) {
                best = entry;
                bestPriority = priority;
            }
        }
        return best;
    }

    private boolean selectNextSupply(Item preferred) {
        Map<Item, Integer> remaining = countRemainingMaterials();
        Map<Item, Integer> missing = new HashMap<>(remaining);
        missing.replaceAll((item, amount) -> Math.max(0, amount - countInventory(item)));
        Set<Item> heldItems = inventoryItems();
        int usableSlots = usableMaterialSlots(remaining.keySet());
        long totalDemand = remaining.values().stream().mapToLong(Integer::longValue).sum();

        // First give every still-needed color at least one dedicated stack.
        // Otherwise a common color can consume every free slot before a rare
        // color's chest is visited. An impossible reservation (more colors
        // than free slots) is relaxed one slot at a time so work can continue.
        Item selected = null;
        int selectedScore = Integer.MIN_VALUE;
        for (Map.Entry<Item, Integer> entry : remaining.entrySet()) {
            Item item = entry.getKey();
            if (heldItems.contains(item) || !hasUsableSupply(item)) continue;
            int reserved = reservedSlotsForOtherColors(item, remaining.keySet(), heldItems);
            if (inventoryCapacity(item, reserved) <= 0) continue;
            int score = entry.getValue() + (item == preferred ? targets.size() + 1 : 0);
            if (score > selectedScore) {
                selected = item;
                selectedScore = score;
            }
        }
        if (selected == null) {
            // Spread the remaining usable slots roughly in proportion to the
            // unfinished projection. A single frequent color must not take
            // every slot while other colors still have large work areas.
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                Item item = entry.getKey();
                if (entry.getValue() <= 0 || !hasUsableSupply(item)) continue;
                int reserved = reservedSlotsForOtherColors(item, remaining.keySet(), heldItems);
                if (inventoryCapacity(item, reserved) <= 0) continue;
                int desired = desiredInventoryCount(item, remaining, usableSlots, totalDemand);
                int deficit = Math.min(entry.getValue(), Math.max(0, desired - countInventory(item)));
                if (deficit <= 0) continue;
                int score = deficit + (item == preferred ? targets.size() + 1 : 0);
                if (score > selectedScore) {
                    selected = item;
                    selectedScore = score;
                }
            }
        }
        boolean fillingReserve = selected == null;
        if (fillingReserve) {
            selectedScore = Integer.MIN_VALUE;
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                Item item = entry.getKey();
                if (entry.getValue() <= 0 || !hasUsableSupply(item)) continue;
                int reserved = reservedSlotsForOtherColors(item, remaining.keySet(), heldItems);
                if (inventoryCapacity(item, reserved) <= 0) continue;
                if (entry.getValue() > selectedScore) {
                    selected = item;
                    selectedScore = entry.getValue();
                }
            }
        }
        if (selected == null) return false;
        supplyItem = selected;
        reservedSupplySlots = reservedSlotsForOtherColors(selected, remaining.keySet(), heldItems);
        int capacity = inventoryCapacity(selected, reservedSupplySlots);
        // While visiting a chest, take this color's full proportional quota
        // in one trip. The reserved-slot cap still protects every other color.
        int desired = desiredInventoryCount(selected, remaining, usableSlots, totalDemand);
        int usefulAmount = Math.min(capacity, missing.get(selected));
        supplyAmount = fillingReserve ? usefulAmount
            : Math.min(usefulAmount, Math.max(0, desired - countInventory(selected)));
        supplyChestIndex = 0;
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        return true;
    }

    private boolean hasUsableSupply(Item item) {
        return !isSupplyTemporarilyEmpty(item) && !supplies.getOrDefault(item, List.of()).isEmpty();
    }

    private int reservedSlotsForOtherColors(Item selected, Set<Item> needed, Set<Item> heldItems) {
        int uncovered = 0;
        for (Item item : needed) {
            // Keep the slot even while an empty registered chest is in its
            // 60-second cooldown; that color may be restocked shortly.
            if (item != selected && !heldItems.contains(item)
                && !supplies.getOrDefault(item, List.of()).isEmpty()) uncovered++;
        }
        int empty = emptyInventorySlots();
        return Math.min(uncovered, heldItems.contains(selected) ? empty : Math.max(0, empty - 1));
    }

    private int usableMaterialSlots(Set<Item> needed) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || needed.contains(stack.getItem())) count++;
        }
        return count;
    }

    private int desiredInventoryCount(Item item, Map<Item, Integer> remaining, int usableSlots, long totalDemand) {
        if (totalDemand <= 0) return 0;
        int stackSize = new ItemStack(item).getMaxCount();
        int desiredStacks = Math.max(1, (int) Math.floor((double) usableSlots * remaining.get(item) / totalDemand));
        return desiredStacks * stackSize;
    }

    private void finishRestock() {
        closeSupplyScreen();
        resetRestockTransfer();
        resetNavigation();
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyChest = null;
        workGoalTarget = null;
        state = TaskState.WORK;
        activity = "补货结束，优先铺设背包已有颜色";
    }

    private void continueRestock() {
        closeSupplyScreen();
        resetRestockTransfer();
        resetNavigation();
        if (selectNextSupply(null)) {
            activity = "继续批量补充其它所需颜色";
        } else {
            finishRestock();
        }
    }

    private void deferCurrentTarget(Item item) {
        targetIndex++;
        activity = "暂缓缺料颜色 " + item.getName().getString() + "，继续其它颜色";
    }

    /** True when at least one unfinished future target can still be built or supplied. */
    private boolean hasPotentialWorkFrom(int startIndex) {
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.tailMap(Math.max(0, startIndex), true).values())) {
            if (isSatisfied(target) || blockedTargets.contains(target.pos())) continue;
            Item item = target.state().getBlock().asItem();
            if (hasItem(item)) return true;
            if (!isSupplyTemporarilyEmpty(item) && !supplies.getOrDefault(item, List.of()).isEmpty()) return true;
        }
        return false;
    }

    private void markSupplyTemporarilyEmpty(Item item) {
        emptySupplyUntil.put(item, tick + EMPTY_SUPPLY_RECHECK_TICKS);
    }

    private boolean isSupplyTemporarilyEmpty(Item item) {
        Long until = emptySupplyUntil.get(item);
        if (until == null) return false;
        if (tick < until) return true;
        emptySupplyUntil.remove(item);
        return false;
    }

    private boolean hasPendingSupplyRecheck() {
        emptySupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        return !emptySupplyUntil.isEmpty();
    }

    private void beginSupplyRecheckWait() {
        releaseMovement();
        state = TaskState.WAITING_SUPPLY_RECHECK;
        activity = "等待材料箱补货后复查";
    }

    private void tickSupplyRecheckWait() {
        if (hasPendingSupplyRecheck()) {
            long nextCheck = emptySupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(tick);
            long seconds = Math.max(1, (nextCheck - tick + 19) / 20);
            activity = "等待空材料箱复查：约 " + seconds + " 秒";
            return;
        }
        targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
        verificationIndex = 0;
        state = TaskState.WORK;
        activity = "空材料标记已过期，重新检查补货箱";
        info("Map Art: empty supply markers expired; retrying remaining colors.");
    }

    private Map<Item, Integer> countRemainingMaterials() {
        Map<Item, Integer> result = new HashMap<>();
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            if (!blockedTargets.contains(target.pos()) && !isSatisfied(target)) {
                result.merge(target.state().getBlock().asItem(), 1, Integer::sum);
            }
        }
        return result;
    }

    private void tickRestock() {
        if (supplyItem == null || supplyAmount <= 0) {
            if (supplyItem != null) emptySupplyUntil.remove(supplyItem);
            continueRestock();
            return;
        }

        List<BlockPos> options = supplies.getOrDefault(supplyItem, List.of());
        if (supplyChest == null) {
            if (supplyChestIndex >= options.size()) {
                markSupplyTemporarilyEmpty(supplyItem);
                activity = "材料箱已空，60 秒后复查；继续其它颜色";
                warning("所有登记的 %s 补给箱都为空；该颜色将在 60 秒后自动重新检查，当前先绘制其它颜色。", supplyItem.getName().getString());
                continueRestock();
                return;
            }
            supplyChest = options.get(supplyChestIndex++);
            supplyChestLabel = findNearestLabelItem(supplyChest, 4);
            Identifier wantedId = Registries.ITEM.getId(supplyItem);
            Identifier labelId = supplyChestLabel == null ? null : Registries.ITEM.getId(supplyChestLabel);
            if (labelId == null || !labelId.equals(wantedId)) {
                warning("补给箱 %s 的标签物品 ID 与所需 %s 不一致，已跳过。", supplyChest.toShortString(), wantedId);
                supplyChest = null;
                supplyChestLabel = null;
                return;
            }
            supplyStand = null;
            rejectedSupplyStands.clear();
            supplyOpenTick = -1;
            supplyAimTick = -1;
            supplyOpenAttempts = 0;
        }

        GenericContainerScreenHandler opened = mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
            ? handler : null;
        if (opened != null && (supplyOpenTick < 0 || opened.syncId == supplyOpenSyncId)) {
            // Never transfer from a menu opened before this chest click.
            mc.player.closeHandledScreen();
            opened = null;
        }
        if (opened == null && mc.currentScreen != null) {
            if (mc.currentScreen instanceof Generic3x3ContainerScreen) mc.player.closeHandledScreen();
            else activity = "补货暂停：请关闭当前界面";
            releaseMovement();
            return;
        }
        if (opened == null && supplyOpenTick >= 0 && tick - supplyOpenTick < 12) {
            releaseMovement();
            activity = "等待目标箱子打开";
            return;
        }

        BlockHitResult chestHit = findContainerHit(supplyChest, mc.player.getEyePos());
        if (chestHit == null && opened == null && supplyStand == null) {
            supplyStand = findSupplyStandNear(supplyChest, 4);
        }
        BlockPos stand = opened != null || chestHit != null ? mc.player.getBlockPos() : supplyStand;
        if (stand == null) {
            warning("无法走到补给箱 %s，尝试同材料的下一只箱子。", supplyChest.toShortString());
            supplyChest = null;
            supplyOpenTick = -1;
            resetNavigation();
            return;
        }
        if (opened == null && chestHit == null) {
            NavigationResult route = navigateTo(stand);
            if (route == NavigationResult.UNREACHABLE || route == NavigationResult.ARRIVED) {
                rejectedSupplyStands.add(stand);
                supplyStand = null;
                resetNavigation();
                activity = "换一个角度靠近补给箱";
                return;
            }
            activity = "前往补给箱";
            return;
        }

        // Stop walking before rotating/clicking. A leftover forward key was
        // moving the player past the chest between aim and interaction.
        if (navigationGoal != null) resetNavigation();
        else releaseMovement();

        if (opened == null) {
            if (supplyOpenTick >= 0 && ++supplyOpenAttempts >= 3) {
                warning("补给箱 %s 未能确认打开，尝试下一只。", supplyChest.toShortString());
                supplyChest = null;
                supplyOpenTick = -1;
                resetNavigation();
                return;
            }
            if (tick < nextActionTick) return;
            if (supplyAimTick < 0 || tick - supplyAimTick > 2) {
                faceToward(chestHit.getPos());
                supplyAimTick = tick;
                activity = "正对补给箱";
                return;
            }
            faceToward(chestHit.getPos());
            supplyOpenSyncId = mc.player.currentScreenHandler.syncId;
            BlockUtils.interact(chestHit, Hand.MAIN_HAND, true);
            supplyOpenTick = tick;
            supplyAimTick = -1;
            nextActionTick = tick + 4;
            activity = "等待补给箱界面确认";
            return;
        }

        // A container click is confirmed by the server a few ticks later. Do
        // not issue another one until that update arrives; otherwise lag can
        // make the client repeatedly click stale slots forever.
        if (restockTransferTick >= 0) {
            int moved = countInventory(supplyItem) - restockInventoryBefore;
            if (moved > 0) {
                supplyAmount -= moved;
                resetRestockTransfer();
                nextActionTick = tick + 3;
                activity = "已取到 " + moved + " 个 " + supplyItem.getName().getString();
                return;
            }
            if (tick - restockTransferTick < 10) {
                activity = "等待补货确认";
                return;
            }
            if (++restockTransferAttempts >= 3) {
                warning("补给箱 %s 的物品移动未被服务器确认，改用下一只箱子。", supplyChest.toShortString());
                closeSupplyScreen();
                supplyChest = null;
                resetRestockTransfer();
                resetNavigation();
                return;
            }
            restockTransferTick = -1;
            nextActionTick = tick + 2;
            return;
        }
        int safeCapacity = inventoryCapacity(supplyItem, reservedSupplySlots);
        if (safeCapacity <= 0) {
            continueRestock();
            return;
        }
        if (tick < nextActionTick) return;
        Item currentLabel = findNearestLabelItem(supplyChest, 4);
        Identifier labelId = currentLabel == null ? null : Registries.ITEM.getId(currentLabel);
        Identifier registeredId = Registries.ITEM.getId(supplyChestLabel);
        Identifier requiredId = Registries.ITEM.getId(supplyItem);
        if (labelId == null || !labelId.equals(registeredId) || !labelId.equals(requiredId)) {
            // A label may have changed after the chest was selected. Never
            // shift-click by display name, color family or item tags.
            warning("补给箱 %s 的标签 ID 已变化，跳过该箱以免拿错颜色。", supplyChest.toShortString());
            closeSupplyScreen();
            supplyChest = null;
            supplyChestLabel = null;
            resetNavigation();
            return;
        }
        int slots = opened.getRows() * 9;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            // Shift-click moves the entire source stack. Never let that one
            // click use a slot reserved for another unfinished color.
            if (!stack.isEmpty() && labelId.equals(Registries.ITEM.getId(stack.getItem()))
                && stack.getCount() <= safeCapacity) {
                restockInventoryBefore = countInventory(supplyItem);
                // slot() converts a player-inventory index to a slot id. A
                // chest already exposes raw handler ids (0..rows*9), so that
                // conversion was clicking the wrong inventory slot. slotId()
                // targets the actual container stack.
                InvUtils.shiftClick().slotId(slot);
                restockTransferTick = tick;
                nextActionTick = tick + 1;
                activity = "从补给箱取 " + supplyItem.getName().getString();
                return;
            }
        }
        closeSupplyScreen();
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        activity = "当前补给箱为空，切换备用箱";
    }

    private void resetRestockTransfer() {
        restockInventoryBefore = 0;
        restockTransferAttempts = 0;
        restockTransferTick = -1;
    }

    private void tickVerify() {
        int budget = 512;
        while (budget-- > 0 && verificationIndex < targets.size()) {
            ProjectionTarget target = targets.get(verificationIndex++);
            isSatisfied(target);
        }
        if (verificationIndex < targets.size()) return;
        int incomplete = targets.size() - completedMatrixCells();
        if (incomplete == 0) {
            info("自动地图画完成：%d 个投影方块已复查。", targets.size());
            disable();
            return;
        }
        if (remainingTargets.values().stream().allMatch(target -> blockedTargets.contains(target.pos()))) {
            activity = "暂停：" + incomplete + " 格无法放置";
            warning("仍有 %d 格无法放置；已跳过失败位置，不再反复尝试。", incomplete);
            state = TaskState.PAUSED;
            return;
        }
        if (!hasPotentialWorkFrom(0)) {
            if (hasPendingSupplyRecheck()) {
                beginSupplyRecheckWait();
                return;
            }
            activity = "暂停：剩余颜色均无材料";
            warning("其余 %d 格均缺少材料；所有能供应的颜色已完成，地图画已安全暂停。", incomplete);
            state = TaskState.PAUSED;
            return;
        }
        if (snakeSweepCompleted || verificationPass++ >= 1) {
            activity = "暂停：仍有 " + incomplete + " 格未完成";
            warning("全图复查后仍有 %d 格未完成，已停止以避免无限循环。", incomplete);
            state = TaskState.PAUSED;
            return;
        }
        targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
        state = TaskState.WORK;
        activity = "复查发现遗漏，开始补齐";
    }

    private boolean isSatisfied(ProjectionTarget target) {
        boolean satisfied = mc.world.getBlockState(target.pos()).equals(target.state());
        placementMatrix.put(target.pos(), satisfied);
        Integer index = targetIndices.get(target.pos());
        if (index != null) {
            if (satisfied) remainingTargets.remove(index);
            else remainingTargets.put(index, target);
        }
        if (satisfied) {
            if (workGoalTarget != null && workGoalTarget.pos().equals(target.pos())) workGoalTarget = null;
            dispatchedPlacements.remove(target.pos());
            blockedTargets.remove(target.pos());
        }
        return satisfied;
    }

    private int completedMatrixCells() {
        return targets.size() - remainingTargets.size();
    }

    private Vec3d placementAim(ProjectionTarget target) {
        return target.state().getBlock() instanceof CarpetBlock
            ? Vec3d.ofCenter(target.pos()).add(0, -0.5, 0) : Vec3d.ofCenter(target.pos());
    }

    private boolean isWithinPlaceRange(ProjectionTarget target) {
        return mc.player.getEyePos().squaredDistanceTo(placementAim(target)) <= placeRange.get() * placeRange.get();
    }

    private boolean playerIntersectsTarget(ProjectionTarget target) {
        BlockPos pos = target.pos();
        VoxelShape collision = target.state().getCollisionShape(mc.world, pos);
        if (collision.isEmpty()) return false;
        net.minecraft.util.math.Box local = collision.getBoundingBox();
        net.minecraft.util.math.Box worldBox = new net.minecraft.util.math.Box(
            pos.getX() + local.minX, pos.getY() + local.minY, pos.getZ() + local.minZ,
            pos.getX() + local.maxX, pos.getY() + local.maxY, pos.getZ() + local.maxZ
        );
        return mc.player.getBoundingBox().intersects(worldBox);
    }

    private boolean hasItem(Item item) {
        return countInventory(item) > 0;
    }

    private Set<Item> inventoryItems() {
        Set<Item> result = new HashSet<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) result.add(stack.getItem());
        }
        return result;
    }

    private int emptyInventorySlots() {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) count++;
        }
        return count;
    }

    private int countInventory(Item item) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int inventoryCapacity(Item item, int reservedSlots) {
        int capacity = 0;
        int emptySlots = 0;
        int maxStack = new ItemStack(item).getMaxCount();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) emptySlots++;
            else if (stack.isOf(item)) capacity += Math.max(0, stack.getMaxCount() - stack.getCount());
        }
        return capacity + Math.max(0, emptySlots - reservedSlots) * maxStack;
    }

    private boolean isSupplyContainer(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock
            || state.getBlock() instanceof BarrelBlock
            || state.getBlock() instanceof ShulkerBoxBlock;
    }

    private BlockHitResult findContainerHit(BlockPos pos, Vec3d eye) {
        if (!isSupplyContainer(pos)) return null;
        for (Direction face : Direction.values()) {
            Vec3d hitPoint = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.49, face.getOffsetY() * 0.49, face.getOffsetZ() * 0.49
            );
            if (eye.squaredDistanceTo(hitPoint) > 4.0 * 4.0) continue;
            HitResult trace = mc.world.raycast(new RaycastContext(
                eye, hitPoint, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
            ));
            if (trace instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(pos)) return blockHit;
        }
        return null;
    }

    private BlockPos findSupplyStandNear(BlockPos chest, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = chest.getX() - radius; x <= chest.getX() + radius; x++) {
            for (int z = chest.getZ() - radius; z <= chest.getZ() + radius; z++) {
                BlockPos stand = new BlockPos(x, walkY, z);
                if (!isStandUsable(stand) || rejectedSupplyStands.contains(stand)) continue;
                Vec3d eye = new Vec3d(x + 0.5, walkY + 1.62, z + 0.5);
                if (findContainerHit(chest, eye) != null) candidates.add(stand);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
    }

    private void closeSupplyScreen() {
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) mc.player.closeHandledScreen();
    }

    // This is the same safe ground-only BFS used by the patrol feature. It
    // intentionally does not consider Litematica ghost blocks walkable.
    private NavigationResult navigateTo(BlockPos goal) {
        if (hasReached(goal)) {
            releaseMovement();
            navigationGoal = null;
            return NavigationResult.ARRIVED;
        }
        if (!isStandUsable(goal)) return NavigationResult.UNREACHABLE;
        boolean differentGoal = navigationGoal == null || !navigationGoal.equals(goal);
        BlockPos pendingStep = pathIndex < path.size() ? path.get(pathIndex) : null;
        boolean carpetTransition = pendingStep != null
            && (mc.world.getBlockState(pendingStep).getBlock() instanceof CarpetBlock
                || mc.world.getBlockState(mc.player.getBlockPos()).getBlock() instanceof CarpetBlock);
        int timeout = carpetTransition ? Math.min(stuckTimeout.get(), 40) : stuckTimeout.get();
        boolean stuck = !path.isEmpty() && tick - lastProgressTick >= timeout;
        if (differentGoal || path.isEmpty() || stuck) {
            if (differentGoal) {
                resetNavigation();
                navigationGoal = goal.toImmutable();
            } else if (stuck) {
                // A tiny oscillation at a carpet lip is not route progress.
                // Avoid this exact step on the next BFS instead of planning
                // the same blocked edge forever.
                releaseMovement();
                if (pendingStep != null) avoidedRouteCells.add(pendingStep);
            }
            if (pathAttempts > routeRetries.get()) return NavigationResult.UNREACHABLE;
            pathAttempts++;
            if (!planPath(goal)) return NavigationResult.UNREACHABLE;
        }
        while (pathIndex < path.size() && hasReached(path.get(pathIndex))) {
            pathIndex++;
            lastProgressTick = tick;
            lastPathDistance = Double.POSITIVE_INFINITY;
        }
        if (pathIndex >= path.size()) return hasReached(goal) ? NavigationResult.ARRIVED : NavigationResult.MOVING;
        BlockPos next = path.get(pathIndex);
        if (!isStandUsable(next)) {
            path.clear();
            pathIndex = 0;
            return NavigationResult.MOVING;
        }
        double distance = Math.sqrt(horizontalDistanceSquared(next));
        if (distance + 0.04 < lastPathDistance) {
            lastPathDistance = distance;
            lastProgressTick = tick;
        }
        faceToward(next);
        pressForward();
        return NavigationResult.MOVING;
    }

    private boolean planPath(BlockPos goal) {
        path.clear();
        pathIndex = 0;
        lastProgressTick = tick;
        lastPathDistance = Double.POSITIVE_INFINITY;
        BlockPos start = mc.player.getBlockPos().toImmutable();
        int margin = 4;
        int minPathX = Math.min(Math.min(minX, start.getX()), goal.getX()) - margin;
        int maxPathX = Math.max(Math.max(maxX, start.getX()), goal.getX()) + margin;
        int minPathZ = Math.min(Math.min(minZ, start.getZ()), goal.getZ()) - margin;
        int maxPathZ = Math.max(Math.max(maxZ, start.getZ()), goal.getZ()) + margin;
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        open.add(start);
        parents.put(start, null);
        int searched = 0;
        while (!open.isEmpty() && searched++ < pathSearchLimit.get()) {
            BlockPos current = open.removeFirst();
            if (current.equals(goal)) {
                ArrayDeque<BlockPos> reverse = new ArrayDeque<>();
                for (BlockPos cursor = goal; cursor != null; cursor = parents.get(cursor)) reverse.addFirst(cursor);
                path.addAll(reverse);
                pathIndex = path.size() > 1 ? 1 : 0;
                return true;
            }
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                BlockPos next = current.offset(direction).toImmutable();
                if (next.getX() < minPathX || next.getX() > maxPathX || next.getZ() < minPathZ || next.getZ() > maxPathZ
                    || next.getY() != walkY || parents.containsKey(next) || avoidedRouteCells.contains(next)
                    || !isStandUsable(next) || Math.abs(standSurfaceY(next) - standSurfaceY(current)) > 0.6) continue;
                parents.put(next, current);
                open.addLast(next);
            }
        }
        return false;
    }

    private BlockPos findStandNear(BlockPos focus, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = focus.getX() - radius; x <= focus.getX() + radius; x++) {
            for (int z = focus.getZ() - radius; z <= focus.getZ() + radius; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (isStandUsable(candidate)) candidates.add(candidate);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(focus))).orElse(null);
    }

    /** Select a reachable working position, never the block that will be placed. */
    private BlockPos findPlacementStandNear(BlockPos target, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        int band = (target.getZ() - minZ) / workingBandWidth;
        int bandStart = minZ + band * workingBandWidth;
        int bandEnd = Math.min(maxZ, bandStart + workingBandWidth - 1);
        int centerZ = (bandStart + bandEnd) >> 1;
        int direction = (band & 1) == 0 ? 1 : -1;
        Integer index = targetIndices.get(target);
        Vec3d aim = index == null ? Vec3d.ofCenter(target) : placementAim(targets.get(index));
        for (int x = target.getX() - radius; x <= target.getX() + radius; x++) {
            for (int z = target.getZ() - radius; z <= target.getZ() + radius; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (candidate.equals(target) || !isStandUsable(candidate)) continue;
                double feetY = standSurfaceY(candidate);
                Vec3d eye = new Vec3d(x + 0.5, feetY + 1.62, z + 0.5);
                if (eye.squaredDistanceTo(aim) <= placeRange.get() * placeRange.get()) candidates.add(candidate);
            }
        }
        // Stay on the band's middle working line and advance along X. A
        // blocked middle line can fall back to another real walkable cell.
        return candidates.stream().min(Comparator
            .comparingInt((BlockPos pos) -> Math.abs(pos.getZ() - centerZ))
            .thenComparingInt(pos -> Math.abs(pos.getX() - (target.getX() + direction)))
            .thenComparingDouble(this::distanceToPlayer)
        ).orElse(null);
    }

    private boolean isStandUsable(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        // A carpet occupies only the feet block and is safe to walk on. Treat
        // it as a valid route cell so a completed map does not become a wall.
        boolean feetPassable = feet.getCollisionShape(mc.world, pos).isEmpty()
            || feet.getBlock() instanceof CarpetBlock;
        return feetPassable
            && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty()
            && !mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down()).isEmpty();
    }

    private boolean hasReached(BlockPos pos) {
        return mc.player.isOnGround() && horizontalDistanceSquared(pos) <= 0.20 * 0.20
            && Math.abs(standSurfaceY(pos) - mc.player.getY()) <= 0.18;
    }

    private double standSurfaceY(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        if (feet.getBlock() instanceof CarpetBlock) {
            return pos.getY() + feet.getCollisionShape(mc.world, pos).getBoundingBox().maxY;
        }
        BlockPos below = pos.down();
        VoxelShape support = mc.world.getBlockState(below).getCollisionShape(mc.world, below);
        return support.isEmpty() ? pos.getY() : below.getY() + support.getBoundingBox().maxY;
    }

    private double horizontalDistanceSquared(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    private double distanceToPlayer(BlockPos pos) {
        double dx = mc.player.getX() - (pos.getX() + 0.5);
        double dy = mc.player.getY() - (pos.getY() + 0.5);
        double dz = mc.player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private void faceToward(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        mc.player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI - 90.0));
    }

    private void faceToward(Vec3d pos) {
        Vec3d eye = mc.player.getEyePos();
        double dx = pos.x - eye.x;
        double dy = pos.y - eye.y;
        double dz = pos.z - eye.z;
        mc.player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI - 90.0));
        mc.player.setPitch((float) (-MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    private void resetNavigation() {
        releaseMovement();
        path.clear();
        avoidedRouteCells.clear();
        navigationGoal = null;
        pathIndex = 0;
        pathAttempts = 0;
        lastPathDistance = Double.POSITIVE_INFINITY;
    }

    private void pressForward() {
        if (mc.options == null) return;
        if (walkPace.get() < 100) {
            walkPaceAccumulator += walkPace.get() / 100.0;
            if (walkPaceAccumulator < 1.0) {
                releaseMovement();
                return;
            }
            walkPaceAccumulator -= 1.0;
        } else {
            walkPaceAccumulator = 0;
        }
        setKeyPressed(mc.options.forwardKey, true);
        ownsForwardKey = true;
        // The worker never initiates sprinting: normal walking keeps the
        // player from overrunning the thin carpet edge during placement.
        if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private void releaseMovement() {
        if (ownsForwardKey) lastMovementReleaseTick = tick;
        if (ownsForwardKey && mc.options != null) {
            setKeyPressed(mc.options.forwardKey, false);
            ownsForwardKey = false;
        }
    }

    private static void setKeyPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    private void resetRuntime() {
        walkPaceAccumulator = 0;
        workingBandWidth = 1;
        workOrigin = null;
        workGoalTarget = null;
        targets.clear();
        blockedTargets.clear();
        placementMatrix.clear();
        targetIndices.clear();
        remainingTargets.clear();
        dispatchedPlacements.clear();
        emptySupplyUntil.clear();
        resetNavigation();
        targetIndex = 0;
        verificationIndex = 0;
        verificationPass = 0;
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyChestIndex = 0;
        supplyChest = null;
        supplyChestLabel = null;
        supplyStand = null;
        rejectedSupplyStands.clear();
        resetRestockTransfer();
        supplyOpenTick = -1;
        supplyAimTick = -1;
        supplyOpenAttempts = 0;
        supplyOpenSyncId = -1;
        snakeWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeReturning = false;
        snakeSweepCompleted = false;
        tick = 0;
        lastStatusMessageTick = 0;
        lastProgressTick = 0;
        nextActionTick = 0;
        lastPlacementDispatchTick = -100;
        lastMovementReleaseTick = -100;
    }

    @Override
    public String getInfoString() {
        return activity + " [" + completedMatrixCells() + "/" + targets.size() + "]";
    }

    private record ProjectionTarget(BlockPos pos, BlockState state, int lane) { }

    private record WorkChoice(Map.Entry<Integer, ProjectionTarget> target, double score) { }

    private static final class WorkRegion {
        private int count;
        private double bestPriority = Double.POSITIVE_INFINITY;
        private Map.Entry<Integer, ProjectionTarget> bestTarget;

        private void add(Map.Entry<Integer, ProjectionTarget> target, double priority) {
            count++;
            if (priority < bestPriority) {
                bestPriority = priority;
                bestTarget = target;
            }
        }

        private double score() {
            return bestPriority - 0.75 * Math.sqrt(count);
        }
    }

    private enum NavigationResult { MOVING, ARRIVED, UNREACHABLE }

    private enum WorkMode {
        START_OUTWARD("Start Outward"),
        ONE_ROUND_TRIP_SNAKE("One Round-Trip Snake");

        private final String label;

        WorkMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum TaskState { IDLE, REGISTERING, WORK, RESTOCK, VERIFY, WAITING_SUPPLY_RECHECK, PAUSED }
}
