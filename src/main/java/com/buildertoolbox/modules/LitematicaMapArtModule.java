package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.utils.MapArtBandGeometry;
import com.buildertoolbox.utils.MapArtSupplyPlanner;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NavigableMap;
import java.util.TreeMap;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
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
    private static final int RESTOCK_MAX_LOOKAHEAD_LANES = 6;
    private static final int MAX_PENDING_PLACEMENTS = 3;
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

    private final Setting<Boolean> autoBandWidth = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Band Width")
        .description("Use the widest band safely reachable from its middle, including the row just walked past.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> laneWidth = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Band Width")
        .description("Manual band width when Auto Band Width is off; still limited by safe reach.")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 8)
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
        .description("Maximum placement attempts per second while stationary. Up to three placements may await confirmation; walking waits for all of them.")
        .defaultValue(20)
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
        .description("Percentage of normal walking time used while following a route. Does not control placement speed.")
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
    private final Map<Item, Long> shortSupplyUntil = new HashMap<>();
    private final Map<BlockPos, Long> emptySupplyChestUntil = new HashMap<>();
    // A matching stack can exist but be too large for the currently unreserved
    // inventory space. Keep that separate from the 60-second no-supply marker.
    private final Map<Item, Integer> minimumTakeCapacity = new HashMap<>();
    private final Map<Item, Long> capacityRecheckAt = new HashMap<>();
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> avoidedRouteCells = new HashSet<>();
    private final List<BlockPos> snakeWaypoints = new ArrayList<>();
    private final List<Integer> snakeWaypointLanes = new ArrayList<>();
    private final Set<Integer> snakeEntryWaypoints = new HashSet<>();
    private final Set<BlockPos> rejectedSupplyStands = new HashSet<>();
    private final Set<BlockPos> triedSupplyChests = new HashSet<>();
    private final Map<Item, Integer> returnMaterialReserve = new HashMap<>();

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
    private int supplyReceivedThisRound;
    private BlockPos supplyChest;
    private Item supplyChestLabel;
    private BlockPos supplyStand;
    private BlockPos navigationGoal;
    private ProjectionTarget workGoalTarget;
    private BlockPos restockReturnPos;
    private boolean returningFromRestock;
    private int returnWorkLane = -1;
    private int supplyCheckedLane = -1;
    private int pathIndex;
    private int pathAttempts;
    private int restockInventoryBefore;
    private int restockTransferAttempts;
    private long restockTransferTick = -1;
    private boolean restockTransferStartedUnfocused;
    private long supplyOpenTick = -1;
    private long supplyAimTick = -1;
    private int supplyOpenAttempts;
    private int supplyOpenSyncId = -1;
    private int snakeWaypointIndex;
    private boolean snakeSweepCompleted;
    private boolean snakeCleanupMode;
    private int snakeSkippedWaypoints;
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
    private boolean backgroundPauseOverridden;
    private boolean closingSupplyBehindMenu;
    private boolean previousPauseOnLostFocus;
    private final Map<String, Item> signItemLookup = new HashMap<>();
    private boolean signItemLookupReady;
    private boolean startAfterRegistration;
    private TaskState stateBeforeRegistration = TaskState.IDLE;
    private String activity = "空闲";

    public LitematicaMapArtModule() {
        super(BuilderToolboxAddon.CATEGORY, "Map Art Maker", "Builds the current Litematica layer in serpentine bands and restocks from labeled containers. It never toggles Schematic Printer.");
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
        overrideFocusPause();
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
        buildSnakeWaypoints();
        activity = "开始蛇形打印";
        info("Map Art resumed: %d already complete, %d remaining, %d material types.",
            completedMatrixCells(), remainingTargets.size(), supplies.size());
        info("Working line spacing: %d blocks (%s).", workingBandWidth,
            autoBandWidth.get() ? "automatic safe maximum" : "manual, reach-limited");
    }

    @Override
    public void onDeactivate() {
        if (state != TaskState.IDLE && statusMessages.get()) {
            info("Map Art stopped: %s (%d/%d verified).", activity, completedMatrixCells(), targets.size());
        }
        releaseMovement();
        cancelQueuedPlacements();
        if (state == TaskState.RESTOCK && mc.player != null && mc.currentScreen instanceof GameMenuScreen) {
            closeSupplyScreen();
        }
        restoreFocusPause();
        resetNavigation();
        if (registerSupply.get()) registerSupply.set(false);
        state = TaskState.IDLE;
        activity = "空闲";
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        disable();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        // Esc remains a normal menu. Only our own supply UI stays behind it;
        // the server's handler is installed before this screen event fires.
        if (!(mc.currentScreen instanceof GameMenuScreen)) return;
        if (closingSupplyBehindMenu && event.screen == null) event.cancel();
        else if (state == TaskState.RESTOCK && supplyChest != null && supplyOpenTick >= 0
            && event.screen instanceof GenericContainerScreen) event.cancel();
    }

    /** Used by the menu pause hook; never changes pause behavior for an idle module. */
    public boolean runsWithGameMenu() {
        return isActive() && mc.world != null && mc.player != null
            && state != TaskState.IDLE && state != TaskState.REGISTERING && state != TaskState.PAUSED;
    }

    private void overrideFocusPause() {
        if (mc.options == null || backgroundPauseOverridden) return;
        previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        backgroundPauseOverridden = true;
    }

    private void restoreFocusPause() {
        if (!backgroundPauseOverridden || mc.options == null) return;
        mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
        backgroundPauseOverridden = false;
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
        switch (state) {
            case WORK -> tickWork();
            case RESTOCK -> tickRestock();
            case VERIFY -> tickVerify();
            case WAITING_SUPPLY_RECHECK -> tickSupplyRecheckWait();
            case WAITING_CAPACITY -> tickCapacityWait();
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
        if (!added) {
            warning("This container is already registered for that item.", new Object[0]);
            return;
        }
        containers.add(container.toImmutable());
        emptySupplyUntil.remove(item);
        shortSupplyUntil.remove(item);
        minimumTakeCapacity.remove(item);
        capacityRecheckAt.remove(item);
        emptySupplyChestUntil.remove(container);
        triedSupplyChests.remove(container);
        updateSupplyList();
        info("Supply chest registered: %s -> %s", item.getName().getString(), container.toShortString());
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
        // Item frames are the primary label for map-art supply chests. Do not
        // synchronously inspect hundreds of block entities (and every sign
        // line's item registry) on each click when a frame already identifies it.
        if (nearestItem != null) return nearestItem;

        // Search adjacent signs first. A label one block away wins over any
        // sign in a later shell, so most registrations inspect only 26 cells.
        for (int shell = 1; shell <= radius; shell++) {
            for (int dy = -shell; dy <= shell; dy++) {
                for (int dz = -shell; dz <= shell; dz++) {
                    for (int dx = -shell; dx <= shell; dx++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != shell) continue;
                        BlockPos pos = container.add(dx, dy, dz);
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
            if (nearestItem != null && nearestDistance <= shell * shell) return nearestItem;
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
        if (!signItemLookupReady) {
            for (Item item : Registries.ITEM) {
                Identifier itemId = Registries.ITEM.getId(item);
                if (item == Items.AIR || itemId == null) continue;
                signItemLookup.putIfAbsent(normalizeItemLabel(itemId.toString()), item);
                signItemLookup.putIfAbsent(normalizeItemLabel(itemId.getPath()), item);
                signItemLookup.putIfAbsent(normalizeItemLabel(item.getName().getString()), item);
            }
            signItemLookupReady = true;
        }
        return signItemLookup.get(normalizeItemLabel(label));
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
        emptySupplyUntil.clear();
        shortSupplyUntil.clear();
        emptySupplyChestUntil.clear();
        minimumTakeCapacity.clear();
        capacityRecheckAt.clear();
        triedSupplyChests.clear();
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
        int maximumWidth = MapArtBandGeometry.maximumWidth(placeRange.get(), eyeHeight);
        workingBandWidth = autoBandWidth.get() ? maximumWidth : Math.min(laneWidth.get(), maximumWidth);
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
        // Serialize actual rotation callbacks, but allow a small stationary
        // pipeline while previously clicked carpets await world updates.
        if (printer.hasQueuedProjectionPlacement() || dispatchedPlacements.size() >= MAX_PENDING_PLACEMENTS) {
            releaseMovement();
            lastProgressTick = tick;
            activity = "等待刚放置的方块确认";
            return;
        }
        if (returningFromRestock) {
            tickReturnFromRestock();
            return;
        }
        if (!snakeCleanupMode) {
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
        if (holdForPlacementConfirmation()) return;
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
            if (isSupplyTemporarilyDeferred(requiredItem)) {
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

    private void buildSnakeWaypoints() {
        snakeWaypoints.clear();
        snakeWaypointLanes.clear();
        snakeEntryWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeSweepCompleted = false;
        snakeSkippedWaypoints = 0;
        if (mc.player == null || targets.isEmpty()) return;
        BlockPos start = mc.player.getBlockPos();
        int width = Math.max(1, workingBandWidth);
        Set<Integer> activeLanes = new HashSet<>();
        for (ProjectionTarget target : remainingTargets.values()) {
            activeLanes.add(target.lane());
        }
        for (MapArtBandGeometry.Sweep sweep : MapArtBandGeometry.snakeSweeps(
            minX, maxX, minZ, maxZ, width, activeLanes, start.getX(), start.getZ())) {
            BlockPos first = new BlockPos(sweep.firstX(), walkY, sweep.centerZ());
            BlockPos last = new BlockPos(sweep.lastX(), walkY, sweep.centerZ());
            snakeEntryWaypoints.add(snakeWaypoints.size());
            snakeWaypoints.add(first);
            snakeWaypointLanes.add(sweep.lane());
            // Keep a distinct sweep phase even for a one-column projection.
            snakeWaypoints.add(last);
            snakeWaypointLanes.add(sweep.lane());
        }
        if (!snakeWaypoints.isEmpty()) {
            BlockPos last = snakeWaypoints.getLast();
            BlockPos first = snakeWaypoints.get(snakeWaypoints.size() - 2);
            int direction = last.getX() >= first.getX() ? 1 : -1;
            // Vacate the final center-row cell so it can be filled too.
            // Only use an adjacent real floor, never walk off the platform.
            for (BlockPos exit : List.of(last.add(direction, 0, 0), last.north(), last.south(), last.add(-direction, 0, 0))) {
                if (!isStandUsable(exit) || Math.abs(standSurfaceY(exit) - standSurfaceY(last)) > 0.6) continue;
                snakeWaypoints.add(exit);
                snakeWaypointLanes.add(snakeWaypointLanes.getLast());
                break;
            }
        }
        info("蛇形工作线：从当前位置单程扫描 %d 个路点。", snakeWaypoints.size());
    }

    private boolean holdForPlacementConfirmation() {
        if (dispatchedPlacements.isEmpty()) return false;
        releaseMovement();
        lastProgressTick = tick;
        activity = "等待放置确认后继续行走";
        return true;
    }

    private void tickReturnFromRestock() {
        if (restockReturnPos == null || remainingTargets.isEmpty() || hasReached(restockReturnPos)) {
            finishReturnToWork();
            return;
        }
        ProjectionTarget nearby = selectReachableMatrixTarget();
        if (nearby != null) {
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return;
        }
        if (holdForPlacementConfirmation()) return;
        if (tick - lastPlacementDispatchTick < 2) {
            releaseMovement();
            return;
        }
        NavigationResult route = navigateTo(restockReturnPos);
        if (route == NavigationResult.ARRIVED || route == NavigationResult.UNREACHABLE) {
            finishReturnToWork();
            return;
        }
        activity = "补货返程：沿途铺设，保留当前扫描带材料";
    }

    private void finishReturnToWork() {
        returningFromRestock = false;
        restockReturnPos = null;
        returnWorkLane = -1;
        returnMaterialReserve.clear();
        resetNavigation();
        activity = "返程结束，继续蛇形工作线";
    }

    private void tickSnakeWork() {
        if (remainingTargets.isEmpty()) {
            resetNavigation();
            snakeSweepCompleted = true;
            verificationIndex = 0;
            state = TaskState.VERIFY;
            activity = "矩阵已铺完，正在复查";
            return;
        }
        Integer activeLane = snakeWaypointIndex < snakeWaypointLanes.size()
            ? snakeWaypointLanes.get(snakeWaypointIndex) : null;
        if (activeLane != null && activeLane >= 0 && !hasRemainingWorkInLane(activeLane)) {
            skipSnakeLane(activeLane);
            activity = "当前扫描带已完成，转向下一带";
            return;
        }
        // Check the whole next band once, before consuming its first stacks.
        // A capacity-limited load is not requested again until a color runs out.
        if (activeLane != null && activeLane >= 0 && supplyCheckedLane != activeLane
            && dispatchedPlacements.isEmpty()) {
            supplyCheckedLane = activeLane;
            ProjectionTarget missing = findRestockTargetInLane(activeLane, true);
            if (missing != null) {
                beginRestock(missing);
                return;
            }
        }
        if (snakeEntryWaypoints.contains(snakeWaypointIndex)) {
            tickSnakeTurn();
            return;
        }
        ProjectionTarget nearby = activeLane == null || activeLane < 0
            ? null : selectReachableMatrixTarget(activeLane);
        if (nearby != null) {
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return;
        }
        if (holdForPlacementConfirmation()) return;
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
            activity = "蛇形扫描结束，复查未完成格";
            return;
        }
        // Within a band, restock only colors that have actually run out.
        // This avoids crossing the band empty-handed and later repeating it.
        if (activeLane != null && activeLane >= 0 && tick % 5 == 0) {
            ProjectionTarget missing = findRestockTargetInLane(activeLane, false);
            if (missing != null) {
                beginRestock(missing);
                return;
            }
        }
        BlockPos waypoint = snakeWaypoints.get(snakeWaypointIndex);
        NavigationResult result = navigateTo(waypoint);
        if (result == NavigationResult.ARRIVED || result == NavigationResult.UNREACHABLE) {
            if (result == NavigationResult.UNREACHABLE) {
                snakeSkippedWaypoints++;
                resetNavigation();
                if (snakeSkippedWaypoints <= 3 || snakeSkippedWaypoints % 16 == 0) {
                    warning("蛇形路点 %s 不可达；已记录，扫描后将按剩余矩阵补扫。", waypoint.toShortString());
                }
            }
            snakeWaypointIndex++;
        }
        activity = "蛇形扫描：" + Math.min(snakeWaypointIndex + 1, snakeWaypoints.size())
            + "/" + snakeWaypoints.size() + " 路点";
    }

    private void tickSnakeTurn() {
        // While turning, fill the previous band's endpoint just vacated by
        // the player. Otherwise the center row leaves one hole at every turn.
        if (snakeWaypointIndex > 0) {
            ProjectionTarget trailing = selectReachableMatrixTarget(snakeWaypointLanes.get(snakeWaypointIndex - 1));
            if (trailing != null) {
                if (tick >= nextActionTick) placeTarget(trailing);
                else releaseMovement();
                return;
            }
        }
        if (holdForPlacementConfirmation()) return;
        NavigationResult result = navigateTo(snakeWaypoints.get(snakeWaypointIndex));
        if (result == NavigationResult.ARRIVED) {
            snakeWaypointIndex++;
            resetNavigation();
        } else if (result == NavigationResult.UNREACHABLE) {
            snakeSkippedWaypoints++;
            warning("扫描带端点不可达，留待矩阵补扫。");
            skipSnakeLane(snakeWaypointLanes.get(snakeWaypointIndex));
        }
        activity = "蛇形转弯：前往下一条中线端点";
    }

    private void skipSnakeLane(int lane) {
        resetNavigation();
        do snakeWaypointIndex++;
        while (snakeWaypointIndex < snakeWaypointLanes.size() && snakeWaypointLanes.get(snakeWaypointIndex) == lane);
    }

    /** Pick a nearest unfinished matrix cell already in interaction range. */
    private ProjectionTarget selectReachableMatrixTarget() {
        return selectReachableMatrixTarget(null);
    }

    private ProjectionTarget selectReachableMatrixTarget(Integer requiredLane) {
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
                if (requiredLane != null && candidate.lane() != requiredLane) continue;
                if (blockedTargets.contains(candidate.pos()) || playerIntersectsTarget(candidate)) continue;
                if (eye.squaredDistanceTo(placementAim(candidate)) > rangeSquared) continue;
                if (isPlacementPending(candidate.pos()) || isSatisfied(candidate)) continue;
                Item item = candidate.state().getBlock().asItem();
                if (!hasItem(item)) continue;
                if (returningFromRestock && candidate.lane() != returnWorkLane
                    && countInventory(item) <= returnMaterialReserve.getOrDefault(item, 0)) continue;

                double distance = eye.squaredDistanceTo(Vec3d.ofCenter(candidate.pos()));
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private ProjectionTarget findRestockTargetInLane(int lane, boolean fillLane) {
        Map<Item, Integer> laneDemand = new HashMap<>();
        Map<Item, ProjectionTarget> representative = new HashMap<>();
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            if (target.lane() != lane || blockedTargets.contains(target.pos())
                || isPlacementPending(target.pos())) continue;
            if (isSatisfied(target)) continue;
            Item item = target.state().getBlock().asItem();
            laneDemand.merge(item, 1, Integer::sum);
            representative.putIfAbsent(item, target);
        }
        Map<Item, Integer> deficits = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : laneDemand.entrySet()) {
            int held = countInventory(entry.getKey());
            if ((held == 0 || fillLane) && held < entry.getValue()) {
                deficits.put(entry.getKey(), entry.getValue() - held);
            }
        }
        if (deficits.isEmpty()) return null;
        Set<Item> upcoming = laneDemand.keySet();
        Set<Item> heldItems = inventoryItems();
        ProjectionTarget best = null;
        int largestDeficit = 0;
        for (Map.Entry<Item, Integer> entry : deficits.entrySet()) {
            Item item = entry.getKey();
            int reserved = reservedSlotsForOtherColors(item, upcoming, heldItems);
            if (!hasUsableSupply(item, reserved)) continue;
            if (inventoryCapacity(item, reserved) <= 0) continue;
            int deficit = entry.getValue();
            if (deficit > largestDeficit) {
                largestDeficit = deficit;
                best = representative.get(item);
            }
        }
        return best;
    }

    private boolean hasRemainingWorkInLane(int lane) {
        return remainingTargets.values().stream()
            .anyMatch(target -> target.lane() == lane && !blockedTargets.contains(target.pos()));
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
        if (tick - lastMovementReleaseTick < 1 || horizontalSpeedSquared > 0.025 * 0.025
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
                lastProgressTick = tick;
                activity = "放置矩阵：" + completedMatrixCells() + "/" + targets.size();
            }
            case MISSING_ITEM -> {
                if (!holdForPlacementConfirmation() && !returningFromRestock) beginRestock(target);
            }
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
            if (printer.consumeCancelledPlacement(entry.getKey())) {
                dispatchedPlacements.remove(entry.getKey());
                continue;
            }
            if (tick - entry.getValue() < PLACEMENT_CONFIRM_TICKS) continue;
            dispatchedPlacements.remove(entry.getKey());
            printer.cancelQueuedProjectionPlacement(entry.getKey());
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
        BlockPos returnPos = mc.player.getBlockPos().toImmutable();
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
            if (!minimumTakeCapacity.isEmpty() || emptyInventorySlots() == 0) {
                beginCapacityWait();
                return;
            }
            activity = "暂停：材料暂不可取或背包空间不足";
            error("No obtainable supplies or inventory capacity. Free space or refill/register the required chests.", new Object[0]);
            state = TaskState.PAUSED;
            return;
        }
        restockReturnPos = returnPos;
        returningFromRestock = false;
        state = TaskState.RESTOCK;
        Map<Item, Integer> bandDemand = countNextLaneMaterials();
        int heldForBand = bandDemand.entrySet().stream()
            .mapToInt(entry -> Math.min(entry.getValue(), countInventory(entry.getKey()))).sum();
        int neededForBand = bandDemand.values().stream().mapToInt(Integer::intValue).sum();
        info("当前蛇形带备料：剩余 %d 格，背包可覆盖 %d 格，缺口 %d；优先补齐后再装后续条带。",
            neededForBand, heldForBand, neededForBand - heldForBand);
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
                .filter(pos -> !isSupplyChestTemporarilyEmpty(pos))
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
        List<List<ProjectionTarget>> bands = upcomingTargetBands();
        if (bands.isEmpty()) return false;
        Map<Item, Integer> urgent = materialCounts(bands.getFirst());
        List<List<Item>> materialBands = new ArrayList<>();
        Map<Item, MapArtSupplyPlanner.Stock> inventory = new HashMap<>();
        int empty = emptyInventorySlots();
        for (List<ProjectionTarget> band : bands) {
            List<Item> materials = new ArrayList<>();
            for (ProjectionTarget target : band) {
                Item item = target.state().getBlock().asItem();
                materials.add(item);
                inventory.computeIfAbsent(item, key -> new MapArtSupplyPlanner.Stock(
                    countInventory(key), countInventory(key) + inventoryCapacity(key, empty),
                    new ItemStack(key).getMaxCount(), !supplies.getOrDefault(key, List.of()).isEmpty(),
                    isSupplyTemporarilyDeferred(key)));
            }
            materialBands.add(materials);
        }
        MapArtSupplyPlanner.Plan<Item> plan = MapArtSupplyPlanner.plan(materialBands, inventory, empty);
        Item selected = null;
        double selectedScore = Double.NEGATIVE_INFINITY;
        int selectedPriority = Integer.MAX_VALUE;
        for (Map.Entry<Item, Integer> entry : plan.desired().entrySet()) {
            Item item = entry.getKey();
            int held = countInventory(item);
            int deficit = entry.getValue() - held;
            int reserved = plan.reservedForOthers(item);
            if (deficit <= 0 || !hasUsableSupply(item, reserved)) continue;
            if (inventoryCapacity(item, reserved) <= 0) continue;
            int priority = urgent.getOrDefault(item, 0) > held ? 0 : 1;
            double score = supplyVisitScore(item, deficit);
            if (selected == null || priority < selectedPriority
                || (priority == selectedPriority && (item == preferred
                    || (selected != preferred && score > selectedScore)))) {
                selected = item;
                selectedPriority = priority;
                selectedScore = score;
            }
        }
        if (selected == null) return false;
        supplyItem = selected;
        reservedSupplySlots = plan.reservedForOthers(selected);
        supplyReceivedThisRound = 0;
        int capacity = inventoryCapacity(selected, reservedSupplySlots);
        supplyAmount = Math.min(capacity, Math.max(0, plan.desired().get(selected) - countInventory(selected)));
        info("按蛇形顺序补货 %s：当前带需要 %d，背包已有 %d，计划补入 %d（整组取货）。",
            selected.getName().getString(), urgent.getOrDefault(selected, 0), countInventory(selected), supplyAmount);
        triedSupplyChests.clear();
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        return true;
    }

    private double supplyVisitScore(Item item, int demand) {
        double distance = supplies.getOrDefault(item, List.of()).stream()
            .filter(pos -> !isSupplyChestTemporarilyEmpty(pos))
            .mapToDouble(pos -> Math.sqrt(distanceToPlayer(pos)))
            .min().orElse(Double.POSITIVE_INFINITY);
        return -distance + Math.min(demand, 64) * 0.01;
    }

    private Map<Item, Integer> countNextLaneMaterials() {
        List<List<ProjectionTarget>> bands = upcomingTargetBands();
        return bands.isEmpty() ? Map.of() : materialCounts(bands.getFirst());
    }

    private boolean hasUsableSupply(Item item) {
        int reserved = reservedSlotsForOtherColors(item, countNextLaneMaterials().keySet(), inventoryItems());
        return hasUsableSupply(item, reserved);
    }

    private boolean hasUsableSupply(Item item, int reserved) {
        if (isSupplyTemporarilyDeferred(item)) return false;
        Integer minimum = minimumTakeCapacity.get(item);
        if (minimum != null) {
            if (inventoryCapacity(item, reserved) < minimum
                && tick < capacityRecheckAt.getOrDefault(item, tick)) return false;
            minimumTakeCapacity.remove(item);
            capacityRecheckAt.remove(item);
        }
        return supplies.getOrDefault(item, List.of()).stream()
            .anyMatch(pos -> !isSupplyChestTemporarilyEmpty(pos));
    }

    private boolean isSupplyChestTemporarilyEmpty(BlockPos pos) {
        Long until = emptySupplyChestUntil.get(pos);
        if (until == null) return false;
        if (tick < until) return true;
        emptySupplyChestUntil.remove(pos);
        return false;
    }

    private void rememberTakeCapacity(Item item, int minimum) {
        minimumTakeCapacity.merge(item, minimum, Math::min);
        capacityRecheckAt.put(item, tick + EMPTY_SUPPLY_RECHECK_TICKS);
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


    private void finishRestock() {
        boolean didRestock = state == TaskState.RESTOCK;
        closeSupplyScreen();
        resetRestockTransfer();
        resetNavigation();
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyReceivedThisRound = 0;
        supplyChest = null;
        workGoalTarget = null;
        returnMaterialReserve.clear();
        returnWorkLane = -1;
        returningFromRestock = false;
        if (didRestock && restockReturnPos != null && !hasReached(restockReturnPos)) {
            if (!isStandUsable(restockReturnPos)) restockReturnPos = findStandNear(restockReturnPos, 2);
            returningFromRestock = restockReturnPos != null;
            if (!snakeCleanupMode) {
                List<List<ProjectionTarget>> bands = upcomingTargetBands();
                if (!bands.isEmpty()) {
                    returnWorkLane = bands.getFirst().getFirst().lane();
                    returnMaterialReserve.putAll(materialCounts(bands.getFirst()));
                }
            }
        } else {
            restockReturnPos = null;
        }
        state = TaskState.WORK;
        activity = returningFromRestock ? "补货结束，规划沿路铺设的返程" : "补货结束，继续铺设";
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
        Set<Item> upcoming = countUpcomingMaterials().keySet();
        Set<Item> heldItems = inventoryItems();
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.tailMap(Math.max(0, startIndex), true).values())) {
            if (isSatisfied(target) || blockedTargets.contains(target.pos())) continue;
            Item item = target.state().getBlock().asItem();
            if (hasItem(item)) return true;
            if (hasUsableSupply(item) && inventoryCapacity(item,
                reservedSlotsForOtherColors(item, upcoming, heldItems)) > 0) return true;
        }
        return false;
    }

    private void markSupplyTemporarilyEmpty(Item item) {
        emptySupplyUntil.put(item, tick + EMPTY_SUPPLY_RECHECK_TICKS);
    }

    private boolean isSupplyTemporarilyDeferred(Item item) {
        Long emptyUntil = emptySupplyUntil.get(item);
        Long shortUntil = shortSupplyUntil.get(item);
        if (emptyUntil != null && tick >= emptyUntil) emptySupplyUntil.remove(item);
        if (shortUntil != null && tick >= shortUntil) shortSupplyUntil.remove(item);
        return (emptyUntil != null && tick < emptyUntil) || (shortUntil != null && tick < shortUntil);
    }

    private boolean hasPendingSupplyRecheck() {
        emptySupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        shortSupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        return !emptySupplyUntil.isEmpty() || !shortSupplyUntil.isEmpty();
    }

    private void beginSupplyRecheckWait() {
        releaseMovement();
        state = TaskState.WAITING_SUPPLY_RECHECK;
        activity = "等待材料箱可再次取货";
    }

    private void beginCapacityWait() {
        releaseMovement();
        state = TaskState.WAITING_CAPACITY;
        activity = "等待背包空间；箱内有货不记为无货";
    }

    private void tickCapacityWait() {
        releaseMovement();
        if (tick % 20 != 0) return;
        if (!hasPotentialWorkFrom(0)) return;
        state = TaskState.WORK;
        activity = "背包已有可用空间，返回蛇形扫描路点";
    }

    private void tickSupplyRecheckWait() {
        if (hasPendingSupplyRecheck()) {
            long nextCheck = Math.min(
                emptySupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                shortSupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE));
            long seconds = Math.max(1, (nextCheck - tick + 19) / 20);
            activity = "等待材料箱复查：约 " + seconds + " 秒";
            return;
        }
        targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
        verificationIndex = 0;
        state = TaskState.WORK;
        activity = "补货延迟已过，重新检查登记箱";
        info("Map Art: supply recheck interval expired; retrying remaining colors.");
    }

    /** Actual snake order, excluding cells already completed in the real world. */
    private List<List<ProjectionTarget>> upcomingTargetBands() {
        Map<Integer, List<ProjectionTarget>> byLane = new LinkedHashMap<>();
        boolean cleanup = snakeCleanupMode || snakeWaypointIndex >= snakeWaypointLanes.size();
        if (!cleanup) {
            for (int index = snakeWaypointIndex; index < snakeWaypointLanes.size(); index++) {
                int lane = snakeWaypointLanes.get(index);
                if (lane < 0 || byLane.containsKey(lane)) continue;
                if (byLane.size() >= RESTOCK_MAX_LOOKAHEAD_LANES) break;
                byLane.put(lane, new ArrayList<>());
            }
        }
        if (cleanup) byLane.put(-1, new ArrayList<>());
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            List<ProjectionTarget> band = byLane.get(cleanup ? -1 : target.lane());
            if (band == null || blockedTargets.contains(target.pos()) || isPlacementPending(target.pos())
                || isSatisfied(target)) continue;
            band.add(target);
        }
        List<List<ProjectionTarget>> result = new ArrayList<>();
        for (Map.Entry<Integer, List<ProjectionTarget>> entry : byLane.entrySet()) {
            List<ProjectionTarget> band = entry.getValue();
            if (band.isEmpty()) continue;
            if (cleanup) band.sort(Comparator.comparingDouble(target -> workPriority(target.pos())));
            else {
                int firstX = 0;
                int lastX = 0;
                for (int index = 0; index + 1 < snakeWaypointLanes.size(); index++) {
                    if (!snakeEntryWaypoints.contains(index) || !snakeWaypointLanes.get(index).equals(entry.getKey())) continue;
                    firstX = snakeWaypoints.get(index).getX();
                    lastX = snakeWaypoints.get(index + 1).getX();
                    break;
                }
                int direction = lastX >= firstX ? 1 : -1;
                band.sort(Comparator.comparingInt((ProjectionTarget target) -> direction * target.pos().getX())
                    .thenComparingInt(target -> target.pos().getZ()));
            }
            result.add(band);
        }
        return result;
    }

    private Map<Item, Integer> materialCounts(List<ProjectionTarget> band) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ProjectionTarget target : band) counts.merge(target.state().getBlock().asItem(), 1, Integer::sum);
        return counts;
    }

    private Map<Item, Integer> countUpcomingMaterials() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (List<ProjectionTarget> band : upcomingTargetBands()) {
            for (Map.Entry<Item, Integer> entry : materialCounts(band).entrySet()) {
                counts.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        return counts;
    }

    private void tickRestock() {
        // Restocking never puts items on the cursor. If an old/incomplete
        // transaction left one there, do not risk clicking or closing it.
        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler
            && !handler.getCursorStack().isEmpty()) {
            releaseMovement();
            activity = "已暂停：鼠标指针仍持有物品，请手动放回";
            warning("补货界面的指针仍持有物品；已暂停，避免误放或丢失。", new Object[0]);
            state = TaskState.PAUSED;
            return;
        }
        if (supplyItem != null && supplyChest == null) refreshSupplyAmount();
        if (supplyItem == null || supplyAmount <= 0) {
            if (supplyItem != null) {
                emptySupplyUntil.remove(supplyItem);
                shortSupplyUntil.remove(supplyItem);
                minimumTakeCapacity.remove(supplyItem);
                capacityRecheckAt.remove(supplyItem);
            }
            continueRestock();
            return;
        }

        if (supplyChest == null) {
            // Choose again after every chest: the nearest remaining chest can
            // change as the player moves, and registration order is arbitrary.
            BlockPos nearest = supplies.getOrDefault(supplyItem, List.of()).stream()
                .filter(pos -> !triedSupplyChests.contains(pos) && !isSupplyChestTemporarilyEmpty(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer))
                .orElse(null);
            if (nearest == null) {
                if (minimumTakeCapacity.containsKey(supplyItem)) {
                    activity = "箱内有材料，但当前预留背包空间装不下整组；继续其它颜色";
                    warning("%s 的箱内有货，但当前可用背包空间不足；背包腾出空间后再取，不标记为无货。",
                        supplyItem.getName().getString());
                } else {
                    if (supplyReceivedThisRound == 0) markSupplyTemporarilyEmpty(supplyItem);
                    else shortSupplyUntil.put(supplyItem, tick + EMPTY_SUPPLY_RECHECK_TICKS);
                    activity = supplyReceivedThisRound == 0
                        ? "所有同色箱均未取到材料，60 秒后复查" : "本轮已取到部分材料，但尚未补足";
                    warning("已尝试所有登记的 %s 箱，本轮取到 %d 个、仍缺 %d 个；60 秒后复查，先铺其它颜色。",
                        supplyItem.getName().getString(), supplyReceivedThisRound, supplyAmount);
                }
                continueRestock();
                return;
            }
            supplyChest = nearest;
            triedSupplyChests.add(nearest);
            info("补货 %s：前往最近的未尝试箱子 %s；本轮还需 %d 个。",
                supplyItem.getName().getString(), nearest.toShortString(), supplyAmount);
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
            closeSupplyScreen();
            opened = null;
        }
        if (opened == null && mc.currentScreen != null && !(mc.currentScreen instanceof GameMenuScreen)) {
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
            int confirmationTicks = restockTransferStartedUnfocused ? 6 : 2;
            int timeoutTicks = restockTransferStartedUnfocused ? 60 : 10;
            if (moved > 0 && tick - restockTransferTick >= confirmationTicks) {
                supplyAmount -= moved;
                supplyReceivedThisRound += moved;
                resetRestockTransfer();
                nextActionTick = tick + 3;
                activity = "已取到 " + moved + " 个 " + supplyItem.getName().getString();
                return;
            }
            if (tick - restockTransferTick < timeoutTicks) {
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
        refreshSupplyAmount();
        if (supplyAmount <= 0) {
            continueRestock();
            return;
        }
        if (safeCapacity <= 0) {
            rememberTakeCapacity(supplyItem, 1);
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
        boolean matchingStockPresent = false;
        int smallestMatchingStack = Integer.MAX_VALUE;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (!stack.isEmpty() && labelId.equals(Registries.ITEM.getId(stack.getItem()))) {
                matchingStockPresent = true;
                smallestMatchingStack = Math.min(smallestMatchingStack, stack.getCount());
                if (stack.getCount() > safeCapacity) continue;
                // Move the entire chest stack in one quick-move. A smaller
                // remaining quota may be overfilled by one stack, but we
                // never split it or leave material on the cursor.
                restockInventoryBefore = countInventory(supplyItem);
                InvUtils.shiftClick().slotId(slot);
                restockTransferTick = tick;
                restockTransferStartedUnfocused = !mc.isWindowFocused();
                nextActionTick = tick + 1;
                activity = "从补给箱取 " + supplyItem.getName().getString();
                return;
            }
        }
        if (!matchingStockPresent) {
            // A partially stocked chest is used until its exact item runs out.
            // Remember only genuinely exhausted chests, not stacks that are
            // temporarily too large for the reserved inventory capacity.
            emptySupplyChestUntil.put(supplyChest.toImmutable(), tick + EMPTY_SUPPLY_RECHECK_TICKS);
        } else {
            rememberTakeCapacity(supplyItem, smallestMatchingStack);
        }
        closeSupplyScreen();
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        activity = matchingStockPresent ? "当前背包空间不足，尝试同色下一箱" : "当前箱子的所需材料已取尽，尝试同色下一箱";
    }

    private void refreshSupplyAmount() {
        if (supplyItem == null) return;
        int unfinished = countUpcomingMaterials().getOrDefault(supplyItem, 0);
        supplyAmount = Math.min(supplyAmount, Math.max(0, unfinished - countInventory(supplyItem)));
    }

    private void resetRestockTransfer() {
        restockInventoryBefore = 0;
        restockTransferAttempts = 0;
        restockTransferTick = -1;
        restockTransferStartedUnfocused = false;
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
            if (!minimumTakeCapacity.isEmpty() || emptyInventorySlots() == 0) {
                beginCapacityWait();
                return;
            }
            activity = "暂停：剩余材料暂不可取或背包空间不足";
            warning("其余 %d 格暂时无法补货或背包空间不足；地图画已安全暂停。", incomplete);
            state = TaskState.PAUSED;
            return;
        }
        if (snakeSweepCompleted && !snakeCleanupMode) {
            snakeCleanupMode = true;
            verificationPass = 1;
            targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
            resetNavigation();
            state = TaskState.WORK;
            activity = "蛇形扫描结束，定向补扫剩余 " + incomplete + " 格";
            info("蛇形扫描跳过 %d 个不可达路点；开始按剩余矩阵补扫。", snakeSkippedWaypoints);
            return;
        }
        if (verificationPass++ >= 1) {
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
            if (satisfied) {
                boolean removed = remainingTargets.remove(index) != null;
                if (removed && returningFromRestock && target.lane() == returnWorkLane) {
                    Item item = target.state().getBlock().asItem();
                    returnMaterialReserve.computeIfPresent(item, (key, amount) -> Math.max(0, amount - 1));
                }
            }
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
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler)) return;
        // Close the actual handler without dismissing the user's Esc menu or
        // locking their mouse again. A user-initiated menu close is not blocked.
        GameMenuScreen menu = mc.currentScreen instanceof GameMenuScreen gameMenu ? gameMenu : null;
        closingSupplyBehindMenu = menu != null;
        try {
            mc.player.closeHandledScreen();
        } finally {
            closingSupplyBehindMenu = false;
            // onDeactivate may already be unsubscribed from screen events.
            if (menu != null && mc.currentScreen == null && mc.world != null) mc.setScreen(menu);
        }
    }

    // Ground-only routes never use projection ghosts as support. Return
    // routes prefer productive cells among paths of the same shortest length.
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
        Map<BlockPos, Integer> depths = new HashMap<>();
        Map<BlockPos, Integer> scores = new HashMap<>();
        Map<BlockPos, Integer> benefitCache = new HashMap<>();
        Map<Item, Integer> held = new HashMap<>();
        boolean productiveReturn = returningFromRestock && goal.equals(restockReturnPos);
        int scanLane = state == TaskState.WORK && !returningFromRestock && !snakeCleanupMode
            && snakeWaypointIndex < snakeWaypointLanes.size() ? snakeWaypointLanes.get(snakeWaypointIndex) : -1;
        boolean centeredSweep = scanLane >= 0;
        int centerZ = centeredSweep ? MapArtBandGeometry.centerZ(minZ, maxZ, workingBandWidth, scanLane) : 0;
        boolean scoreEqualRoutes = productiveReturn || centeredSweep;
        if (productiveReturn) {
            for (Item item : inventoryItems()) held.put(item, countInventory(item));
        }
        open.add(start);
        parents.put(start, null);
        depths.put(start, 0);
        scores.put(start, 0);
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
                int depth = depths.get(current) + 1;
                Integer knownDepth = depths.get(next);
                if (knownDepth != null && (!scoreEqualRoutes || knownDepth != depth)) continue;
                if (next.getX() < minPathX || next.getX() > maxPathX || next.getZ() < minPathZ || next.getZ() > maxPathZ
                    || next.getY() != walkY || avoidedRouteCells.contains(next)
                    || !isStandUsable(next) || Math.abs(standSurfaceY(next) - standSurfaceY(current)) > 0.6) continue;
                int benefit = productiveReturn ? benefitCache.computeIfAbsent(next, pos -> returnPathBenefit(pos, held))
                    : centeredSweep ? -Math.abs(next.getZ() - centerZ) : 0;
                int score = scoreEqualRoutes ? scores.get(current) + benefit : 0;
                if (knownDepth == null || score > scores.get(next)) {
                    parents.put(next, current);
                    depths.put(next, depth);
                    scores.put(next, score);
                    if (knownDepth == null) open.addLast(next);
                }
            }
        }
        return false;
    }

    private int returnPathBenefit(BlockPos stand, Map<Item, Integer> held) {
        int useful = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                Integer index = targetIndices.get(stand.add(dx, 0, dz));
                ProjectionTarget target = index == null ? null : remainingTargets.get(index);
                if (target == null || blockedTargets.contains(target.pos()) || isPlacementPending(target.pos())
                    || mc.world.getBlockState(target.pos()).equals(target.state())) continue;
                Item item = target.state().getBlock().asItem();
                int available = held.getOrDefault(item, 0);
                if (available > 0 && (target.lane() == returnWorkLane
                    || available > returnMaterialReserve.getOrDefault(item, 0))) useful++;
            }
        }
        return Math.min(8, useful) * 2
            + (mc.world.getBlockState(stand).getBlock() instanceof CarpetBlock ? 0 : 1);
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
        int centerZ = MapArtBandGeometry.centerZ(minZ, maxZ, workingBandWidth, band);
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
        cancelQueuedPlacements();
        walkPaceAccumulator = 0;
        workingBandWidth = 1;
        workOrigin = null;
        workGoalTarget = null;
        restockReturnPos = null;
        returningFromRestock = false;
        returnWorkLane = -1;
        supplyCheckedLane = -1;
        returnMaterialReserve.clear();
        targets.clear();
        blockedTargets.clear();
        placementMatrix.clear();
        targetIndices.clear();
        remainingTargets.clear();
        dispatchedPlacements.clear();
        emptySupplyUntil.clear();
        shortSupplyUntil.clear();
        emptySupplyChestUntil.clear();
        minimumTakeCapacity.clear();
        capacityRecheckAt.clear();
        triedSupplyChests.clear();
        resetNavigation();
        targetIndex = 0;
        verificationIndex = 0;
        verificationPass = 0;
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyReceivedThisRound = 0;
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
        snakeWaypointLanes.clear();
        snakeEntryWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeSweepCompleted = false;
        snakeCleanupMode = false;
        snakeSkippedWaypoints = 0;
        tick = 0;
        lastStatusMessageTick = 0;
        lastProgressTick = 0;
        nextActionTick = 0;
        lastPlacementDispatchTick = -100;
        lastMovementReleaseTick = -100;
    }

    private void cancelQueuedPlacements() {
        if (printer == null) return;
        for (BlockPos pos : dispatchedPlacements.keySet()) printer.cancelQueuedProjectionPlacement(pos);
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

    private enum TaskState { IDLE, REGISTERING, WORK, RESTOCK, VERIFY, WAITING_SUPPLY_RECHECK, WAITING_CAPACITY, PAUSED }
}
