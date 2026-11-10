package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.utils.RestockSurplusController;
import com.buildertoolbox.utils.MapArtAutoEat;
import com.buildertoolbox.utils.MapArtNavigationPolicy;
import com.buildertoolbox.utils.MapArtProgressTimer;
import com.buildertoolbox.utils.CursorRecovery;
import com.buildertoolbox.utils.AutomationControl;
import com.buildertoolbox.utils.ServerPlacementLedger;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import meteordevelopment.meteorclient.events.entity.player.ItemUseCrosshairTargetEvent;
import com.buildertoolbox.utils.MapArtBandGeometry;
import com.buildertoolbox.utils.GroundPathVisibility;
import com.buildertoolbox.utils.MapArtSupplyPlanner;
import com.buildertoolbox.utils.MapArtColorFilter;
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
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import net.minecraft.util.DyeColor;
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
 * A flat-projection map-art worker with its own unregistered placement engine.
 * The former standalone schematic printer is no longer a user-facing module.
 */
public final class LitematicaMapArtModule extends Module {
    private static final long EMPTY_SUPPLY_RECHECK_TICKS = 20L * 60L;
    private static final long PLACEMENT_CONFIRM_TICKS = 20L;
    private static final int RESTOCK_MAX_LOOKAHEAD_LANES = 2;
    private static final int MAX_PENDING_PLACEMENTS = 3;
    private static final int WORK_REGION_SIZE = 8;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final RestockSurplusController restockSurplus = new RestockSurplusController(mc);
    private ProjectionTarget surplusRestockTarget;
    private boolean skipRestockSurplusOnce;
    private long nextSurplusCheckTick;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLocations = settings.createGroup("Locations");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Eat")
        .description("饱食度低于四格（8 点）时，从背包中自动进食，吃满后继续工作。")
        .defaultValue(true).build());
    private final MapArtAutoEat autoEater = new MapArtAutoEat(mc, message -> warning("%s", message));
    private final CursorRecovery cursorRecovery = new CursorRecovery(mc);
    private final ServerPlacementLedger<BlockPos,BlockState> placementReceipts = new ServerPlacementLedger<>();
    private final java.util.concurrent.ConcurrentHashMap<Long,Long> receiptChunks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long receiptTick;

    // Persist through Meteor profiles, but present these in the color table
    // instead of exposing a raw editable list of color IDs.
    private final Setting<Boolean> colorFilterEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("Color Filter Enabled")
        .description("启用下方颜色筛选表。勾选的颜色会跳过铺设和补货。")
        .defaultValue(false)
        .visible(() -> false)
        .onChanged(value -> queueWorkPlanUpdate())
        .build()
    );

    private final Setting<List<String>> excludedColors = sgGeneral.add(new StringListSetting.Builder()
        .name("Excluded Colors")
        .description("保存颜色筛选表中勾选的颜色。")
        .defaultValue(new String[0])
        .visible(() -> false)
        .onChanged(value -> { if (colorFilterEnabled.get()) queueWorkPlanUpdate(); })
        .build()
    );

    private final Setting<Boolean> registerSupply = sgLocations.add(new BoolSetting.Builder()
        .name("Select Supply Chests")
        .description("进入选择模式后隐藏设置，右键点击带物品标签的容器进行登记，按 Esc 返回。")
        .defaultValue(false)
        .onChanged(this::onRegisterSupplyChanged)
        .build()
    );

    private final Setting<List<String>> registeredSupplies = sgLocations.add(new StringListSetting.Builder()
        .name("Supply Chest List")
        .description("按“物品 ID → X,Y,Z 坐标”保存补给箱，可手动编辑列表或重新登记。")
        .defaultValue(new String[0])
        .onChanged(this::restoreSupplyList)
        .build()
    );

    private final Setting<Boolean> autoBandWidth = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Band Width")
        .description("根据放置距离选择从中线能安全覆盖的最大条带宽度，包含刚走过的一行。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> laneWidth = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Band Width")
        .description("关闭自动条带宽度后使用此值，实际宽度仍受安全放置距离限制。")
        .defaultValue(4)
        .range(1, 16)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Boolean> reverseSnake = sgGeneral.add(new BoolSetting.Builder()
        .name("Reverse Snake")
        .description("从正常路线的另一端反向蛇形铺设。两人协作时可让其中一人开启，补货按所选方向计算。")
        .defaultValue(false)
        .onChanged(value -> queueWorkPlanUpdate())
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("Place Range")
        .description("铺设地图画时允许的最大交互距离，单位为格。")
        .defaultValue(3.6)
        .range(2.0, 3.6)
        .sliderRange(2.0, 3.6)
        .build()
    );

    private final Setting<Integer> placementSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("Placement Speed")
        .description("站稳后每秒最多尝试放置的次数。最多同时等待三次放置确认，确认完成后继续行走。")
        .defaultValue(20)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> statusMessages = sgGeneral.add(new BoolSetting.Builder()
        .name("Status Messages")
        .description("每十秒及任务停止时，在聊天栏显示进度。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> routeRetries = sgNavigation.add(new IntSetting.Builder()
        .name("Route Retries")
        .description("判定目标或补给箱不可达前，允许重新规划路线的次数。")
        .defaultValue(2)
        .range(0, 6)
        .sliderRange(0, 4)
        .build()
    );

    private final Setting<Integer> walkPace = sgNavigation.add(new IntSetting.Builder()
        .name("Walk Speed (%)")
        .description("按正常行走时间的百分比控制路线行走速度。放置速度由单独选项控制。")
        .defaultValue(100)
        .range(25, 100)
        .sliderRange(25, 100)
        .build()
    );

    private final Setting<Integer> pathSearchLimit = sgNavigation.add(new IntSetting.Builder()
        .name("Path Search Limit")
        .description("每次寻路最多检查的真实可站立格子数量。")
        .defaultValue(20000)
        .range(1024, 100000)
        .sliderRange(4096, 50000)
        .build()
    );

    private final Setting<Integer> stuckTimeout = sgNavigation.add(new IntSetting.Builder()
        .name("Stuck Timeout")
        .description("连续多少游戏刻未接近目标时重新寻路。通常每秒二十游戏刻。")
        .defaultValue(80)
        .range(20, 300)
        .sliderRange(40, 160)
        .build()
    );

    private final List<ProjectionTarget> targets = new ArrayList<>();
    private final List<ProjectionTarget> projectionLayer = new ArrayList<>();
    private boolean workPlanDirty;
    private int excludedProjectionCells;
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

    private final LitematicaPrinterModule printer = new LitematicaPrinterModule();
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
    private long supplyProgressTick;
    private double supplyBestDistance = Double.POSITIVE_INFINITY;
    private int supplyOpenSyncId = -1;
    private net.minecraft.screen.ScreenHandler ownedSupplyHandler;
    private int snakeWaypointIndex;
    private boolean snakeSweepCompleted;
    private int snakeSkippedWaypoints;
    // The module may only clear a key state it set itself. This keeps idle
    // states, menus and chest registration from cancelling manual movement.
    private boolean ownsForwardKey;
    private double walkPaceAccumulator;
    private long tick;
    private long lastStatusMessageNanos;
    private final MapArtProgressTimer progressTimer = new MapArtProgressTimer();
    private long lastProgressTick;
    private long supplyRetryAt, workRetryAt;
    private int lastVerifiedErrors = -1;
    private final Map<BlockPos, Long> unreachableSupplyUntil = new HashMap<>();
    private long nextActionTick;
    private int cursorSpaceAttempts, cursorSpaceCount = -1;
    private Item cursorSpaceItem;
    private long lastPlacementDispatchTick = -100;
    private long placementStandSince = -1;
    private BlockPos placementStandTarget, footingRecoveryGoal;
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
        super(BuilderToolboxAddon.CATEGORY, "Map Art Maker", "按蛇形条带铺设当前 Litematica 投影层，并从已登记的材料箱自动补货。");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WSection section = theme.section("颜色筛选（勾选后跳过该颜色）", true);
        WHorizontalList controls = section.add(theme.horizontalList()).expandX().widget();
        controls.add(theme.label("启用颜色筛选"));
        WCheckbox enabled = controls.add(theme.checkbox(colorFilterEnabled.get())).widget();
        WLabel status = section.add(theme.label(colorFilterStatus())).widget();
        enabled.action = () -> {
            colorFilterEnabled.set(enabled.checked);
            status.set(colorFilterStatus());
        };
        enabled.tooltip = "默认关闭。关闭筛选后，勾选记录仍会保存。";
        WTable table = section.add(theme.table()).expandX().widget();
        table.horizontalSpacing = 12;
        for (int panel = 0; panel < 2; panel++) {
            table.add(theme.label("色块"));
            table.add(theme.label("颜色"));
            table.add(theme.label("跳过"));
        }
        table.row();
        DyeColor[] colors = DyeColor.values();
        for (int row = 0; row < 8; row++) {
            for (int panel = 0; panel < 2; panel++) {
                DyeColor color = colors[row + panel * 8];
                String id = color.getId();
                ColorSwatch swatch = new ColorSwatch(color);
                swatch.theme = theme;
                table.add(swatch);
                String label = com.buildertoolbox.utils.ChineseSettings.colorTitle(id);
                table.add(theme.label(label));
                WCheckbox skip = table.add(theme.checkbox(excludedColors.get().contains(id))).widget();
                skip.tooltip = "勾选后跳过" + label + "染色方块，已放置的方块会保留。";
                skip.action = () -> {
                    List<String> selected = new ArrayList<>(excludedColors.get());
                    selected.removeIf(id::equals);
                    if (skip.checked) selected.add(id);
                    excludedColors.set(selected);
                    status.set(colorFilterStatus());
                };
            }
            table.row();
        }
        section.add(theme.label("按染色方块的颜色筛选，地图颜色相近的其它方块不在筛选范围内。"));
        return section;
    }

    private String colorFilterStatus() {
        return colorFilterEnabled.get() ? "已启用，排除 " + excludedColors.get().size() + " 种颜色"
            : "未启用，铺设全部颜色（已保存勾选记录）";
    }

    private void queueWorkPlanUpdate() {
        if (isActive()) workPlanDirty = true;
    }

    private boolean isExcludedColor(BlockState blockState) {
        Identifier id = Registries.BLOCK.getId(blockState.getBlock());
        return MapArtColorFilter.excludes(colorFilterEnabled.get(), excludedColors.get(), id.getNamespace(), id.getPath());
    }

    @Override
    public void onActivate() {
        if (!AutomationControl.acquire(this)) { disable(); return; }
        cursorRecovery.reset();
        progressTimer.start(System.nanoTime());
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
        if (!readFlatProjection()) {
            disable();
            return;
        }
        if (remainingTargets.isEmpty()) {
            info("Map Art: all %d included cells already match; %d cells excluded by color.", targets.size(), excludedProjectionCells);
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
        progressTimer.beginDrawing(System.nanoTime(), targets.size(), completedMatrixCells());
        info("Map Art resumed: %d already complete, %d remaining, %d material types.",
            completedMatrixCells(), remainingTargets.size(), supplies.size());
        if (excludedProjectionCells > 0) info("颜色筛选：跳过 %d 格，不计入铺设、补货或完成数量。", excludedProjectionCells);
        info("Working line spacing: %d blocks (%s).", workingBandWidth,
            autoBandWidth.get() ? "automatic safe maximum" : "manual, reach-limited");
    }

    @Override
    public void onDeactivate() {
        restockSurplus.stop(); surplusRestockTarget = null;
        cursorRecovery.reset();
        autoEater.stop();
        if (state != TaskState.IDLE && statusMessages.get()) {
            printProgress("已停止：" + activity, completedMatrixCells(), System.nanoTime(), null);
        }
        releaseMovement();
        cancelQueuedPlacements();
        printer.resetExternalPlacements();
        if (state == TaskState.RESTOCK && mc.player != null && mc.currentScreen instanceof GameMenuScreen) {
            closeSupplyScreen();
        }
        restoreFocusPause();
        resetNavigation();
        if (registerSupply.get()) registerSupply.set(false);
        state = TaskState.IDLE;
        activity = "空闲";
        AutomationControl.release(this);
    }

    @EventHandler
    private void onPlacementPacket(PacketEvent.Receive event) {
        cursorRecovery.onPacket(event.packet);
        if (event.packet instanceof BlockUpdateS2CPacket packet)
            placementReceipts.acknowledge(packet.getPos(),packet.getState());
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet)
            packet.visitUpdates(placementReceipts::acknowledge);
        else if (event.packet instanceof ChunkDataS2CPacket packet)
            receiptChunks.put(net.minecraft.util.math.ChunkPos.toLong(packet.getChunkX(),packet.getChunkZ()),receiptTick+2);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        disable();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player != null && state == TaskState.RESTOCK && supplyOpenTick >= 0 && ownedSupplyHandler == null
            && mc.player.currentScreenHandler != mc.player.playerScreenHandler
            && mc.player.currentScreenHandler.syncId != supplyOpenSyncId
            && (event.screen instanceof GenericContainerScreen))
            ownedSupplyHandler = mc.player.currentScreenHandler;
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
        if (ownedSupplyHandler != null && mc.player.currentScreenHandler != ownedSupplyHandler) {
            ownedSupplyHandler = null; supplyOpenTick = supplyAimTick = -1;
        }
        receiptTick++;
        receiptChunks.forEach((chunk,after) -> {
            int x = (int)(long)chunk, z = (int)((long)chunk >> 32);
            if (receiptTick >= after && mc.world.isChunkLoaded(x,z)) {
                // The newly applied full chunk is authoritative; old receipts
                // must not mask changes made while this chunk was unloaded.
                placementReceipts.invalidateWhere(pos -> pos.getX()>>4 == x && pos.getZ()>>4 == z);
                receiptChunks.remove(chunk,after);
            }
        });

        if (state == TaskState.WORK || state == TaskState.RESTOCK || state == TaskState.VERIFY
            || state == TaskState.WAITING_SUPPLY_RECHECK || state == TaskState.WAITING_CAPACITY) {
            if (cursorRecovery.tick(mc.player.currentScreenHandler == mc.player.playerScreenHandler
                || state == TaskState.RESTOCK && supplyChest != null && supplyOpenTick >= 0
                && mc.player.currentScreenHandler == ownedSupplyHandler,
                stack -> state == TaskState.RESTOCK && supplyItem != null && stack.isOf(supplyItem),
                stack -> state == TaskState.RESTOCK && supplyItem != null && stack.isOf(supplyItem))) {
                releaseMovement();activity = cursorRecovery.status();sendStatusHeartbeat();return;
            }
            if (cursorRecovery.needsSpace() && !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                releaseMovement();
                activity = freeCursorSpace() ? "安全铺设已有材料，为鼠标物品腾出空间"
                    : "保留鼠标物品：附近暂无可安全腾出的空间，请清出一个背包格";
                sendStatusHeartbeat();return;
            }
            if (cursorRecovery.consumeRecovered()) {
                cursorSpaceAttempts = 0; cursorSpaceCount = -1; cursorSpaceItem = null;
                supplyProgressTick = tick;
                info("已收回鼠标物品，继续当前地图画步骤。");
            }
        }
        if (tickAutoEat()) return;
        if (workPlanDirty && state != TaskState.REGISTERING && state != TaskState.IDLE && !projectionLayer.isEmpty()) {
            applyWorkPlanUpdate();
            return;
        }

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

    @EventHandler
    private void onAutoEatCrosshair(ItemUseCrosshairTargetEvent event) {
        if (autoEater.eating()) event.target = null;
    }

    private boolean tickAutoEat() {
        if (state == TaskState.IDLE || state == TaskState.REGISTERING || state == TaskState.PAUSED
            || !autoEater.wanted(autoEat.get())) {
            autoEater.stop();
            return false;
        }
        checkDispatchedPlacements();
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) {
            releaseMovement();
            activity = "自动进食：等待在途放置确认";
            sendStatusHeartbeat();
            return true;
        }
        if (state == TaskState.RESTOCK) {
            // Finish an outstanding transfer/open response before interrupting it.
            if (restockTransferTick >= 0 || supplyOpenTick >= 0
                && mc.player.currentScreenHandler == mc.player.playerScreenHandler) return false;
            if (supplyOpenTick >= 0 && mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                closeSupplyScreen();
                supplyOpenTick = supplyAimTick = -1;
                supplyOpenAttempts = 0;
                supplyOpenSyncId = -1;
            }
        }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler
            || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        releaseMovement();
        if (!autoEater.tick(autoEat.get())) return false;
        // Eating has its own clock; do not charge this time to path/stock timeouts.
        tick--;
        activity = autoEater.status();
        sendStatusHeartbeat();
        return true;
    }

    private void sendStatusHeartbeat() {
        long now = System.nanoTime();
        if (state == TaskState.IDLE || now - lastStatusMessageNanos < 10_000_000_000L) return;
        lastStatusMessageNanos = now;
        int complete = completedMatrixCells();
        String estimate = state == TaskState.PAUSED || state == TaskState.REGISTERING
            ? "预计铺设剩余：暂停工作，暂无法估算"
            : progressTimer.estimate(now, targets.size(), complete);
        if (statusMessages.get()) printProgress(activity, complete, now, estimate);
    }

    private void printProgress(String status, int complete, long now, String estimate) {
        var message = Text.empty()
            .append(Text.literal(status).formatted(state == TaskState.PAUSED ? Formatting.RED : Formatting.AQUA))
            .append(Text.literal(" | 已确认 " + complete + "/" + targets.size()).formatted(Formatting.GREEN))
            .append(Text.literal(" | " + progressTimer.elapsed(now)).formatted(Formatting.YELLOW));
        if (estimate != null) message.append(Text.literal(" | " + estimate).formatted(
            estimate.contains("停滞") || estimate.contains("暂无法") ? Formatting.RED : Formatting.GOLD));
        info(message);
    }

    @Override
    public void info(String message, Object... args) {
        // Send styled Text directly: filenames and user text cannot become formatting tokens.
        super.info(Text.literal(String.format(message, args)).formatted(Formatting.AQUA));
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
        if (isActive() && mc.player != null) mc.setScreen(null);
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
        projectionLayer.clear();
        for (BlockPos pos : positions) {
            BlockState required = schematic.getBlockState(pos);
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            projectionLayer.add(new ProjectionTarget(pos.toImmutable(), required, 0));
        }
        rebuildFilteredMatrix();
        return true;
    }

    private void rebuildFilteredMatrix() {
        cancelQueuedPlacements();
        targets.clear();
        placementMatrix.clear();
        targetIndices.clear();
        remainingTargets.clear();
        dispatchedPlacements.clear();
        blockedTargets.clear();
        excludedProjectionCells = 0;
        for (ProjectionTarget target : projectionLayer) {
            if (isExcludedColor(target.state())) {
                excludedProjectionCells++;
                continue;
            }
            targets.add(target);
            placementMatrix.put(target.pos(), false);
        }
        // Bounds still come from the FULL projection, so excluding a color
        // at its edge never shifts band centers or creates geometric gaps.
        orderTargetsBySnakeBands();
        // Recover from the actual world on every activation, including a
        // full game restart. Completed cells never enter the work queue.
        for (int index = 0; index < targets.size(); index++) {
            ProjectionTarget target = targets.get(index);
            targetIndices.put(target.pos(), index);
            isSatisfied(target);
        }
        targetIndex = remainingTargets.isEmpty() ? targets.size() : remainingTargets.firstKey();
        workPlanDirty = false;
    }

    private void applyWorkPlanUpdate() {
        releaseMovement();
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            activity = "等待背包操作结束后更新蛇形路线和颜色筛选";
            return;
        }
        if (state == TaskState.RESTOCK) closeSupplyScreen();
        cancelQueuedPlacements();
        resetNavigation();
        resetRestockTransfer();
        supplyItem = null;
        supplyAmount = 0;
        supplyChest = null;
        supplyStand = null;
        supplyChestLabel = null;
        reservedSupplySlots = 0;
        supplyReceivedThisRound = 0;
        supplyOpenTick = -1;
        supplyOpenSyncId = -1;
        supplyAimTick = -1;
        returningFromRestock = false;
        restockReturnPos = null;
        supplyCheckedLane = -1;
        workGoalTarget = null;
        verificationIndex = 0;
        verificationPass = 0;
        rebuildFilteredMatrix();
        progressTimer.beginDrawing(System.nanoTime(), targets.size(), completedMatrixCells());
        buildSnakeWaypoints();
        state = TaskState.WORK;
        activity = "施工设置已更新，继续" + (reverseSnake.get() ? "反向" : "正向") + "蛇形铺设";
        info("施工设置已更新：%s蛇形，排除 %d 格，已完成 %d 格，还需铺设 %d 格。",
            reverseSnake.get() ? "反向" : "正向", excludedProjectionCells, completedMatrixCells(), remainingTargets.size());
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
        if (tick < workRetryAt) {
            releaseMovement();
            activity = "第 " + verificationPass + " 轮补扫准备：" + ((workRetryAt-tick+19)/20) + " 秒后继续";
            return;
        }
        if (footingRecoveryGoal != null) {
            NavigationResult recovery = navigateTo(footingRecoveryGoal);
            if (recovery != NavigationResult.MOVING) { footingRecoveryGoal = null; resetNavigation(); }
            activity = "地毯边缘卡位，移动到相邻安全位置后继续";
            return;
        }
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
        if (serviceRestockSurplusForCapacity()) return;
        tickSnakeWork();
    }

    private void buildSnakeWaypoints() {
        snakeWaypoints.clear();
        snakeWaypointLanes.clear();
        snakeEntryWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeSweepCompleted = false;
        snakeSkippedWaypoints = 0;
        if (mc.player == null || targets.isEmpty()) return;
        // Keep the normal route's reference point fixed during this run.
        // Toggling direction after travelling to a chest must not redefine
        // which end is considered the normal start.
        BlockPos start = workOrigin == null ? mc.player.getBlockPos() : workOrigin;
        int width = Math.max(1, workingBandWidth);
        Set<Integer> activeLanes = new HashSet<>();
        for (ProjectionTarget target : remainingTargets.values()) {
            activeLanes.add(target.lane());
        }
        for (MapArtBandGeometry.Sweep sweep : MapArtBandGeometry.snakeSweeps(
            minX, maxX, minZ, maxZ, width, activeLanes, start.getX(), start.getZ(), reverseSnake.get())) {
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
        info("%s蛇形工作线：从%s端开始，单程扫描 %d 个路点。",
            reverseSnake.get() ? "反向" : "正向", reverseSnake.get() ? "正常路线终点" : "正常路线起点", snakeWaypoints.size());
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
        // Supply return is movement-only; keep the computed band materials untouched.
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
        activity = "补货返程：仅行走，回到断点后继续蛇形条带";
    }

    private void finishReturnToWork() {
        returningFromRestock = false;
        restockReturnPos = null;
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
        // Another player can finish a band before we get there. Refresh it
        // from the shared world before deciding to walk or restock for it.
        boolean unfinished = false;
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            if (target.lane() != lane) continue;
            boolean satisfied = isSatisfied(target);
            if (!satisfied && !blockedTargets.contains(target.pos())) unfinished = true;
        }
        return unfinished;
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
            if (!target.pos().equals(placementStandTarget)) { placementStandTarget = target.pos(); placementStandSince = tick; }
            if (tick - placementStandSince >= 40) {
                markPlacementBlocked(target);
                resetNavigation();
                BlockPos feet = new BlockPos(mc.player.getBlockX(),walkY,mc.player.getBlockZ());
                for (Direction direction : HORIZONTAL_DIRECTIONS) {
                    BlockPos candidate = feet.offset(direction);
                    if (isStandUsable(candidate) && Math.abs(standSurfaceY(candidate)-mc.player.getY()) <= 0.6) {
                        footingRecoveryGoal = candidate; break;
                    }
                }
                placementStandTarget = null; placementStandSince = -1;
                warning("站位持续不稳定，暂缓 %s 并尝试挪到相邻安全格，后续蛇形补扫重试。", target.pos().toShortString());
                return;
            }
            activity = "稳定站位后放置";
            return;
        }
        placementStandTarget = null; placementStandSince = -1;
        if (!isWithinPlaceRange(target) || playerIntersectsTarget(target)) {
            workGoalTarget = null;
            return;
        }

        placementReceipts.watch(target.pos().toImmutable(),mc.world.getBlockState(target.pos()));
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

    /** Full bags must be considered before supply eligibility rejects every missing color. */
    private boolean serviceRestockSurplusForCapacity() {
        if (tick < nextSurplusCheckTick || restockSurplus.active() || emptyInventorySlots() > 0
            || printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) return false;
        nextSurplusCheckTick = tick + 20;
        List<List<ProjectionTarget>> bands = upcomingTargetBands();
        if (bands.isEmpty()) return false;
        Map<Item,Integer> demand = new LinkedHashMap<>();
        for (ProjectionTarget target : bands.getFirst()) demand.merge(target.state().getBlock().asItem(), 1, Integer::sum);
        for (ProjectionTarget target : bands.getFirst()) {
            Item item = target.state().getBlock().asItem();
            if (countInventory(item) < demand.get(item) && !supplies.getOrDefault(item,List.of()).isEmpty())
                return beginRestockSurplus(target);
        }
        return false;
    }

    private boolean beginRestockSurplus(ProjectionTarget current) {
        List<List<Item>> bands = upcomingTargetBands().stream().limit(2)
            .map(band -> band.stream().map(target -> target.state().getBlock().asItem()).toList()).toList();
        if (bands.isEmpty()) return false;
        Item item = current.state().getBlock().asItem();
        List<BlockPos> destinations = supplies.getOrDefault(item, List.of());
        BlockPos destination = destinations.stream().min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
        if (!restockSurplus.begin(bands, RestockSurplusController.carpetMaterials(), 0, destination, tick)) return false;
        releaseMovement(); resetNavigation(); resetRestockTransfer();
        if (restockReturnPos == null) restockReturnPos = mc.player.getBlockPos().toImmutable();
        returningFromRestock = false;
        surplusRestockTarget = current;
        supplyChest = supplyStand = null; supplyItem = null;
        supplyOpenTick = supplyAimTick = -1;
        state = TaskState.RESTOCK;
        activity = restockSurplus.status();
        return true;
    }

    private void tickRestockSurplus() {
        releaseMovement();
        boolean working = restockSurplus.tick(tick);
        activity = restockSurplus.status();
        if (working) return;
        ProjectionTarget shortage = surplusRestockTarget;
        surplusRestockTarget = null;
        state = TaskState.WORK;
        if (shortage != null && !remainingTargets.isEmpty()) {
            skipRestockSurplusOnce = true;
            beginRestock(shortage);
        }
    }

    private void beginRestock(ProjectionTarget current) {
        // No inventory sorting: decide directly from the current slots.
        releaseMovement();
        checkDispatchedPlacements();
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) {
            activity = "等待在途放置确认后直接补货";
            return;
        }
        boolean allowDiscard = !skipRestockSurplusOnce;
        skipRestockSurplusOnce = false;
        if (allowDiscard && beginRestockSurplus(current)) return;
        state = TaskState.WORK;
        BlockPos returnPos = restockReturnPos != null ? restockReturnPos : mc.player.getBlockPos().toImmutable();
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
            beginSupplyRecheckWait();
            warning("当前材料暂不可取，将自动重试；有背包材料的条带优先继续，不停止模块。");
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
                .filter(pos -> !isSupplyChestUnavailable(pos))
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
            .filter(pos -> !isSupplyChestUnavailable(pos))
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
            .anyMatch(pos -> !isSupplyChestUnavailable(pos));
    }

    private boolean isSupplyChestTemporarilyEmpty(BlockPos pos) {
        Long until = emptySupplyChestUntil.get(pos);
        if (until == null) return false;
        if (tick < until) return true;
        emptySupplyChestUntil.remove(pos);
        return false;
    }

    private boolean isSupplyChestUnavailable(BlockPos pos) {
        Long until = unreachableSupplyUntil.get(pos);
        if (until != null && tick >= until) { unreachableSupplyUntil.remove(pos); until = null; }
        return isSupplyChestTemporarilyEmpty(pos) || until != null;
    }

    private void deferSupplyChest(String reason) {
        if (supplyChest != null) {
            unreachableSupplyUntil.put(supplyChest.toImmutable(), tick + EMPTY_SUPPLY_RECHECK_TICKS);
            warning("补给箱 %s 暂不可达/操作未确认（%s），冷却60秒，改试同色下一箱；不记为无货。", supplyChest.toShortString(), reason);
        }
        closeSupplyScreen();
        supplyChest = null; supplyStand = null;
        supplyOpenTick = supplyAimTick = -1;
        supplyOpenAttempts = 0;
        resetRestockTransfer(); resetNavigation();
    }

    private boolean resumeStockedBand() {
        if (snakeWaypointIndex >= snakeWaypoints.size()) buildSnakeWaypoints();
        for (int cursor = snakeWaypointIndex; cursor < snakeWaypointLanes.size(); cursor++) {
            int lane = snakeWaypointLanes.get(cursor);
            boolean stocked = false;
            for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
                if (target.lane() == lane && !blockedTargets.contains(target.pos()) && !isSatisfied(target)
                    && hasItem(target.state().getBlock().asItem())) { stocked = true; break; }
            }
            if (!stocked) continue;
            snakeWaypointIndex = cursor; supplyCheckedLane = lane;
            returningFromRestock = false; restockReturnPos = null;
            resetNavigation(); state = TaskState.WORK;
            activity = "补货暂不可用，继续背包有材料的蛇形条带 " + lane;
            return true;
        }
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
        returningFromRestock = false;
        if (didRestock && restockReturnPos != null && !hasReached(restockReturnPos)) {
            if (!isStandUsable(restockReturnPos)) restockReturnPos = findStandNear(restockReturnPos, 2);
            returningFromRestock = restockReturnPos != null;
        } else {
            restockReturnPos = null;
        }
        state = TaskState.WORK;
        activity = returningFromRestock ? "补货结束，仅行走返回蛇形工作位置" : "补货结束，继续铺设";
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
        unreachableSupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        return !emptySupplyUntil.isEmpty() || !shortSupplyUntil.isEmpty() || !unreachableSupplyUntil.isEmpty();
    }

    private void beginSupplyRecheckWait() {
        releaseMovement();
        state = TaskState.WAITING_SUPPLY_RECHECK;
        supplyRetryAt = tick + 20L*10;
        activity = "材料箱暂不可用，自动重试中；有货条带优先继续";
    }

    private void beginCapacityWait() {
        releaseMovement();
        state = TaskState.WAITING_CAPACITY;
        activity = "等待背包空间；箱内有货不记为无货";
    }

    private void tickCapacityWait() {
        if (serviceRestockSurplusForCapacity()) return;
        releaseMovement();
        if (tick % 20 != 0) return;
        if (resumeStockedBand()) return;
        if (!hasPotentialWorkFrom(0)) return;
        state = TaskState.WORK;
        activity = "背包已有可用空间，返回蛇形扫描路点";
    }

    private void tickSupplyRecheckWait() {
        if (tick % 20 == 0 && resumeStockedBand()) return;
        if (hasPendingSupplyRecheck() || tick < supplyRetryAt) {
            long nextCheck = java.util.stream.LongStream.of(
                emptySupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                shortSupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                unreachableSupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                tick < supplyRetryAt ? supplyRetryAt : Long.MAX_VALUE).min().orElse(tick+20);
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
        boolean cleanup = snakeWaypointIndex >= snakeWaypointLanes.size();
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

    private boolean freeCursorSpace() {
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler
            || mc.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
        if (cursorSpaceItem != cursor.getItem() || cursorSpaceCount != cursor.getCount()) {
            cursorSpaceItem = cursor.getItem(); cursorSpaceCount = cursor.getCount(); cursorSpaceAttempts = 0;
        }
        checkDispatchedPlacements();
        if (printer == null || cursorSpaceAttempts >= 3) return false;
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) return true;
        releaseMovement();
        Vec3d velocity = mc.player.getVelocity();
        if (!mc.player.isOnGround() || velocity.x*velocity.x+velocity.z*velocity.z > 0.000625 || tick < nextActionTick) return false;
        ProjectionTarget best = null;
        double nearest = Double.POSITIVE_INFINITY;
        int radius = (int) Math.ceil(placeRange.get());
        BlockPos feet = mc.player.getBlockPos();
        for (int x = feet.getX()-radius; x <= feet.getX()+radius; x++) for (int z = feet.getZ()-radius; z <= feet.getZ()+radius; z++) {
            BlockPos pos = new BlockPos(x,walkY,z);
            Integer index = targetIndices.get(pos);
            if (index == null || blockedTargets.contains(pos) || !mc.world.isChunkLoaded(x>>4,z>>4)) continue;
            ProjectionTarget candidate = targets.get(index);
            if (!mc.world.getBlockState(pos).isAir() || !isWithinPlaceRange(candidate) || playerIntersectsTarget(candidate)) continue;
            boolean freesSpace = false;
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (stack.isOf(candidate.state().getBlock().asItem()) && (stack.getCount() <= 3
                    || ItemStack.areItemsAndComponentsEqual(stack,cursor))) { freesSpace = true; break; }
            }
            if (!freesSpace) continue;
            double distance = mc.player.getEyePos().squaredDistanceTo(placementAim(candidate));
            if (distance < nearest) { nearest = distance; best = candidate; }
        }
        if (best == null) return false;
        placementReceipts.watch(best.pos().toImmutable(),mc.world.getBlockState(best.pos()));
        LitematicaPrinterModule.ExternalPlacementResult result = printer.placeProjectionBlockForCursorRecovery(best.pos(),best.state(),placeRange.get());
        nextActionTick = tick+Math.max(1,(int)Math.ceil(20.0/placementSpeed.get()));
        if (result == LitematicaPrinterModule.ExternalPlacementResult.DISPATCHED) {
            cursorSpaceAttempts++;
            dispatchedPlacements.put(best.pos(),tick);lastPlacementDispatchTick=tick;
        } else if (result == LitematicaPrinterModule.ExternalPlacementResult.BLOCKED) markPlacementBlocked(best);
        return result == LitematicaPrinterModule.ExternalPlacementResult.DISPATCHED;
    }

    private void tickRestock() {
        if (restockSurplus.active()) { tickRestockSurplus(); return; }
        // Interrupted cursor operations are settled by the tick entry point;
        // they never switch this worker into PAUSED.
        if (supplyChest != null) {
            double distance = distanceToPlayer(supplyChest);
            if (distance + 0.25 < supplyBestDistance) {
                supplyBestDistance = distance;
                supplyProgressTick = tick;
            }
            if (tick - supplyProgressTick >= 20L * 30) {
                if (mc.player.currentScreenHandler != mc.player.playerScreenHandler && supplyOpenTick < 0) {
                    releaseMovement();
                    state = TaskState.PAUSED;
                    activity = "补货被其他界面阻挡，请关闭后重新开启功能";
                    warning("%s", activity);
                    return;
                }
                deferSupplyChest("连续30秒无接近或取货进展");
                activity = "当前箱子无进展，切换同色下一箱";
                return;
            }
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
                .filter(pos -> !triedSupplyChests.contains(pos) && !isSupplyChestUnavailable(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer))
                .orElse(null);
            if (nearest == null) {
                if (minimumTakeCapacity.containsKey(supplyItem)) {
                    activity = "箱内有材料，但当前预留背包空间装不下整组；继续其它颜色";
                    warning("%s 的箱内有货，但当前可用背包空间不足；背包腾出空间后再取，不标记为无货。",
                        supplyItem.getName().getString());
                } else if (supplies.getOrDefault(supplyItem,List.of()).stream().anyMatch(pos -> unreachableSupplyUntil.getOrDefault(pos,0L)>tick)) {
                    activity = "同色箱暂不可达，先继续其他颜色，冷却后自动重试";
                    warning("%s 的箱子存在寻路/开箱失败，未记为无货；先处理其他条带。", supplyItem.getName().getString());
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
            supplyProgressTick = tick;
            supplyBestDistance = distanceToPlayer(nearest);
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
        if (opened != null && opened != ownedSupplyHandler) {
            releaseMovement();
            activity = "请关闭手动打开的容器，之后继续补货";
            return;
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
            deferSupplyChest("找不到安全且可交互的站位");
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
            if (supplyOpenAttempts >= 3) {
                deferSupplyChest("三次实际开箱均未确认");
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
            supplyOpenAttempts++;
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
                supplyProgressTick = tick;
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
                deferSupplyChest("取货连续未获服务器确认");
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
                cursorRecovery.rememberOrigin(opened,slot);
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
        releaseMovement(); checkDispatchedPlacements();
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) return;
        if (tick-lastPlacementDispatchTick < PLACEMENT_CONFIRM_TICKS) {
            activity = "等待最后放置的服务器确认后复查";
            return;
        }
        int budget = 512;
        while (budget-- > 0 && verificationIndex < targets.size()) isSatisfied(targets.get(verificationIndex++));
        if (verificationIndex < targets.size()) return;
        int incomplete = targets.size() - completedMatrixCells();
        if (incomplete == 0) {
            info("自动地图画完成：%d 个投影方块已复查，颜色筛选跳过 %d 格。", targets.size(), excludedProjectionCells);
            disable(); return;
        }
        verificationPass++;
        // Retry on another sweep, not by spamming the same cell in place.
        rebuildFilteredMatrix(); buildSnakeWaypoints();
        supplyCheckedLane = -1; verificationIndex = 0;
        workRetryAt = tick + (lastVerifiedErrors >= 0 && incomplete >= lastVerifiedErrors ? 200 : 40);
        lastVerifiedErrors = incomplete;
        state = TaskState.WORK;
        activity = "仍有 " + incomplete + " 格未完成，继续第 " + verificationPass + " 轮蛇形补扫";
        info("%s；已放好的跳过，错放只标记，不自动拆除。", activity);
        if (!hasPotentialWorkFrom(0)) {
            if (!minimumTakeCapacity.isEmpty() || emptyInventorySlots() == 0) beginCapacityWait();
            else beginSupplyRecheckWait();
        }
    }

    private boolean isSatisfied(ProjectionTarget target) {
        boolean satisfied = placementReceipts.observedOr(target.pos(),mc.world.getBlockState(target.pos())).equals(target.state());
        placementMatrix.put(target.pos(), satisfied);
        Integer index = targetIndices.get(target.pos());
        if (index != null) {
            if (satisfied) {
                remainingTargets.remove(index);
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
        var shape = mc.world.getBlockState(pos).getOutlineShape(mc.world,pos);
        if (shape.isEmpty()) return null;
        var bounds = shape.getBoundingBox();
        for (Direction face : Direction.values()) {
            Vec3d point = new Vec3d(pos.getX()+(bounds.minX+bounds.maxX)/2+face.getOffsetX()*(bounds.maxX-bounds.minX)*0.499,
                pos.getY()+(bounds.minY+bounds.maxY)/2+face.getOffsetY()*(bounds.maxY-bounds.minY)*0.499,
                pos.getZ()+(bounds.minZ+bounds.maxZ)/2+face.getOffsetZ()*(bounds.maxZ-bounds.minZ)*0.499);
            if (eye.squaredDistanceTo(point)>4.0*4.0) continue;
            var hit = mc.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.OUTLINE,RaycastContext.FluidHandling.NONE,mc.player));
            if (hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    private BlockPos findSupplyStandNear(BlockPos chest, int radius) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = chest.getX() - radius; x <= chest.getX() + radius; x++) {
            for (int z = chest.getZ() - radius; z <= chest.getZ() + radius; z++) {
                BlockPos stand = new BlockPos(x, walkY, z);
                if (!isStandUsable(stand) || rejectedSupplyStands.contains(stand)) continue;
                Vec3d eye = new Vec3d(x+0.5, standSurfaceY(stand)+mc.player.getEyePos().y-mc.player.getY(), z+0.5);
                if (findContainerHit(chest, eye) != null) candidates.add(stand);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
    }

    private void closeSupplyScreen() {
        if (mc.player == null || mc.player.currentScreenHandler != ownedSupplyHandler) return;
        if (!ownedSupplyHandler.getCursorStack().isEmpty()) return;
        ownedSupplyHandler = null; supplyOpenTick = -1;
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
                if (pendingStep != null) {
                    // A smoothed supply waypoint may be far away. Avoid the
                    // immediate blocked step, not the chest/return endpoint.
                    BlockPos blockedStep = pendingStep;
                    if (isSupplyTravel() && horizontalDistanceSquared(pendingStep) > 2.25) {
                        double dx = pendingStep.getX() + 0.5 - mc.player.getX();
                        double dz = pendingStep.getZ() + 0.5 - mc.player.getZ();
                        double length = Math.hypot(dx, dz);
                        blockedStep = BlockPos.ofFloored(mc.player.getX() + dx / length * 0.9,
                            walkY, mc.player.getZ() + dz / length * 0.9);
                    }
                    if (!blockedStep.equals(goal) && !blockedStep.equals(mc.player.getBlockPos())) avoidedRouteCells.add(blockedStep);
                }
            }
            if (pathAttempts > routeRetries.get()) return NavigationResult.UNREACHABLE;
            pathAttempts++;
            if (!planPath(goal)) return NavigationResult.UNREACHABLE;
        }
        while (pathIndex < path.size() && hasReachedPathStep(path.get(pathIndex))) {
            pathIndex++;
            lastProgressTick = tick;
            lastPathDistance = Double.POSITIVE_INFINITY;
        }
        if (pathIndex >= path.size()) {
            releaseMovement();
            if (hasReached(goal)) return NavigationResult.ARRIVED;
            // Never return MOVING with no remaining step and no movement input.
            path.clear();pathIndex = 0;
            return pathAttempts > routeRetries.get() ? NavigationResult.UNREACHABLE : NavigationResult.MOVING;
        }
        if (isSupplyTravel()) {
            // Shorten a detour to its furthest safely visible near-future
            // waypoint. Do not apply this to the printing serpentine route.
            for (int index = Math.min(path.size() - 1, pathIndex + 32); index > pathIndex; index--) {
                if (!canWalkDirectlyTo(path.get(index))) continue;
                pathIndex = index;
                lastPathDistance = Double.POSITIVE_INFINITY;
                break;
            }
        }
        BlockPos next = path.get(pathIndex);
        if (!isStandUsable(next) || (isSupplyTravel() && !canWalkDirectlyTo(next))) {
            path.clear();
            pathIndex = 0;
            releaseMovement();
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
        if (isSupplyTravel() && canWalkDirectlyTo(goal)) {
            // A single waypoint makes the actual movement diagonal rather
            // than a staircase of four-direction grid steps.
            path.add(goal.toImmutable());
            return true;
        }
        int margin = 4;
        int minPathX = Math.min(Math.min(minX, start.getX()), goal.getX()) - margin;
        int maxPathX = Math.max(Math.max(maxX, start.getX()), goal.getX()) + margin;
        int minPathZ = Math.min(Math.min(minZ, start.getZ()), goal.getZ()) - margin;
        int maxPathZ = Math.max(Math.max(maxZ, start.getZ()), goal.getZ()) + margin;
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Map<BlockPos, Integer> depths = new HashMap<>();
        Map<BlockPos, Integer> scores = new HashMap<>();
        int scanLane = state == TaskState.WORK && !returningFromRestock
            && snakeWaypointIndex < snakeWaypointLanes.size() ? snakeWaypointLanes.get(snakeWaypointIndex) : -1;
        boolean centeredSweep = scanLane >= 0;
        int centerZ = centeredSweep ? MapArtBandGeometry.centerZ(minZ, maxZ, workingBandWidth, scanLane) : 0;
        boolean scoreEqualRoutes = centeredSweep;
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
                int benefit = centeredSweep ? -Math.abs(next.getZ() - centerZ) : 0;
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

    private boolean isSupplyTravel() {
        return state == TaskState.RESTOCK || returningFromRestock;
    }

    private boolean canWalkDirectlyTo(BlockPos goal) {
        if (goal.getY() != walkY) return false;
        return GroundPathVisibility.canWalk(mc.player.getX(), mc.player.getZ(), goal.getX() + 0.5, goal.getZ() + 0.5,
            mc.player.getWidth() / 2.0, 0.6, pathSearchLimit.get(), (x, z) -> {
                BlockPos pos = new BlockPos(x, walkY, z);
                if (avoidedRouteCells.contains(pos) || !mc.world.isChunkLoaded(pos) || !isStandUsable(pos)) return Double.NaN;
                return standSurfaceY(pos);
            });
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

    private boolean hasReached(BlockPos pos) { return reachedPosition(pos, true); }

    private boolean hasReachedPathStep(BlockPos pos) { return reachedPosition(pos, false); }

    private boolean reachedPosition(BlockPos pos, boolean endpoint) {
        return MapArtNavigationPolicy.reached(pos.getX()+0.5-mc.player.getX(), pos.getZ()+0.5-mc.player.getZ(),
            standSurfaceY(pos)-mc.player.getY(), mc.player.isOnGround(), isStandUsable(pos), endpoint);
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
        restockSurplus.stop(); surplusRestockTarget = null; skipRestockSurplusOnce = false; nextSurplusCheckTick = 0;
        ownedSupplyHandler = null;
        cursorSpaceAttempts = 0; cursorSpaceCount = -1; cursorSpaceItem = null;
        placementReceipts.clear();
        receiptChunks.clear(); receiptTick = 0;
        cancelQueuedPlacements();
        printer.resetExternalPlacements();
        walkPaceAccumulator = 0;
        workingBandWidth = 1;
        workOrigin = null;
        workGoalTarget = null;
        restockReturnPos = null;
        returningFromRestock = false;
        supplyCheckedLane = -1;
        targets.clear();
        projectionLayer.clear();
        workPlanDirty = false;
        excludedProjectionCells = 0;
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
        supplyProgressTick = 0;
        supplyBestDistance = Double.POSITIVE_INFINITY;
        snakeWaypoints.clear();
        snakeWaypointLanes.clear();
        snakeEntryWaypoints.clear();
        snakeWaypointIndex = 0;
        snakeSweepCompleted = false;
        snakeSkippedWaypoints = 0;
        tick = 0;
        lastStatusMessageNanos = System.nanoTime();
        lastProgressTick = 0;
        supplyRetryAt = workRetryAt = 0;
        lastVerifiedErrors = -1;
        unreachableSupplyUntil.clear();
        nextActionTick = 0;
        lastPlacementDispatchTick = -100;
        placementStandTarget = footingRecoveryGoal = null;
        placementStandSince = -1;
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

    private static final class ColorSwatch extends WWidget {
        private final Color color;

        private ColorSwatch(DyeColor dye) {
            int rgb = dye.getEntityColor();
            color = new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255);
        }

        @Override
        protected void onCalculateSize() {
            width = height = theme.scale(20);
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            double border = theme.scale(1);
            renderer.quad(x, y, width, height, new Color(150, 150, 150, 255));
            renderer.quad(x + border, y + border, width - 2 * border, height - 2 * border, color);
        }
    }

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
