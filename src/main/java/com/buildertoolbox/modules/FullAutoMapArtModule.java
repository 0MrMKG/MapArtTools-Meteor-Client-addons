package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.fullauto.MapArtAutoEat;
import com.buildertoolbox.fullauto.MapArtNavigationPolicy;
import com.buildertoolbox.fullauto.MapArtProgressTimer;
import com.buildertoolbox.fullauto.CursorSpacePlanner;
import com.buildertoolbox.utils.AutomationControl;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.buildertoolbox.client.events.entity.player.ItemUseCrosshairTargetEvent;
import com.buildertoolbox.fullauto.FullAutoPlacementEngine;
import com.buildertoolbox.fullauto.MapArtModeGuard;
import com.buildertoolbox.fullauto.MapArtBandGeometry;
import com.buildertoolbox.fullauto.GroundPathVisibility;
import com.buildertoolbox.fullauto.MapArtSupplyPlanner;
import com.buildertoolbox.fullauto.MapArtColorFilter;
import com.buildertoolbox.fullauto.MapArtBatchController;
import com.buildertoolbox.fullauto.BatchPainting;
import com.buildertoolbox.fullauto.BatchFiles;
import com.buildertoolbox.fullauto.BatchJournal;
import com.buildertoolbox.fullauto.BatchPrintCheckpoint;
import com.buildertoolbox.fullauto.BatchInspection;
import com.buildertoolbox.fullauto.OpeningBandSupply;
import com.buildertoolbox.fullauto.WalkingProgress;
import com.buildertoolbox.fullauto.SupplyTrip;
import com.buildertoolbox.fullauto.ProducerSupplyReserve;
import com.buildertoolbox.fullauto.SweepStride;
import com.buildertoolbox.fullauto.StationaryRestockWatchdog;
import com.buildertoolbox.fullauto.StorageBlockUtils;
import com.buildertoolbox.utils.RestockSurplusController;
import fi.dy.masa.litematica.data.DataManager;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
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
import com.buildertoolbox.client.events.world.TickEvent;
import com.buildertoolbox.client.events.packets.PacketEvent;
import com.buildertoolbox.client.events.game.GameLeftEvent;
import com.buildertoolbox.client.events.game.OpenScreenEvent;
import com.buildertoolbox.client.events.meteor.KeyEvent;
import com.buildertoolbox.client.events.meteor.MouseClickEvent;
import com.buildertoolbox.client.events.render.Render3DEvent;
import com.buildertoolbox.client.gui.GuiThemes;
import com.buildertoolbox.client.gui.GuiTheme;
import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.widgets.WWidget;
import com.buildertoolbox.client.gui.widgets.WLabel;
import com.buildertoolbox.client.gui.widgets.containers.WSection;
import com.buildertoolbox.client.gui.widgets.containers.WTable;
import com.buildertoolbox.client.gui.widgets.containers.WHorizontalList;
import com.buildertoolbox.client.gui.widgets.containers.WVerticalList;
import com.buildertoolbox.client.gui.widgets.pressable.WButton;
import com.buildertoolbox.client.gui.widgets.pressable.WCheckbox;
import com.buildertoolbox.client.renderer.ShapeMode;
import com.buildertoolbox.client.settings.BoolSetting;
import com.buildertoolbox.client.settings.DoubleSetting;
import com.buildertoolbox.client.settings.IntSetting;
import com.buildertoolbox.client.settings.Setting;
import com.buildertoolbox.client.settings.SettingGroup;
import com.buildertoolbox.client.settings.StringListSetting;
import com.buildertoolbox.client.settings.StringSetting;
import com.buildertoolbox.client.systems.modules.Module;
import com.buildertoolbox.client.utils.misc.input.Input;
import com.buildertoolbox.client.utils.misc.input.KeyAction;
import com.buildertoolbox.client.utils.player.InvUtils;
import com.buildertoolbox.client.utils.render.color.Color;
import com.buildertoolbox.client.utils.world.BlockUtils;
import com.buildertoolbox.client.events.EventHandler;
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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
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
 * Independent source fork for future full-auto map-art workflows.
 * Owns its settings, worker state and private placement engine; its helpers
 * live in com.buildertoolbox.fullauto and never call the original map-art code.
 */
public final class FullAutoMapArtModule extends Module implements MapArtBatchController.Host {
    private static final long EMPTY_SUPPLY_RECHECK_TICKS = 20L * 60L;
    private static final long PRODUCING_CHEST_RECHECK_TICKS = 20L * 5L;
    private static final long PLACEMENT_CONFIRM_TICKS = 20L;
    private static final int RESTOCK_MAX_LOOKAHEAD_LANES = 2;
    private static final int MAX_PENDING_PLACEMENTS = 3;
    private static final int WORK_REGION_SIZE = 8;
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLocations = settings.createGroup("Locations");
    private final SettingGroup sgNavigation = settings.createGroup("Navigation");
    private final Setting<Boolean> autoEat = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Eat")
        .description("饱食度低于四格（8 点）时，从背包中自动进食，吃满后继续工作。")
        .defaultValue(true).build());
    private final MapArtAutoEat autoEater = new MapArtAutoEat(mc, message -> warning("%s", message));
    private final SettingGroup sgBatch = settings.createGroup("Full Auto Batch");
    private final Setting<String> batchFolder = sgBatch.add(new StringSetting.Builder()
        .name("Schematic Folder").description("填写相对于 Litematica 蓝图目录的文件夹名称，支持中文和粘贴。点击“验证并重新初始化”会扫描目录并重置批次，即使文件未改变也会重置。").defaultValue("").build());
    private final Setting<Integer> allowedMissingBlocks = sgBatch.add(new IntSetting.Builder()
        .name("Allowed Missing Blocks")
        .description("漏放格数不超过此值时完成补铺。设为 0 表示所有未排除的位置都必须铺好。错放不计入漏放，未读取区域仍需检查。可直接调整，无须重建批次。")
        .defaultValue(0).range(0, 16384).sliderRange(0, 100).build());
    private final Setting<String> canvasOrigin = batchPosition("Canvas Origin", "右键方块的 X、Y+1、Z 作为画布原点。蓝图须为相对高度 Y=0 的单层 128×128 地图画。");
    private final Setting<Boolean> selectCanvas = batchSelector("Set Canvas Origin", BatchLocation.CANVAS);
    private final Setting<String> clearLever = batchPosition("Clear Lever", "用于两次触发出水和收水的拉杆坐标。");
    private final Setting<Boolean> selectLever = batchSelector("Set Clear Lever", BatchLocation.LEVER);
    private final Setting<String> mapSupply = batchPosition("Map Supply", "存放空地图和普通玻璃板的容器位置，无须物品标签。");
    private final Setting<Boolean> selectMaps = batchSelector("Set Map Supply", BatchLocation.MAPS);
    private final Setting<String> cartographyTable = batchPosition("Cartography Table", "锁定地图所用制图台的坐标。");
    private final Setting<Boolean> selectCartography = batchSelector("Set Cartography Table", BatchLocation.TABLE);
    private final Setting<String> productStorage = batchPosition("Product Storage", "存放本批次锁定地图的容器位置，无须物品标签。");
    private final Setting<Boolean> selectProduct = batchSelector("Set Product Storage", BatchLocation.PRODUCT);
    private final Setting<Integer> resetStartMap = sgBatch.add(new IntSetting.Builder()
        .name("Reset Start Map")
        .description("按文件名自然排序后，从此序号开始重建，包含这一张，序号从 1 开始。仅在重建批次或验证目录时生效，启动后会先清理并重新绘制所选地图。")
        .defaultValue(1).range(1, 100000).sliderRange(1, 100).build());
    private final Setting<Boolean> resetBatch = sgBatch.add(new BoolSetting.Builder()
        .name("Reset Batch").description("验证目录，并从“重建起始地图序号”开始建立新批次。旧断点会归档，重新开启功能后才操作拉杆。")
        .defaultValue(false).onChanged(this::onBatchReset).build());

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
        .onChanged(value -> queueWorkPlanUpdate())
        .build()
    );

    private final Setting<Integer> laneWidth = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Band Width")
        .description("关闭自动条带宽度后使用此值，实际宽度仍受安全放置距离限制。")
        .defaultValue(5)
        .range(1, 16)
        .sliderRange(1, 8)
        .onChanged(value -> queueWorkPlanUpdate())
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
        .onChanged(value -> queueWorkPlanUpdate())
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

    private final Setting<Boolean> sprintToSupply = sgNavigation.add(new BoolSetting.Builder()
        .name("Sprint To Supply")
        .description("前往材料箱补货时，在安全路段疾跑。铺设和补货返回时正常行走。")
        .defaultValue(true)
        .onChanged(enabled -> { if (!enabled) releaseSupplySprint(); })
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
    // to unconfirmed sources for every target while allowing a later retry.
    private final Map<Item, Long> emptySupplyUntil = new HashMap<>();
    // A matching stack can exist but be too large for the currently unreserved
    // inventory space. Capacity failures are not chest availability failures.
    private final Map<Item, Integer> minimumTakeCapacity = new HashMap<>();
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> avoidedRouteCells = new HashSet<>();
    private final List<BlockPos> snakeWaypoints = new ArrayList<>();
    private final List<Integer> snakeWaypointLanes = new ArrayList<>();
    private final Set<Integer> snakeEntryWaypoints = new HashSet<>();
    private final Set<BlockPos> rejectedSupplyStands = new HashSet<>();
    private final SupplyTrip<Item, BlockPos> supplyTrip = new SupplyTrip<>();
    private Map<Item, Integer> restockBandDemand = Map.of();

    private final FullAutoPlacementEngine printer = new FullAutoPlacementEngine();
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
    private long supplyLastTakeTick = -1;
    private BlockPos supplyChest;
    private Item supplyChestLabel;
    private BlockPos supplyStand;
    private BlockPos navigationGoal;
    private ProjectionTarget workGoalTarget;
    private BlockPos restockReturnPos;
    private boolean returningFromRestock;
    private int supplyCheckedLane = -1;
    private long nextLocalSupplyCheckTick;
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
    private final StationaryRestockWatchdog stationaryRestock = new StationaryRestockWatchdog();
    private int stationaryRestockRecoveries;
    private int supplyOpenSyncId = -1;
    private volatile int supplyInventorySyncId = -1;
    private long supplyInventoryReadyTick = -1;
    private net.minecraft.screen.ScreenHandler ownedSupplyHandler;
    private int snakeWaypointIndex;
    private boolean snakeSweepCompleted;
    private int snakeSkippedWaypoints;
    // The module may only clear a key state it set itself. This keeps idle
    // states, menus and chest registration from cancelling manual movement.
    private boolean ownsForwardKey;
    private boolean ownsSupplySprintKey;
    private long tick;
    private long lastStatusMessageNanos;
    private final MapArtProgressTimer progressTimer = new MapArtProgressTimer();
    private long lastProgressTick;
    private long supplyRetryAt;
    private final Map<BlockPos, Long> unreachableSupplyUntil = new HashMap<>();
    private final ProducerSupplyReserve<BlockPos> producerSupplyReserve = new ProducerSupplyReserve<>();
    private long nextActionTick;
    private int cursorSpaceAttempts, cursorSpaceCount = -1;
    private Item cursorSpaceItem;
    private ProjectionTarget cursorSpaceTarget;
    private BlockPos cursorSpaceOrigin, cursorSpaceStand;
    private boolean cursorSpaceReturning;
    private long cursorSpacePlanAt;
    private final Set<BlockPos> cursorSpaceRejected = new HashSet<>();
    private final Set<BlockPos> cursorSpaceRejectedStands = new HashSet<>();
    private boolean reviewingRepair;
    private final Set<BlockPos> reviewAttempted = new HashSet<>();
    private int reviewPass;
    private boolean reviewRestocking;
    private final Set<Item> reviewSupplyTried = new HashSet<>();
    private long lastPlacementDispatchTick = -100;
    private long placementStandSince = -1;
    private BlockPos placementStandTarget, footingRecoveryGoal;
    private ProjectionTarget preciseRepairTarget;
    private BlockPos preciseRepairStand;
    private long preciseRepairStarted;
    private final Set<BlockPos> rejectedRepairStands = new HashSet<>();
    private final Map<BlockPos, Integer> repairVisits = new HashMap<>();
    private final Set<BlockPos> deferredBandRepairs = new HashSet<>();
    private final Map<Integer, Set<Item>> bandSupplyAttempts = new HashMap<>();
    // A fixed outward/return pair, not a sliding window that keeps chasing distant stock.
    private final List<Integer> supplyWindowLanes = new ArrayList<>();
    private final OpeningBandSupply openingBands = new OpeningBandSupply();
    private BlockPos boundaryEntryStand;
    private BlockPos sweepEndpointStand;
    private int observationCursor;
    private int finishingLane = -1;
    private BlockPos bandFinishOrigin;
    private final Set<BlockPos> deferredResumeTargets = new HashSet<>();
    private boolean resumeSupplyPending;
    private boolean returningToCheckpoint;
    private BlockPos navigationNudge, unloadedTravelGoal, unloadedTravelProxy;
    private long nudgeUntil;
    private long navigationReleaseUntil;
    private final WalkingProgress walkingProgress = new WalkingProgress();
    private long lastMovementReleaseTick = -100;
    private final SweepStride sweepStride = new SweepStride();
    private boolean sweepCollecting;
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
    private final MapArtBatchController batch = new MapArtBatchController(this, mc);
    private final RestockSurplusController restockSurplus = new RestockSurplusController(mc);
    private ProjectionTarget surplusRestockTarget;
    private boolean skipRestockSurplusOnce;
    private long nextSurplusCheckTick;

    private BatchLocation batchLocation = BatchLocation.NONE;
    private boolean validatingDirectory;
    private long directoryValidationGeneration;
    private String directoryValidationStatus = "尚未验证目录";

    public FullAutoMapArtModule() {
        super(BuilderToolboxAddon.CATEGORY, "map-artmaker-all-auto", "依次读取文件夹中的蓝图，自动清理画布、蛇形铺设、生成地图、锁图并存入成品箱。");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList root = theme.verticalList();
        WSection validation = root.add(theme.section("蓝图目录验证", true)).expandX().widget();
        WLabel validationStatus = validation.add(theme.label(directoryValidationStatus)).widget();
        WButton validate = validation.add(theme.button("验证并重新初始化")).widget();
        validate.tooltip = "停止当前任务，验证目录，并从指定起始地图序号重建批次，包含所选地图。";
        validate.action = () -> validateBatchDirectory(validationStatus);
        validation.add(theme.label("填写相对于蓝图目录的路径，例如“地图画/风景合集”，支持中文和 Ctrl+V 粘贴。"));
        validation.add(theme.label("先设置上方起始序号，从 1 开始并包含该张。验证会重置进度，完成后重新开启功能。"));
        validation.add(theme.label("起始序号之前的地图会跳过，所选地图会清理画布后重新绘制。"));
        validation.add(theme.label("此处检查文件能否读取，蓝图尺寸和地图网格对齐会在批次启动时检查。"));
        WSection section = theme.section("颜色筛选（勾选后跳过该颜色）", true);
        root.add(section).expandX();
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
        return root;
    }

    private void validateBatchDirectory(WLabel label) {
        if (validatingDirectory) {
            if (label != null) label.set(directoryValidationStatus);
            return;
        }
        String input = batchFolder.get();
        int firstMap = resetStartMap.get();
        Path base = DataManager.getSchematicsBaseDirectory();
        String context = directoryValidationContext();
        long request = ++directoryValidationGeneration;
        validatingDirectory = true;
        // Quiesce before the asynchronous read. No old job may advance while a
        // successful validation is preparing to replace its checkpoint.
        if (isActive()) disable();
        directoryValidationStatus = "已停止当前批次，正在检测目录并准备重新初始化…";
        if (label != null) label.set(directoryValidationStatus);
        CompletableFuture.supplyAsync(() -> {
            try {
                Path folder = BatchFiles.directory(base, input);
                List<BatchFiles.Entry> files = BatchFiles.inspectDirectory(folder);
                if (!files.isEmpty()) BatchJournal.startingAt(files, firstMap);
                return new DirectoryValidation(folder, files);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((result, failure) -> mc.execute(() -> {
            if (request != directoryValidationGeneration) return;
            validatingDirectory = false;
            if (!input.equals(batchFolder.get()) || firstMap != resetStartMap.get() || !context.equals(directoryValidationContext())) {
                directoryValidationStatus = "目录、配置或游戏环境已变化，请重新检测；旧进度保留";
            } else if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                directoryValidationStatus = "目录验证失败；旧进度保留，详见聊天提示";
                warning("目录验证失败：%s", cause.getMessage() == null ? cause.toString() : cause.getMessage());
            } else if (result.files().isEmpty()) {
                directoryValidationStatus = "发现 0 个 .litematic 文件；未重新初始化，旧进度保留";
                warning("%s", directoryValidationStatus);
            } else if (!batch.reset(result.folder(), result.files(), firstMap)) {
                directoryValidationStatus = "目录可读：" + result.files().size() + " 张；初始化未执行，详见批次提示";
            } else {
                resetRuntime();
                state = TaskState.IDLE;
                activity = "已重新初始化，开启模块从第 " + firstMap + " 张开始（包含）";
                directoryValidationStatus = "已重新初始化：第 " + firstMap + " / " + result.files().size()
                    + " 张（含）：" + result.files().get(firstMap - 1).name();
                info("%s；路径：%s。相同文件也按新批次处理，旧记录已归档。", directoryValidationStatus, result.folder());
                if (!result.files().isEmpty()) info("自然排序预览：%s%s",
                    result.files().stream().limit(5).map(BatchFiles.Entry::name).collect(java.util.stream.Collectors.joining(", ")),
                    result.files().size() > 5 ? " …" : "");
            }
            if (label != null) label.set(directoryValidationStatus);
        }));
    }

    private record DirectoryValidation(Path folder, List<BatchFiles.Entry> files) { }

    private String directoryValidationContext() {
        String world = mc.getServer() != null
            ? "local:" + mc.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath()
            : mc.getCurrentServerEntry() == null ? "no-server" : "server:" + mc.getCurrentServerEntry().address;
        return batchConfiguration().signature() + "|" + world + "|"
            + (mc.world == null ? "no-world" : mc.world.getRegistryKey().getValue()) + "|"
            + (mc.player == null ? "no-player" : mc.player.getUuidAsString());
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
        if (validatingDirectory) {
            warning("正在重新检测目录并初始化，请完成后再开启模块。");
            disable();return;
        }
        progressTimer.start(System.nanoTime());
        if (!MapArtModeGuard.begin(this)) {
            warning("原地图画模块正在运行，请先关闭原模式再开启全自动模式。");
            disable();
            return;
        }
        if (!AutomationControl.acquire(this)) { disable(); return; }
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
        if (registerSupply.get() || batchLocation != BatchLocation.NONE) {
            state = TaskState.REGISTERING;
            startAfterRegistration = false;
            activity = "Select chests: right-click to add, Esc to exit";
            return;
        }
        state = TaskState.BATCH;
        batch.startOrResume();
    }

    private void startPrinting() {
        state = TaskState.BATCH;
        batch.startOrResume();
    }

    @Override
    public void onDeactivate() {
        autoEater.stop();
        MapArtModeGuard.end(this);
        batch.stop();
        restockSurplus.stop(); surplusRestockTarget = null;
        clearBatchSelectors();
        if (state != TaskState.IDLE && statusMessages.get()) {
            printProgress("已停止：" + activity, completedMatrixCells(), System.nanoTime(), null);
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
        AutomationControl.release(this);
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
            && (event.screen instanceof GenericContainerScreen || event.screen instanceof ShulkerBoxScreen))
            ownedSupplyHandler = mc.player.currentScreenHandler;
        // Esc remains a normal menu. Only our own supply UI stays behind it;
        // the server's handler is installed before this screen event fires.
        if (!(mc.currentScreen instanceof GameMenuScreen)) return;
        if (batch.closingMenu() && event.screen == null) { event.cancel(); return; }
        if (batch.hidesScreen(event.screen)) { event.cancel(); return; }
        if (closingSupplyBehindMenu && event.screen == null) event.cancel();
        else if (state == TaskState.RESTOCK && supplyChest != null && supplyOpenTick >= 0
            && (event.screen instanceof GenericContainerScreen || event.screen instanceof ShulkerBoxScreen)) event.cancel();
    }

    /** Used by the menu pause hook; never changes pause behavior for an idle module. */
    public boolean runsWithGameMenu() {
        return isActive() && mc.world != null && mc.player != null
            && batch.running() && state != TaskState.REGISTERING && state != TaskState.PAUSED;
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
        walkingProgress.sample(tick,mc.player.getX(),mc.player.getZ(),ownsForwardKey);
        if (batch.printing()) refreshProjectionObservations(256);
        if (ownedSupplyHandler != null && mc.player.currentScreenHandler != ownedSupplyHandler) {
            ownedSupplyHandler = null; supplyOpenTick = supplyAimTick = -1;
        }
        if (recoverStationaryRestock()) {
            sendStatusHeartbeat();
            return;
        }

        if (state == TaskState.REGISTERING) {
            autoEater.stop();
            tickRegistration();
            return;
        }
        if (tickAutoEat()) return;
        if (batch.tick()) {
            sendStatusHeartbeat();
            return;
        }

        if (workPlanDirty && batch.printing() && !projectionLayer.isEmpty()) {
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
            case BATCH -> { }
        }
        sendStatusHeartbeat();
    }

    @EventHandler
    private void onAutoEatCrosshair(ItemUseCrosshairTargetEvent event) {
        if (autoEater.eating()) event.target = null;
    }

    private boolean tickAutoEat() {
        if (state == TaskState.IDLE || state == TaskState.REGISTERING || state == TaskState.PAUSED || !batch.allowsAutoEat()
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
        batch.holdForAutoEat();
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
        String estimate = !batch.running() || state == TaskState.PAUSED
            ? "预计铺设剩余：暂停工作，暂无法估算"
            : batch.printing() ? "当前图：" + progressTimer.estimate(now, targets.size(), complete)
            : "预计铺设剩余：当前处于清理、复查或制图流程";
        if (statusMessages.get()) printProgress(
            activity.equals(batch.status()) ? batch.stageStatus() : batch.stageStatus() + " " + activity, complete, now, estimate);
    }

    private void printProgress(String status, int complete, long now, String estimate) {
        status = status.replace(batch.queueDescription() + " ", "");
        var message = Text.empty()
            .append(Text.literal(batch.queueDescription()).formatted(Formatting.LIGHT_PURPLE))
            .append(Text.literal(" | " + status).formatted(state == TaskState.PAUSED ? Formatting.RED : Formatting.AQUA))
            .append(Text.literal(" | 已确认 " + complete + "/" + targets.size()).formatted(Formatting.GREEN))
            .append(Text.literal(" | " + batch.elapsedStatus()).formatted(Formatting.YELLOW));
        if (estimate != null) message.append(Text.literal(" | " + estimate).formatted(
            estimate.contains("停滞") || estimate.contains("暂无法") ? Formatting.RED : Formatting.GOLD));
        info(message);
    }

    @Override
    public void info(String message, Object... args) {
        super.info(Text.literal(String.format(message, args)).formatted(Formatting.AQUA));
    }

    private void tickRegistration() {
        if (batchLocation != BatchLocation.NONE) {
            activity = "右键登记 " + batchLocation + "，Esc 返回设置";
            return;
        }
        if (registerSupply.get()) {
            activity = "选择材料箱：右键添加多个箱子，Esc 返回";
            return;
        }
        if (startAfterRegistration) {
            startAfterRegistration = false;
            startPrinting();
        } else {
            state = TaskState.PAUSED;
            activity = "位置已保存，重新开启本模块即可自动开始或恢复批次";
        }
    }

    private void onRegisterSupplyChanged(boolean enabled) {
        if (!enabled) return;
        beginChestSelection();
    }

    private void beginChestSelection() {
        if (mc.player == null || mc.world == null || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            registerSupply.set(false);
            warning("请先进入世界并放回鼠标持有的物品，再选择箱子"); return;
        }
        batch.stop();
        restockSurplus.stop(); surplusRestockTarget = null;
        if (state == TaskState.RESTOCK) closeSupplyScreen();
        clearBatchSelectors();
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
        if (batchLocation != BatchLocation.NONE && isActive() && mc.player != null && mc.world != null
            && mc.currentScreen == null && event.action == KeyAction.Press && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.cancel();
            selectBatchPosition();
            return;
        }
        if (!isActive() || !registerSupply.get() || mc.player == null || mc.world == null
            || mc.currentScreen != null
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
        if (batchLocation != BatchLocation.NONE && isActive() && event.action == KeyAction.Press && event.key() == GLFW.GLFW_KEY_ESCAPE) {
            event.cancel();clearBatchSelectors();state=TaskState.PAUSED;
            activity="位置已保存，重新开启本模块即可自动开始或恢复批次";
            mc.setScreen(GuiThemes.get().moduleScreen(this));return;
        }
        if (!isActive() || !registerSupply.get() || event.action != KeyAction.Press
            || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        exitRegistration();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || mc.world == null || mc.player == null) return;
        if (batchLocation != BatchLocation.NONE) {
            for (Setting<String> setting : List.of(canvasOrigin, clearLever, mapSupply, cartographyTable, productStorage)) {
                BlockPos pos=StorageBlockUtils.parsePosition(setting.get());
                if(pos!=null)event.renderer.box(pos,null,new Color(255,220,45,255),ShapeMode.Lines,0);
            }
            return;
        }
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
        minimumTakeCapacity.remove(item);
        supplyTrip.forget(container);
        updateSupplyList();
        info("Supply chest registered: %s -> %s", item.getName().getString(), container.toShortString());
    }

    private void exitRegistration() {
        registerSupply.set(false);
        state = TaskState.PAUSED;
        activity = "位置已保存，重新开启本模块即可自动开始或恢复批次";
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
        minimumTakeCapacity.clear();
        supplyTrip.clear();
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
        restockSurplus.stop(); surplusRestockTarget = null; skipRestockSurplusOnce = false;
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
        supplyLastTakeTick = -1;
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
        clearPreciseRepair();finishingLane=-1;openingBands.reset();boundaryEntryStand=sweepEndpointStand=null;
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
        if (serviceNavigationRecovery()) return;
        if (resumeSupplyPending) {
            resumeSupplyPending = false;
            serviceSupplyWindowBoundary();
            ProjectionTarget shortage = findRestockTargetInWindow();
            if (shortage != null) {
                // Recompute inventory demand, but keep the saved work position
                // throughout the supply trip and any subsequent restart.
                beginRestock(shortage);
                if (state != TaskState.WORK) return;
            }
        }
        if (returningFromRestock) {
            tickReturnFromRestock();
            return;
        }
        if (serviceRestockSurplusForCapacity()) return;
        if (serviceOpeningBands()) return;
        if (finishingLane >= 0) {
            int lane = finishingLane;
            if (!finishSnakeBand(lane)) skipSnakeLane(lane);
            return;
        }
        tickSnakeWork();
    }

    private void buildSnakeWaypoints() {
        sweepCollecting = false;
        deferredResumeTargets.clear();
        resumeSupplyPending = returningToCheckpoint = false;
        supplyWindowLanes.clear();
        boundaryEntryStand=null;
        sweepEndpointStand=null;
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
        info("%s蛇形工作线：从%s端开始，实际带宽 %d 格（%s），单程扫描 %d 个路点。",
            reverseSnake.get() ? "反向" : "正向", reverseSnake.get() ? "正常路线终点" : "正常路线起点",
            workingBandWidth,autoBandWidth.get()?"自动最大安全宽度":"手动宽度",snakeWaypoints.size());
        openingBands.start(snakeWaypointLanes);
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
        NavigationResult route = navigateTo(restockReturnPos);
        if (route == NavigationResult.ARRIVED || route == NavigationResult.UNREACHABLE) {
            finishReturnToWork();
            return;
        }
        activity = returningToCheckpoint
            ? "断点续铺：返回 " + restockReturnPos.toShortString() + "，继续原蛇形条带"
            : "补货返程：仅行走，回到断点后继续蛇形条带";
    }

    private void finishReturnToWork() {
        returningToCheckpoint = false;
        returningFromRestock = false;
        restockReturnPos = null;
        resetNavigation();
        activity = "返程结束，继续蛇形工作线";
    }

    private void tickSnakeWork() {
        if (serviceSupplyWindowBoundary()) return;
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
            if (holdForPlacementConfirmation()) return;
            skipSnakeLane(activeLane);
            activity = "当前扫描带已核对，转向下一带";
            return;
        }
        // Prepare each entry once. During a sweep, replenish a missing nearby
        // color before leaving its cells behind, even if other colors remain.
        if (activeLane != null && activeLane >= 0 && dispatchedPlacements.isEmpty()) {
            boolean entering = supplyCheckedLane != activeLane && snakeEntryWaypoints.contains(snakeWaypointIndex);
            if (entering || tick >= nextLocalSupplyCheckTick) {
                if (entering) supplyCheckedLane = activeLane;
                nextLocalSupplyCheckTick = tick + 5;
                // During a sweep, use reachable stock before interrupting the
                // centerline for a missing color in the same few columns.
                ProjectionTarget shortage = entering || selectReachableMatrixTarget(activeLane) == null
                    ? findRestockTargetInWindow(activeLane,!entering,!entering) : null;
                if (shortage != null) {
                    nextLocalSupplyCheckTick = tick + 40;
                    beginRestock(shortage);
                    if (state != TaskState.WORK || returningFromRestock) return;
                }
            }
        }
        // If the whole pair is out, try any obtainable color in the pair.
        if (activeLane != null && activeLane >= 0 && dispatchedPlacements.isEmpty()
            && findStockedWindowTarget() == null) {
            ProjectionTarget missing = findRestockTargetInWindow();
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
        if (nearby == null) sweepCollecting = false;
        if (nearby != null && (sweepCollecting || !canContinueSweepStride(activeLane))) {
            // Finish this stop before starting another stride. Repeatedly
            // priming navigation here would keep resetting click stability.
            sweepCollecting = true;
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return;
        }
        if (holdForPlacementConfirmation()) return;
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
        BlockPos waypoint = snakeWaypoints.get(snakeWaypointIndex);
        // Like Once, keep one continuous centerline goal per band. Nearby cells
        // are placed above; do not interrupt travel with periodic repair stops.
        NavigationResult result = navigateTo(sweepEndpointStand == null ? waypoint : sweepEndpointStand);
        if (result == NavigationResult.MOVING && ownsForwardKey) {
            sweepStride.begin(mc.player.getX(), mc.player.getZ(), tick);
        }
        if (result == NavigationResult.UNREACHABLE && sweepEndpointStand == null) {
            resetNavigation();
            sweepEndpointStand = findSafeEdgeStand(waypoint, 2);
            if (sweepEndpointStand != null) {
                activity = "条带端点受阻，改走附近可达站位 " + sweepEndpointStand.toShortString();
                return;
            }
        }
        if (result == NavigationResult.ARRIVED || result == NavigationResult.UNREACHABLE) {
            if (activeLane != null && finishSnakeBand(activeLane)) return;
            if (result == NavigationResult.UNREACHABLE) {
                snakeSkippedWaypoints++;
                resetNavigation();
                if (snakeSkippedWaypoints <= 3 || snakeSkippedWaypoints % 16 == 0) {
                    warning("蛇形路点 %s 不可达；已记录，扫描后将按剩余矩阵补扫。", waypoint.toShortString());
                }
            }
            sweepEndpointStand=null;
            snakeWaypointIndex++;
        }
        activity = "蛇形扫描：" + Math.min(snakeWaypointIndex + 1, snakeWaypoints.size())
            + "/" + snakeWaypoints.size() + " 路点";
    }

    /** Continue straight travel until unfinished cells approach the rear reach limit. */
    private boolean canContinueSweepStride(Integer lane) {
        if (lane == null || lane < 0 || sweepEndpointStand != null || !mc.player.isOnGround()
            || !dispatchedPlacements.isEmpty() || printer.hasQueuedProjectionPlacement()
            || snakeWaypointIndex >= snakeWaypoints.size()) return false;
        BlockPos goal = snakeWaypoints.get(snakeWaypointIndex);
        // Only optimize straight centerline travel. Turns, obstacle detours,
        // recovery nudges and endpoints retain the existing stop/place flow.
        double dx = goal.getX() + 0.5 - mc.player.getX();
        if (Math.abs(dx) <= 0.4 || Math.abs(mc.player.getZ() - goal.getZ() - 0.5) > 0.2) return false;
        // At a band entry the previous turn has released movement. Start the
        // same normal navigation before deciding whether a larger stop fits.
        if (!ownsForwardKey || pathIndex >= path.size()) {
            if (navigateTo(goal) != NavigationResult.MOVING || !ownsForwardKey) return false;
            sweepStride.begin(mc.player.getX(), mc.player.getZ(), tick);
        }
        double remaining = sweepStride.remaining(mc.player.getX(), mc.player.getZ(), tick);
        if (remaining <= 0 || pathIndex >= path.size()) return false;
        advanceStraightSweepPath(goal);
        BlockPos next = path.get(pathIndex);
        if (next.getZ() != goal.getZ() || (next.getX()+0.5-mc.player.getX()) * dx <= 0
            || !canWalkDirectlyTo(next)) return false;
        Vec3d eye = mc.player.getEyePos();
        // Test the next movement plus braking distance, not the entire stride.
        // A cell may stop a longer stride early without cancelling all safe
        // progress toward the stand that covers both front and rear rows.
        double advance = Math.min(remaining, Math.max(0.1, Math.abs(mc.player.getVelocity().x) * 2)) + 0.3;
        Vec3d futureEye = new Vec3d(eye.x + Math.copySign(advance, dx), eye.y, goal.getZ()+0.5);
        int radius = (int) Math.ceil(placeRange.get());
        BlockPos feet = mc.player.getBlockPos();
        for (int x=feet.getX()-radius; x<=feet.getX()+radius; x++) {
            for (int z=feet.getZ()-radius; z<=feet.getZ()+radius; z++) {
                Integer index = targetIndices.get(new BlockPos(x,walkY,z));
                ProjectionTarget target = index == null ? null : remainingTargets.get(index);
                if (target == null || target.lane() != lane || blockedTargets.contains(target.pos())) continue;
                Vec3d aim = placementAim(target);
                if (SweepStride.wouldLeaveReach(eye.squaredDistanceTo(aim), futureEye.squaredDistanceTo(aim), placeRange.get())
                    && !isSatisfied(target)) return false;
            }
        }
        return true;
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
        int lane = snakeWaypointLanes.get(snakeWaypointIndex);
        BlockPos entry = snakeWaypoints.get(snakeWaypointIndex);
        NavigationResult result = navigateTo(boundaryEntryStand == null ? entry : boundaryEntryStand);
        if (result == NavigationResult.ARRIVED && boundaryEntryStand == null && !hasReachedPathStep(entry)) {
            // Chest arrival allows 0.6 blocks, but starting a five-row sweep
            // that far off its centerline can leave an outside row untouched.
            if (!walkingProgress.stalled(12) && canWalkDirectlyTo(entry)) {
                faceToward(entry);pressForward();activity="对齐条带中线后开始铺设";return;
            }
            result = NavigationResult.UNREACHABLE;
        }
        if (result == NavigationResult.ARRIVED) {
            boundaryEntryStand=null;
            snakeWaypointIndex++;
            resetNavigation();
        } else if (result == NavigationResult.UNREACHABLE) {
            if (boundaryEntryStand == null) {
                resetNavigation();
                boundaryEntryStand=findSafeEdgeStand(entry,2);
                if (boundaryEntryStand != null) {
                    activity="条带入口受阻，改从附近安全位置进入";return;
                }
            }
            boundaryEntryStand=null;
            // A bad corner must not discard an otherwise buildable whole band.
            if (finishSnakeBand(snakeWaypointLanes.get(snakeWaypointIndex))) return;
            snakeSkippedWaypoints++;
            warning("条带入口及本带换位补铺已尝试，剩余缺口保留给定点补检。");
            skipSnakeLane(snakeWaypointLanes.get(snakeWaypointIndex));
        }
        activity = "蛇形转弯：前往下一条中线端点";
    }

    private void skipSnakeLane(int lane) {
        sweepCollecting = false;
        resetNavigation();
        boundaryEntryStand=null;
        sweepEndpointStand=null;
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
        int bestSwitchCost = Integer.MAX_VALUE;
        Item heldItem = mc.player.getMainHandStack().getItem();
        Set<Item> hotbarItems = new HashSet<>();
        for (int slot = 0; slot < 9; slot++) hotbarItems.add(mc.player.getInventory().getStack(slot).getItem());
        double rangeSquared = placeRange.get() * placeRange.get();
        Vec3d eye = mc.player.getEyePos();
        // Look up only coordinates near the player, not the whole blueprint.
        int radius = (int) Math.ceil(placeRange.get());
        BlockPos feet = mc.player.getBlockPos();
        boolean bareForwardSweep = requiredLane != null && finishingLane < 0 && state == TaskState.WORK
            && !returningFromRestock && snakeWaypointIndex < snakeWaypointLanes.size()
            && requiredLane.equals(snakeWaypointLanes.get(snakeWaypointIndex))
            && !snakeEntryWaypoints.contains(snakeWaypointIndex) && mc.player.isOnGround()
            && mc.player.getY() <= walkY+0.03 && feet.getY() == walkY
            && mc.world.getBlockState(feet).getCollisionShape(mc.world,feet).isEmpty();
        int sweepDirection=1;
        if (bareForwardSweep) {
            int entry=snakeWaypointIndex;
            while(entry>0 && snakeWaypointLanes.get(entry-1).equals(requiredLane)) entry--;
            sweepDirection=snakeWaypoints.get(entry+1).getX()>=snakeWaypoints.get(entry).getX()?1:-1;
        }
        for (int x = feet.getX() - radius; x <= feet.getX() + radius; x++) {
            for (int z = feet.getZ() - radius; z <= feet.getZ() + radius; z++) {
                Integer index = targetIndices.get(new BlockPos(x, walkY, z));
                if (index == null) continue;
                ProjectionTarget candidate = remainingTargets.get(index);
                if (candidate == null || deferredResumeTargets.contains(candidate.pos())) continue;
                if (requiredLane != null && candidate.lane() != requiredLane) continue;
                if (finishingLane >= 0 && !withinBandFinishArea(candidate.pos())) continue;
                // On bare glass/floor, do not create a fresh carpet lip in the
                // walking corridor. Side rows still fill now; center cells
                // become eligible immediately after the player walks past.
                if (bareForwardSweep && candidate.state().getBlock() instanceof CarpetBlock
                    && MapArtNavigationPolicy.deferCarpetAhead(mc.player.getX(),mc.player.getZ(),mc.player.getWidth(),
                        x,z,sweepDirection)) continue;
                if (blockedTargets.contains(candidate.pos()) || playerIntersectsTarget(candidate)) continue;
                if (eye.squaredDistanceTo(placementAim(candidate)) > rangeSquared) continue;
                if (isPlacementPending(candidate.pos()) || isSatisfied(candidate)) continue;
                Item item = candidate.state().getBlock().asItem();
                if (!hasItem(item)) continue;

                // Give the outer rows their in-range opportunity before they trail behind the player.
                double distance = eye.squaredDistanceTo(Vec3d.ofCenter(candidate.pos()));
                if (requiredLane != null && isBoundaryBand(requiredLane))
                    distance -= 32.0*Math.abs(candidate.pos().getZ()-MapArtBandGeometry.centerZ(minZ,maxZ,workingBandWidth,requiredLane));
                // Drain one material at this stationary stop before paying for
                // another hotbar/inventory swap. All candidates are already
                // reachable, and movement remains blocked until they drain.
                int switchCost = item == heldItem ? 0 : hotbarItems.contains(item) ? 1 : 2;
                if (switchCost < bestSwitchCost || switchCost == bestSwitchCost && distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                    bestSwitchCost = switchCost;
                }
            }
        }
        return best;
    }

    private boolean isBoundaryBand(int lane) {
        return workingBandWidth > 0 && maxZ >= minZ
            && (lane == 0 || lane == (maxZ - minZ) / workingBandWidth);
    }

    private ProjectionTarget findRestockTargetInWindow() {
        return findRestockTargetInWindow(null,false,false);
    }

    private ProjectionTarget findRestockTargetInWindow(Integer requiredLane, boolean onlyEmpty, boolean nearbyOnly) {
        Map<Item, Integer> laneDemand = new HashMap<>();
        Map<Item, ProjectionTarget> representative = new HashMap<>();
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            if (requiredLane != null && target.lane() != requiredLane) continue;
            if (nearbyOnly && Math.abs(target.pos().getX()-mc.player.getX()) > Math.ceil(placeRange.get())+1) continue;
            if (!supplyWindowLanes.contains(target.lane()) || !needsMaterial(target) || blockedTargets.contains(target.pos())
                || isPlacementPending(target.pos())) continue;
            if (isSatisfied(target)) continue;
            Item item = target.state().getBlock().asItem();
            laneDemand.merge(item, 1, Integer::sum);
            representative.putIfAbsent(item, target);
        }
        Map<Item, Integer> deficits = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : laneDemand.entrySet()) {
            int held = countInventory(entry.getKey());
            if (held < entry.getValue() && (!onlyEmpty || held == 0)) {
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

    private boolean needsMaterial(ProjectionTarget target) {
        if (deferredResumeTargets.contains(target.pos())) return false;
        if (isSatisfied(target)) return false;
        if (!mc.world.isChunkLoaded(target.pos().getX() >> 4, target.pos().getZ() >> 4)) return true;
        BlockState actual = batch.observedBlock(target.pos());
        return actual.isAir() || actual.isReplaceable();
    }

    private ProjectionTarget findStockedWindowTarget() {
        List<ProjectionTarget> remaining = new ArrayList<>(remainingTargets.values());
        for (int lane : supplyWindowLanes) for (ProjectionTarget target : remaining) {
            if (target.lane() == lane && !blockedTargets.contains(target.pos())
                && !deferredBandRepairs.contains(target.pos()) && !isPlacementPending(target.pos())
                && hasItem(target.state().getBlock().asItem()) && needsMaterial(target)) return target;
        }
        return null;
    }

    /** Advance supplies with the first-pass snake; retain distant holes for the final sparse repair. */
    private boolean serviceSupplyWindowBoundary() {
        Integer active = snakeWaypointIndex < snakeWaypointLanes.size() ? snakeWaypointLanes.get(snakeWaypointIndex) : null;
        if (!supplyWindowLanes.isEmpty() && !supplyWindowLanes.contains(active)) {
            if (holdForPlacementConfirmation()) return true;
            if (supplyWindowLanes.stream().anyMatch(openingBands::contains)) rememberOpeningCapacityShortage();
            supplyWindowLanes.clear();
        }
        if (supplyWindowLanes.isEmpty()) {
            for (int index = snakeWaypointIndex; index < snakeWaypointLanes.size(); index++) {
                int lane = snakeWaypointLanes.get(index);
                if (lane < 0 || supplyWindowLanes.contains(lane)) continue;
                supplyWindowLanes.add(lane);
                if (supplyWindowLanes.size() == RESTOCK_MAX_LOOKAHEAD_LANES) break;
            }
        }
        return false;
    }

    /** Prepare the opening pair even when one of its colors is already held. */
    private boolean serviceOpeningBands() {
        if (openingBands.lanes().isEmpty()) return false;
        if (openingBands.preparationPending()) {
            supplyWindowLanes.clear();
            supplyWindowLanes.addAll(openingBands.lanes());
            openingBands.prepared();
            supplyCheckedLane = openingBands.lanes().getFirst();
            Map<Item, Integer> demand = countNextLaneMaterials();
            int required = demand.values().stream().mapToInt(Integer::intValue).sum();
            int held = demand.entrySet().stream().mapToInt(entry -> Math.min(entry.getValue(),countInventory(entry.getKey()))).sum();
            info("开工前两带（%s，条带 %s）：剩余 %d 格，背包覆盖 %d 格，地毯可用空槽 %d；先备料再进入起点。",
                reverseSnake.get() ? "反向" : "正向",openingBands.lanes().stream().map(lane -> lane+1).toList(),required,held,emptyInventorySlots());
            ProjectionTarget shortage = findRestockTargetInWindow();
            if (shortage != null) {
                beginRestock(shortage);
                if (state == TaskState.RESTOCK || returningFromRestock) return true;
                // A constrained no-op must still allow the held opening colors to be consumed.
                state = TaskState.WORK;
            }
            rememberOpeningCapacityShortage();
            return false;
        }
        if (!openingBands.capacityDeferred() || tick % 5 != 0) return false;
        Integer active = snakeWaypointIndex < snakeWaypointLanes.size() ? snakeWaypointLanes.get(snakeWaypointIndex) : null;
        if (active != null && openingBands.contains(active)) return false;

        // Inspect the opening demand without replacing the current outward/return pair yet.
        List<Integer> currentWindow = List.copyOf(supplyWindowLanes);
        supplyWindowLanes.clear();supplyWindowLanes.addAll(openingBands.lanes());
        deferredBandRepairs.removeIf(pos -> {
            Integer index = targetIndices.get(pos);
            if (index == null) return false;
            ProjectionTarget target = targets.get(index);
            return openingBands.contains(target.lane()) && hasItem(target.state().getBlock().asItem())
                && repairVisits.getOrDefault(pos,0) < 3;
        });
        ProjectionTarget stocked = findStockedWindowTarget();
        boolean retry = openingBands.shouldRevisit(emptyInventorySlots(),stocked != null);
        ProjectionTarget shortage = retry && stocked == null ? findRestockTargetInWindow() : null;
        ProjectionTarget restart = stocked != null ? stocked : shortage;
        if (retry && restart != null) {
            for (int index=0;index<snakeWaypointLanes.size();index++) {
                if (snakeWaypointLanes.get(index) != restart.lane()) continue;
                clearPreciseRepair();finishingLane=-1;boundaryEntryStand=sweepEndpointStand=null;
                snakeWaypointIndex=index;resetNavigation();
                openingBands.resolved();
                info("开工两带曾因容量不足缺料，现已可补：优先返回条带 %d，补齐后继续蛇形。",restart.lane()+1);
                if (shortage != null) {
                    beginRestock(shortage);
                    if (state == TaskState.RESTOCK) restockReturnPos=snakeWaypoints.get(index);
                    else state=TaskState.WORK;
                }
                return true;
            }
        }
        supplyWindowLanes.clear();supplyWindowLanes.addAll(currentWindow);
        return false;
    }

    private void rememberOpeningCapacityShortage() {
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            Item item = target.state().getBlock().asItem();
            if (!openingBands.contains(target.lane()) || blockedTargets.contains(target.pos())
                || hasItem(item) || !needsMaterial(target) || supplies.getOrDefault(item,List.of()).isEmpty()) continue;
            if (inventoryCapacity(item,0) < minimumTakeCapacity.getOrDefault(item,1)) {
                if (!openingBands.capacityDeferred())
                    warning("开工两带仍缺 %s，但背包容量不足（地图/玻璃板保留两槽）；先消耗现有材料，腾出空间后优先回补。",item.getName().getString());
                openingBands.deferForCapacity(emptyInventorySlots());
                return;
            }
        }
    }

    private boolean hasRemainingWorkInLane(int lane) {
        // The background observation pass maintains this matrix. Inspect the
        // entire lane only once it appears empty, rather than querying every
        // block in the lane on every movement tick.
        for (ProjectionTarget target : remainingTargets.values())
            if (target.lane() == lane && !deferredResumeTargets.contains(target.pos())) return true;
        for (ProjectionTarget target : targets) {
            if (target.lane() != lane || deferredResumeTargets.contains(target.pos())) continue;
            if (!isSatisfied(target)) return true;
        }
        return false;
    }

    /** Reconcile every cell, including cells removed from the queue by an earlier observation. */
    private void refreshProjectionObservations(int budget) {
        if (targets.isEmpty() || mc.world == null) return;
        long started = System.nanoTime();
        for (int count = 0; count < Math.min(budget, targets.size()); count++) {
            if (observationCursor >= targets.size()) observationCursor = 0;
            ProjectionTarget target = targets.get(observationCursor++);
            if (mc.world.isChunkLoaded(target.pos().getX() >> 4, target.pos().getZ() >> 4)) isSatisfied(target);
            if ((count & 31) == 31 && System.nanoTime() - started >= BatchInspection.MAX_SCAN_NANOS) break;
        }
    }

    /** Finish the nearby endpoint only; do not walk back across each band before the final review. */
    private boolean finishSnakeBand(int lane) {
        if (finishingLane != lane || bandFinishOrigin == null) bandFinishOrigin=mc.player.getBlockPos().toImmutable();
        finishingLane = lane;
        if (preciseRepairTarget != null) {
            if (batchPlacementPending()) { releaseMovement(); return true; }
            ProjectionTarget target = preciseRepairTarget;
            if (tickPreciseRepair(target)) return true;
            if (!isSatisfied(target)) deferredBandRepairs.add(target.pos());
            clearPreciseRepair();
        }
        // Fill all reachable cells from this stand before paying for another
        // precise repair route. Rotation callbacks remain serialized by tickWork.
        ProjectionTarget nearby = selectReachableMatrixTarget(lane);
        if (nearby != null) {
            if (tick >= nextActionTick) placeTarget(nearby);
            else releaseMovement();
            return true;
        }
        if (batchPlacementPending()) { releaseMovement(); return true; }
        ProjectionTarget best = null;
        double distance = Double.POSITIVE_INFINITY;
        int missing = 0;
        for (ProjectionTarget target : targets) {
            if (target.lane() != lane || deferredResumeTargets.contains(target.pos()) || isSatisfied(target)) continue;
            boolean loaded = mc.world.isChunkLoaded(target.pos().getX() >> 4, target.pos().getZ() >> 4);
            BlockState actual = loaded ? batch.observedBlock(target.pos()) : null;
            if (loaded && !actual.isAir() && !actual.isReplaceable()) continue;
            missing++;
            if (!withinBandFinishArea(target.pos())) continue;
            if (deferredBandRepairs.contains(target.pos())) continue;
            // Consume other stocked colors before requesting a missing color.
            double candidate = distanceToPlayer(target.pos())
                + (hasItem(target.state().getBlock().asItem()) ? 0 : 1_000_000);
            if (candidate < distance) { best = target; distance = candidate; }
        }
        if (best == null) {
            if (missing > 0) activity="本带保留 "+missing+" 个缺口，首遍结束后集中定点修补";
            finishingLane = -1;
            return false;
        }
        Item item = best.state().getBlock().asItem();
        if (!hasItem(item)) {
            if (findStockedWindowTarget() != null) {
                for (ProjectionTarget target : targets)
                    if (target.lane() == lane && target.state().getBlock().asItem() == item) deferredBandRepairs.add(target.pos());
                activity = "本带部分颜色不足，先用现有材料完成往返条带；缺口保留";
                return true;
            }
            Set<Item> attempted = bandSupplyAttempts.computeIfAbsent(lane, ignored -> new HashSet<>());
            if (attempted.add(item) && hasUsableSupply(item) && inventoryCapacity(item, 0) > 0) {
                beginRestock(best);
                if (state == TaskState.RESTOCK || returningFromRestock) return true;
                state = TaskState.WORK;
            }
            // Retry unavailable colors in the broad repair pass, without calling the band complete.
            for (ProjectionTarget target : targets)
                if (target.lane() == lane && target.state().getBlock().asItem() == item) deferredBandRepairs.add(target.pos());
            return true;
        }
        if (!tickPreciseRepair(best)) deferredBandRepairs.add(best.pos());
        activity = "条带收尾：核对并补齐漏格 " + best.pos().toShortString();
        return true;
    }

    private boolean withinBandFinishArea(BlockPos pos) {
        return bandFinishOrigin != null
            && Math.abs(pos.getX()-bandFinishOrigin.getX()) <= placeRange.get()
            && Math.abs(pos.getZ()-bandFinishOrigin.getZ()) <= placeRange.get();
    }

    private void clearPreciseRepair() {
        releaseMovement();
        preciseRepairTarget = null; preciseRepairStand = null; rejectedRepairStands.clear();
    }

    /** At most three genuinely different stands per cell/pass; never hammer one rejected ray. */
    private boolean tickPreciseRepair(ProjectionTarget target) {
        if (isSatisfied(target)) { clearPreciseRepair(); return false; }
        if (batchPlacementPending()) { releaseMovement(); return true; }
        if (preciseRepairTarget == null || !preciseRepairTarget.pos().equals(target.pos())) {
            clearPreciseRepair(); preciseRepairTarget = target; preciseRepairStarted = tick;
        }
        if (!mc.world.isChunkLoaded(target.pos().getX() >> 4, target.pos().getZ() >> 4)) {
            if (tick - preciseRepairStarted > 240 || navigateTo(target.pos()) == NavigationResult.UNREACHABLE) {
                clearPreciseRepair(); return false;
            }
            return true;
        }
        BlockState actual = batch.observedBlock(target.pos());
        if ((!actual.isAir() && !actual.isReplaceable()) || !hasItem(target.state().getBlock().asItem())) {
            clearPreciseRepair(); return false;
        }
        if (blockedTargets.remove(target.pos()) && preciseRepairStand != null) rejectRepairStand();
        if (preciseRepairStand == null) {
            int visits = repairVisits.getOrDefault(target.pos(), 0);
            if (visits >= 3) { blockedTargets.add(target.pos()); clearPreciseRepair(); return false; }
            preciseRepairStand = findPreciseRepairStand(target);
            if (preciseRepairStand == null) { blockedTargets.add(target.pos()); clearPreciseRepair(); return false; }
            repairVisits.put(target.pos(), visits + 1);
            preciseRepairStarted = tick;
            resetNavigation();
        }
        NavigationResult route = navigateTo(preciseRepairStand);
        if (route == NavigationResult.UNREACHABLE || tick - preciseRepairStarted > 200) {
            rejectRepairStand(); return true;
        }
        if (route == NavigationResult.MOVING) return true;
        // Normal endpoint tolerance is intentionally generous; a precise click needs a centered stand.
        if (!hasReachedPathStep(preciseRepairStand)) {
            if (!canWalkDirectlyTo(preciseRepairStand)) { rejectRepairStand(); return true; }
            faceToward(preciseRepairStand); pressForward(); return true;
        }
        if (!isWithinPlaceRange(target) || playerIntersectsTarget(target)) { rejectRepairStand(); return true; }
        placeTarget(target);
        return true;
    }

    private void rejectRepairStand() {
        if (preciseRepairStand != null) rejectedRepairStands.add(preciseRepairStand);
        preciseRepairStand = null; resetNavigation();
    }

    private BlockPos findPreciseRepairStand(ProjectionTarget target) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int radius = (int) Math.ceil(placeRange.get());
        Vec3d aim = placementAim(target);
        for (int x = target.pos().getX()-radius; x <= target.pos().getX()+radius; x++)
            for (int z = target.pos().getZ()-radius; z <= target.pos().getZ()+radius; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (candidate.equals(target.pos()) || rejectedRepairStands.contains(candidate) || !isStandUsable(candidate)) continue;
                Vec3d eye = new Vec3d(x+0.5, standSurfaceY(candidate)+1.62, z+0.5);
                if (eye.squaredDistanceTo(aim) > Math.pow(placeRange.get()-0.25, 2)) continue;
                // Floor-face visibility from the candidate stand avoids walking to a known obstructed angle.
                if (target.state().getBlock() instanceof CarpetBlock) {
                    var hit = mc.world.raycast(new RaycastContext(eye, aim.add(0,-0.001,0),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target.pos().down())) continue;
                }
                double distance = distanceToPlayer(candidate);
                if (distance < bestDistance) { best = candidate; bestDistance = distance; }
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

        batch.watchPlacement(target.pos());
        FullAutoPlacementEngine.ExternalPlacementResult result = printer.placeProjectionBlock(
            target.pos(), target.state(), placeRange.get(), repairVisits.getOrDefault(target.pos(), 0)
        );
        nextActionTick = tick + 1;
        switch (result) {
            case COMPLETE -> {
                if (!isSatisfied(target)) {
                    // A predicted client block is not a server receipt.
                    dispatchedPlacements.putIfAbsent(target.pos(), tick);
                    lastPlacementDispatchTick = tick;
                }
            }
            case DISPATCHED -> {
                if (reviewingRepair) reviewAttempted.add(target.pos().toImmutable());
                dispatchedPlacements.put(target.pos(), tick);
                lastPlacementDispatchTick = tick;
                lastProgressTick = tick;
                activity = "放置矩阵：" + completedMatrixCells() + "/" + targets.size();
            }
            case MISSING_ITEM -> {
                if (reviewingRepair) reviewAttempted.add(target.pos().toImmutable());
                else if (!holdForPlacementConfirmation() && !returningFromRestock
                    && (reviewPass > 0 || findStockedWindowTarget() == null)) beginRestock(target);
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
                markPlacementBlocked(target);
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
        activity = "待换站位补放：" + blockedTargets.size() + " 格";
        if (blockedTargets.size() <= 3 || blockedTargets.size() % 32 == 0) {
            warning("放置 %s 尚未确认；条带结束时换站位补放（累计 %d 格）。", target.pos().toShortString(), blockedTargets.size());
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
        if (!restockSurplus.begin(bands, discardableMaterials(), batch.reservedSlots(), destination, tick)) return false;
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
        supplyTrip.clear();
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
        info("往返两条带备料：剩余 %d 格，背包可覆盖 %d 格，缺口 %d；按颜色缺口和背包容量补充。",
            neededForBand, heldForBand, neededForBand - heldForBand);
        activity = "前往补给箱";
    }

    /** Limit ground discards to projection materials and their matching dyed families. */
    private Set<Item> discardableMaterials() {
        Set<Item> eligible = new HashSet<>();
        Set<Item> inspected = new HashSet<>();
        Set<String> dyedFamilies = new HashSet<>();
        for (ProjectionTarget target : projectionLayer) {
            Item item = target.state().getBlock().asItem();
            if (!inspected.add(item) || !returnableMapMaterial(item)) continue;
            eligible.add(item);
            Identifier id = Registries.ITEM.getId(item);
            String color = MapArtColorFilter.colorOf(id.getNamespace(), id.getPath());
            if (color != null) dyedFamilies.add(id.getPath().substring(color.length() + 1));
        }
        for (String family : dyedFamilies) for (String color : MapArtColorFilter.COLORS) {
            Item item = Registries.ITEM.get(Identifier.of("minecraft", color + "_" + family));
            if (returnableMapMaterial(item)) eligible.add(item);
        }
        // Old images may use carpet colors absent from this particular file.
        for (int slot = 0; slot < 36; slot++) {
            Item item = mc.player.getInventory().getStack(slot).getItem();
            if (returnableMapMaterial(item) && item instanceof net.minecraft.item.BlockItem block
                && block.getBlock() instanceof CarpetBlock) eligible.add(item);
        }
        return eligible;
    }

    private boolean returnableMapMaterial(Item item) {
        if (!(item instanceof net.minecraft.item.BlockItem block) || item == Items.GLASS_PANE
            || new ItemStack(item).get(net.minecraft.component.DataComponentTypes.FOOD) != null) return false;
        return !(block.getBlock() instanceof ShulkerBoxBlock || block.getBlock() instanceof ChestBlock
            || block.getBlock() instanceof BarrelBlock);
    }

    private WorkChoice findStockedTarget() {
        Set<Item> heldItems = inventoryItems();
        Map<Long, WorkRegion> regions = new HashMap<>();
        for (Map.Entry<Integer, ProjectionTarget> entry : remainingTargets.entrySet()) {
            ProjectionTarget candidate = entry.getValue();
            if (deferredResumeTargets.contains(candidate.pos()) || blockedTargets.contains(candidate.pos()) || isPlacementPending(candidate.pos())
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
        if (!supplyTrip.planned()) prepareSupplyTrip();
        // The work coordinates and quotas were captured before leaving the
        // band. Chest changes only reconcile the small inventory snapshot.
        Map<Item, MapArtSupplyPlanner.Stock> inventory = new HashMap<>();
        int empty = emptyInventorySlots();
        for (Item item : supplyTrip.goals().keySet()) inventory.put(item, supplyStock(item, empty));
        List<SupplyTrip.Source<Item,BlockPos>> sources = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : supplyTrip.goals().entrySet()) {
            Item item = entry.getKey();
            if (reviewRestocking && reviewSupplyTried.contains(item)) continue;
            int held = inventory.get(item).count();
            int deficit = supplyTrip.remaining(item, held, entry.getValue());
            int reserved = supplyTrip.reservedForOthers(item, inventory);
            if (deficit <= 0 || !hasUsableSupply(item, reserved)) continue;
            if (inventoryCapacity(item, reserved) <= 0) continue;
            for (BlockPos chest : supplies.getOrDefault(item,List.of()))
                if (!isSupplyChestUnavailable(chest))
                    sources.add(new SupplyTrip.Source<>(item,chest,distanceToPlayer(chest)));
        }
        // Distance is the primary ordering across ALL needed colors, not a
        // tie-breaker after preferred-color/empty-inventory priority.
        SupplyTrip.Source<Item,BlockPos> nearest = supplyTrip.nearest(sources);
        Item selected = nearest == null ? null : nearest.item();
        if (selected == null) return false;
        if (reviewRestocking) reviewSupplyTried.add(selected);
        supplyItem = selected;
        reservedSupplySlots = supplyTrip.reservedForOthers(selected, inventory);
        supplyReceivedThisRound = 0;
        supplyLastTakeTick = -1;
        int capacity = inventoryCapacity(selected, reservedSupplySlots);
        supplyAmount = Math.min(capacity, supplyTrip.remaining(selected, countInventory(selected), Integer.MAX_VALUE));
        info("就近备料 %s：最近箱 %s，距离 %.1f 格，背包已有 %d，本趟补入 %d（整组取货）。",
            selected.getName().getString(), nearest.position().toShortString(),Math.sqrt(nearest.distanceSquared()),countInventory(selected),supplyAmount);
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        return true;
    }

    private MapArtSupplyPlanner.Stock supplyStock(Item item, int empty) {
        int held = countInventory(item);
        return new MapArtSupplyPlanner.Stock(held, held + inventoryCapacity(item, empty),
            new ItemStack(item).getMaxCount(), !supplies.getOrDefault(item, List.of()).isEmpty(),
            isSupplyTemporarilyDeferred(item));
    }

    /** Once per journey: active strip first, then the return strip, then bounded spare stock. */
    private void prepareSupplyTrip() {
        List<List<Item>> materialBands = new ArrayList<>();
        Map<Item, MapArtSupplyPlanner.Stock> inventory = new HashMap<>();
        int empty = emptyInventorySlots();
        for (List<ProjectionTarget> band : upcomingTargetBands()) {
            if (materialBands.size() >= RESTOCK_MAX_LOOKAHEAD_LANES) break;
            List<Item> materials = new ArrayList<>();
            for (ProjectionTarget target : band) {
                Item item = target.state().getBlock().asItem();
                materials.add(item);
                inventory.computeIfAbsent(item, key -> supplyStock(key, empty));
            }
            materialBands.add(materials);
        }
        restockBandDemand = new LinkedHashMap<>();
        if (!materialBands.isEmpty()) for (Item item : materialBands.getFirst())
            restockBandDemand.merge(item, 1, Integer::sum);
        MapArtSupplyPlanner.Plan<Item> plan = MapArtSupplyPlanner.planRoundTrip(materialBands, inventory, empty);
        // The buffer never exceeds real remaining blueprint demand, and never
        // takes slots needed to finish either of the planned bands.
        Map<Item, Integer> totalRemaining = new HashMap<>();
        for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
            Item item = target.state().getBlock().asItem();
            if (inventory.containsKey(item) && !blockedTargets.contains(target.pos()) && needsMaterial(target))
                totalRemaining.merge(item, 1, Integer::sum);
        }
        plan = MapArtSupplyPlanner.withBuffer(plan, inventory, empty, totalRemaining, 32);
        supplyTrip.freezeGoals(plan.desired());
    }

    private Map<Item, Integer> countNextLaneMaterials() {
        List<List<ProjectionTarget>> bands = upcomingTargetBands();
        Map<Item, Integer> demand = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(2, bands.size()); index++)
            materialCounts(bands.get(index)).forEach((item, count) -> demand.merge(item, count, Integer::sum));
        return demand;
    }

    private boolean hasUsableSupply(Item item) {
        int reserved = reservedSlotsForOtherColors(item, countNextLaneMaterials().keySet(), inventoryItems());
        return hasUsableSupply(item, reserved);
    }

    private boolean hasUsableSupply(Item item, int reserved) {
        if (isSupplyTemporarilyDeferred(item)) return false;
        // Producers are stocked with whole stacks. A few free merge slots
        // cannot justify a trip to another equivalent chest. Capacity, not
        // elapsed time, determines when this color becomes restockable again.
        if (!canTakeSupplyStack(item, reserved)) return false;
        minimumTakeCapacity.remove(item);
        return supplies.getOrDefault(item, List.of()).stream()
            .anyMatch(pos -> !isSupplyChestUnavailable(pos));
    }

    private boolean canTakeSupplyStack(Item item, int reserved) {
        int minimum = Math.max(new ItemStack(item).getMaxCount(), minimumTakeCapacity.getOrDefault(item, 0));
        return inventoryCapacity(item, reserved) >= minimum;
    }

    private boolean isSupplyChestUnavailable(BlockPos pos) {
        Long until = unreachableSupplyUntil.get(pos);
        if (until != null && tick >= until) { unreachableSupplyUntil.remove(pos); until = null; }
        return until != null || producerSupplyReserve.cooling(pos, tick);
    }

    private void deferSupplyChest(String reason) {
        if (supplyChest != null) {
            unreachableSupplyUntil.put(supplyChest.toImmutable(), tick + EMPTY_SUPPLY_RECHECK_TICKS);
            warning("补给箱 %s 暂不可达/操作未确认（%s），冷却60秒，改试同色下一箱；不记为无货。", supplyChest.toShortString(), reason);
        }
        closeSupplyScreen();
        supplyChest = null; supplyStand = null;
        stationaryRestock.reset();
        stationaryRestockRecoveries = 0;
        supplyOpenTick = supplyAimTick = -1;
        supplyOpenAttempts = 0;
        resetRestockTransfer(); resetNavigation();
    }

    private void pauseSupplyForProduction(int remaining) {
        if (supplyChest != null) {
            producerSupplyReserve.defer(supplyChest.toImmutable(), tick);
            info("补给箱 %s 有 %d 个 %s，整组取货后需至少保留 %d 个；关闭箱子，约120秒后再试。",
                supplyChest.toShortString(), remaining, supplyItem.getName().getString(), new ItemStack(supplyItem).getMaxCount());
        }
        closeSupplyScreen();
        supplyChest = null; supplyStand = null;
        stationaryRestock.reset(); stationaryRestockRecoveries = 0;
        supplyOpenTick = supplyAimTick = -1; supplyOpenAttempts = 0;
        resetRestockTransfer(); resetNavigation();
        activity = "为地毯机保留一组原料，该箱等待生产约120秒";
    }

    private ProducerSupplyReserve.Take producerSupplyTake(ScreenHandler opened, int capacity) {
        List<ProducerSupplyReserve.Stack> matching = new ArrayList<>();
        int slots = StorageBlockUtils.storageSlots(opened);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (!stack.isEmpty() && stack.isOf(supplyItem))
                matching.add(new ProducerSupplyReserve.Stack(slot, stack.getCount()));
        }
        return ProducerSupplyReserve.choose(matching, new ItemStack(supplyItem).getMaxCount(), capacity);
    }

    private boolean resumeStockedBand() {
        if (snakeWaypointIndex >= snakeWaypoints.size()) buildSnakeWaypoints();
        for (int cursor = snakeWaypointIndex; cursor < snakeWaypointLanes.size(); cursor++) {
            int lane = snakeWaypointLanes.get(cursor);
            // Supply trouble cannot turn into a canvas-wide teleport in the work order.
            if (cursor > snakeWaypointIndex && lane != snakeWaypointLanes.get(snakeWaypointIndex)) break;
            boolean stocked = false;
            for (ProjectionTarget target : new ArrayList<>(remainingTargets.values())) {
                if (target.lane() == lane && !deferredResumeTargets.contains(target.pos())
                    && !blockedTargets.contains(target.pos()) && !isSatisfied(target)
                    && hasItem(target.state().getBlock().asItem())) { stocked = true; break; }
            }
            if (!stocked) continue;
            snakeWaypointIndex = cursor; supplyCheckedLane = lane;
            returningFromRestock = returningToCheckpoint && restockReturnPos != null;
            if (!returningFromRestock) restockReturnPos = null;
            resetNavigation(); state = TaskState.WORK;
            activity = "补货暂不可用，继续背包有材料的蛇形条带 " + lane;
            return true;
        }
        return false;
    }

    private void rememberTakeCapacity(Item item, int minimum) {
        minimumTakeCapacity.merge(item, minimum, Math::min);
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
        // Count the whole journey, never each container or each color.
        if (didRestock) {
            int missing = restockBandDemand.entrySet().stream()
                .mapToInt(entry -> Math.max(0, entry.getValue() - countInventory(entry.getKey()))).sum();
            if (missing > 0) info("当前条带仍缺 %d 个材料，受背包容量或材料箱状态限制；先铺已有材料，腾出空间后再补。", missing);
        }
        restockBandDemand = Map.of();
        closeSupplyScreen();
        resetRestockTransfer();
        resetNavigation();
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyReceivedThisRound = 0;
        // Missing-color deferrals are temporary; failed placement stands keep their retry budget.
        deferredBandRepairs.removeIf(pos -> {
            Integer index = targetIndices.get(pos);
            return index != null && hasItem(targets.get(index).state().getBlock().asItem())
                && repairVisits.getOrDefault(pos, 0) < 3;
        });
        supplyChest = null;
        workGoalTarget = null;
        returningFromRestock = false;
        if ((didRestock || returningToCheckpoint) && restockReturnPos != null && !hasReached(restockReturnPos)) {
            // An unloaded work position is not an invalid stand. navigateTo
            // loads its route progressively, preserving the original anchor.
            if (mc.world.isChunkLoaded(restockReturnPos.getX() >> 4, restockReturnPos.getZ() >> 4)
                && !isStandUsable(restockReturnPos)) {
                BlockPos alternate = findStandNear(restockReturnPos, 2);
                if (alternate != null) restockReturnPos = alternate;
            }
            returningFromRestock = true;
        } else {
            restockReturnPos = null;
            returningToCheckpoint = false;
        }
        state = TaskState.WORK;
        // A successful supply visit is not a permanent ban on that color for
        // this review: it may be used up again while repairing other holes.
        if (reviewRestocking) reviewSupplyTried.removeIf(this::hasItem);
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
            if (deferredResumeTargets.contains(target.pos()) || isSatisfied(target) || blockedTargets.contains(target.pos())) continue;
            Item item = target.state().getBlock().asItem();
            if (hasItem(item)) return true;
            if (hasUsableSupply(item) && inventoryCapacity(item,
                reservedSlotsForOtherColors(item, upcoming, heldItems)) > 0) return true;
        }
        return false;
    }

    private void markSupplyTemporarilyEmpty(Item item) {
        emptySupplyUntil.put(item, tick + PRODUCING_CHEST_RECHECK_TICKS);
    }

    private boolean isSupplyTemporarilyDeferred(Item item) {
        Long emptyUntil = emptySupplyUntil.get(item);
        if (emptyUntil != null && tick >= emptyUntil) emptySupplyUntil.remove(item);
        return emptyUntil != null && tick < emptyUntil;
    }

    private boolean hasPendingSupplyRecheck() {
        emptySupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        unreachableSupplyUntil.entrySet().removeIf(entry -> tick >= entry.getValue());
        return !emptySupplyUntil.isEmpty() || !unreachableSupplyUntil.isEmpty() || producerSupplyReserve.pending(tick);
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
        releaseMovement();
        if (tick % 20 != 0) return;
        if (resumeStockedBand()) return;
        if (serviceRestockSurplusForCapacity()) return;
        if (!hasPotentialWorkFrom(0)) return;
        state = TaskState.WORK;
        activity = "背包已有可用空间，返回蛇形扫描路点";
    }

    private void tickSupplyRecheckWait() {
        if (tick % 20 == 0 && resumeStockedBand()) return;
        // A ready source can resume work even if another producer is still
        // cooling. Do not wait for every registered container's timer.
        boolean sourceReady = tick % 20 == 0 && tick >= supplyRetryAt && hasPotentialWorkFrom(0);
        if (!sourceReady && (hasPendingSupplyRecheck() || tick < supplyRetryAt)) {
            long nextCheck = java.util.stream.LongStream.of(
                emptySupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                unreachableSupplyUntil.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE),
                producerSupplyReserve.nextRetry(tick),
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
        boolean cleanup = reviewPass > 0 || snakeWaypointIndex >= snakeWaypointLanes.size();
        if (reviewPass == 0 && !supplyWindowLanes.isEmpty()) cleanup = false;
        if (!cleanup) {
            // On the return leg, its remaining cells outrank deferred holes
            // from the outward leg while both remain in the fixed pair.
            if (snakeWaypointIndex < snakeWaypointLanes.size()) {
                int active = snakeWaypointLanes.get(snakeWaypointIndex);
                if (supplyWindowLanes.contains(active)) byLane.put(active,new ArrayList<>());
            }
            for (int lane : supplyWindowLanes) byLane.put(lane, new ArrayList<>());
            if (byLane.isEmpty()) for (int index = snakeWaypointIndex; index < snakeWaypointLanes.size(); index++) {
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
                || !needsMaterial(target)) continue;
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
            cursorSpaceTarget = null; cursorSpaceStand = null;
            cursorSpaceRejected.clear(); cursorSpaceRejectedStands.clear(); cursorSpacePlanAt = 0;
        }
        if (cursorSpaceOrigin == null) cursorSpaceOrigin = mc.player.getBlockPos().toImmutable();
        checkDispatchedPlacements();
        if (printer == null || cursorSpaceAttempts >= CursorSpacePlanner.attemptLimit(cursor.getMaxCount())) return false;
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) return true;
        if (cursorSpaceTarget != null && (blockedTargets.contains(cursorSpaceTarget.pos())
            || !mc.world.getBlockState(cursorSpaceTarget.pos()).isAir())) {
            cursorSpaceTarget = null; cursorSpaceStand = null;
            cursorSpaceRejectedStands.clear();
        }
        if (cursorSpaceTarget == null) {
            if (tick < cursorSpacePlanAt) return false;
            cursorSpacePlanAt = tick + 20;
            Map<Item, CursorSpacePlanner.Stock> hotbar = new HashMap<>();
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (stack.isEmpty()) continue;
                boolean compatible = ItemStack.areItemsAndComponentsEqual(stack,cursor);
                hotbar.merge(stack.getItem(), new CursorSpacePlanner.Stock(stack.getCount(), compatible),
                    (left,right) -> new CursorSpacePlanner.Stock(Math.min(left.smallestStackCount(),right.smallestStackCount()),
                        left.compatibleWithCursor() || right.compatibleWithCursor()));
            }
            Map<Item,Integer> missing = new HashMap<>();
            Map<Item,ProjectionTarget> nearest = new HashMap<>();
            for (ProjectionTarget candidate : targets) {
                BlockPos pos = candidate.pos();
                Item item = candidate.state().getBlock().asItem();
                if (!hotbar.containsKey(item) || blockedTargets.contains(pos) || cursorSpaceRejected.contains(pos)
                    || !mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4) || !mc.world.getBlockState(pos).isAir()) continue;
                missing.merge(item,1,Integer::sum);
                ProjectionTarget old = nearest.get(item);
                if (old == null || distanceToPlayer(pos) < distanceToPlayer(old.pos())) nearest.put(item,candidate);
            }
            Item selected = CursorSpacePlanner.chooseMaterial(hotbar,missing);
            if (selected == null) return false;
            cursorSpaceTarget = nearest.get(selected);
            cursorSpaceRejectedStands.clear();
            cursorSpaceStand = findCursorSpaceStand(cursorSpaceTarget);
            cursorSpacePlanAt = 0;
        }
        ProjectionTarget best = cursorSpaceTarget;
        if (!isWithinPlaceRange(best) || playerIntersectsTarget(best)) {
            NavigationResult result = cursorSpaceStand == null ? NavigationResult.UNREACHABLE : navigateTo(cursorSpaceStand);
            if (result == NavigationResult.ARRIVED) {
                // An endpoint's normal 0.6-block tolerance does not guarantee
                // the actual hand is in range. Try a different concrete stand.
                cursorSpaceRejectedStands.add(cursorSpaceStand);
                cursorSpaceStand = findCursorSpaceStand(best);
                resetNavigation();
                if (cursorSpaceStand != null) return true;
                result = NavigationResult.UNREACHABLE;
            }
            if (result == NavigationResult.UNREACHABLE) {
                cursorSpaceRejected.add(best.pos());
                cursorSpaceTarget = null; cursorSpaceStand = null; resetNavigation();
                return false;
            }
            return true;
        }
        releaseMovement();
        Vec3d velocity = mc.player.getVelocity();
        if (!mc.player.isOnGround() || velocity.x*velocity.x+velocity.z*velocity.z > 0.000625 || tick < nextActionTick) return true;
        batch.watchPlacement(best.pos());
        FullAutoPlacementEngine.ExternalPlacementResult result = printer.placeProjectionBlockForCursorRecovery(best.pos(),best.state(),placeRange.get());
        nextActionTick = tick + 1;
        if (result == FullAutoPlacementEngine.ExternalPlacementResult.DISPATCHED) {
            cursorSpaceAttempts++;
            dispatchedPlacements.put(best.pos(),tick);lastPlacementDispatchTick=tick;
        } else if (result == FullAutoPlacementEngine.ExternalPlacementResult.BLOCKED) markPlacementBlocked(best);
        else if (result == FullAutoPlacementEngine.ExternalPlacementResult.MISSING_ITEM) cursorSpaceRejected.add(best.pos());
        cursorSpaceTarget = null; cursorSpaceStand = null;
        return true;
    }

    private BlockPos findCursorSpaceStand(ProjectionTarget target) {
        BlockPos best = null;
        double nearest = Double.POSITIVE_INFINITY;
        double eyeHeight = mc.player.getEyePos().y-mc.player.getY();
        double reach = Math.max(0.5,placeRange.get()-0.2);
        for (int x=target.pos().getX()-2; x<=target.pos().getX()+2; x++) {
            for (int z=target.pos().getZ()-2; z<=target.pos().getZ()+2; z++) {
                BlockPos stand = new BlockPos(x,walkY,z);
                if (stand.equals(target.pos()) || cursorSpaceRejectedStands.contains(stand)
                    || !mc.world.isChunkLoaded(x>>4,z>>4) || !isStandUsable(stand)) continue;
                Vec3d eye = new Vec3d(x+0.5,standSurfaceY(stand)+eyeHeight,z+0.5);
                if (eye.squaredDistanceTo(placementAim(target)) > reach*reach) continue;
                double distance = distanceToPlayer(stand);
                if (distance < nearest) { nearest = distance; best = stand; }
            }
        }
        return best;
    }

    private void tickRestock() {
        if (restockSurplus.active()) { tickRestockSurplus(); return; }
        // The batch controller settles any interrupted cursor operation before
        // this worker runs. Restocking itself still uses whole-stack quick move.
        if (supplyChest != null) {
            double distance = distanceToPlayer(supplyChest);
            if (distance + 0.25 < supplyBestDistance) {
                supplyBestDistance = distance;
                supplyProgressTick = tick;
            }
            if (tick - supplyProgressTick >= StationaryRestockWatchdog.TIMEOUT_TICKS) {
                if (mc.player.currentScreenHandler != mc.player.playerScreenHandler && supplyOpenTick < 0) {
                    releaseMovement();
                    state = TaskState.PAUSED;
                    activity = "补货被其他界面阻挡，请关闭后重新开启功能";
                    warning("%s", activity);
                    return;
                }
                deferSupplyChest("连续三分钟无接近或取货进展");
                activity = "当前箱子无进展，切换同色下一箱";
                return;
            }
        }
        // A delayed inventory update may already satisfy the quota while we
        // are walking to a selected box. Check before moving/opening, while
        // leaving an in-flight take alone until its confirmation is handled.
        if (supplyItem != null && restockTransferTick < 0) refreshSupplyAmount();
        if (supplyItem == null || supplyAmount <= 0) {
            if (supplyItem != null) {
                emptySupplyUntil.remove(supplyItem);
                minimumTakeCapacity.remove(supplyItem);
            }
            continueRestock();
            return;
        }

        if (supplyChest == null) {
            if (!canTakeSupplyStack(supplyItem, reservedSupplySlots)) {
                activity = "空间不足以装下一整组，继续其它颜色或返回铺设";
                continueRestock();
                return;
            }
            // Retry an equivalent source only for an actual access/transfer
            // failure, never because this inventory cannot fit its stacks.
            BlockPos nearest = supplies.getOrDefault(supplyItem, List.of()).stream()
                .filter(pos -> !supplyTrip.visited(pos) && !isSupplyChestUnavailable(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer))
                .orElse(null);
            if (nearest == null) {
                if (minimumTakeCapacity.containsKey(supplyItem)) {
                    activity = "箱内有材料，但当前预留背包空间装不下整组；继续其它颜色";
                    warning("%s 的箱内有货，但当前可用背包空间不足；背包腾出空间后再取，不标记为无货。",
                        supplyItem.getName().getString());
                } else if (supplies.getOrDefault(supplyItem,List.of()).stream().anyMatch(pos -> producerSupplyReserve.cooling(pos,tick))) {
                    activity = "同色生产箱保留种料中，等待约2分钟后再补；先处理其它材料";
                } else if (supplies.getOrDefault(supplyItem,List.of()).stream().anyMatch(pos -> unreachableSupplyUntil.getOrDefault(pos,0L)>tick)) {
                    activity = "同色箱暂不可达，先继续其他颜色，冷却后自动重试";
                    warning("%s 的箱子存在寻路/开箱失败，未记为无货；先处理其他条带。", supplyItem.getName().getString());
                } else {
                    markSupplyTemporarilyEmpty(supplyItem);
                    activity = "登记箱取货未确认，5 秒后复查";
                    warning("已尝试可用的 %s 箱，本轮取到 %d 个、本趟计划尚余 %d 个；先铺背包材料，再复查生产箱。",
                        supplyItem.getName().getString(), supplyReceivedThisRound, supplyAmount);
                }
                continueRestock();
                return;
            }
            supplyChest = nearest;
            stationaryRestock.reset();
            stationaryRestockRecoveries = 0;
            supplyProgressTick = tick;
            supplyBestDistance = distanceToPlayer(nearest);
            supplyTrip.visit(nearest);
            info("补货 %s：前往最近的未尝试箱子 %s；本轮还需 %d 个。",
                supplyItem.getName().getString(), nearest.toShortString(), supplyAmount);
            // Remote item frames may not be loaded yet. Travel using the
            // registered ID; validate the live label and slot IDs on arrival.
            supplyChestLabel = supplyItem;
            supplyStand = null;
            rejectedSupplyStands.clear();
            supplyOpenTick = -1;
            supplyAimTick = -1;
            supplyOpenAttempts = 0;
        }

        ScreenHandler opened = StorageBlockUtils.storageSlots(mc.player.currentScreenHandler) > 0
            ? mc.player.currentScreenHandler : null;
        if (opened != null && opened != ownedSupplyHandler) {
            releaseMovement();
            activity = "请关闭手动打开的容器，之后继续补货";
            return;
        }
        if (opened != null) {
            if (supplyInventorySyncId == opened.syncId && supplyInventoryReadyTick < 0) supplyInventoryReadyTick=tick;
            if (supplyInventorySyncId != opened.syncId || supplyInventoryReadyTick < 0 || tick-supplyInventoryReadyTick < 2) {
                releaseMovement();
                if (supplyOpenTick >= 0 && tick-supplyOpenTick >= 100) deferSupplyChest("完整库存列表未同步");
                else activity="等待服务器同步箱子物品，不判定为空箱";
                return;
            }
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
            supplyInventorySyncId=-1;supplyInventoryReadyTick=-1;
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
                stationaryRestock.reset();
                stationaryRestockRecoveries = 0;
                supplyAmount -= moved;
                supplyReceivedThisRound += moved;
                supplyLastTakeTick = tick;
                resetRestockTransfer();
                nextActionTick = tick + 1;
                activity = "已取到 " + moved + " 个 " + supplyItem.getName().getString();
                // Also mark the box when this take completed our quota, so a
                // new short trip cannot immediately reopen the seed-only box.
                ProducerSupplyReserve.Take left = producerSupplyTake(opened, Integer.MAX_VALUE);
                if (left.reserveLimited()) pauseSupplyForProduction(left.total());
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
        ProducerSupplyReserve.Take take = producerSupplyTake(opened, safeCapacity);
        if (take.reserveLimited()) { pauseSupplyForProduction(take.total()); return; }
        if (take.slot() >= 0) {
            // Only a complete stack that leaves one full stack's worth of
            // this exact material in storage is eligible for quick-move.
            int slot = take.slot();
            restockInventoryBefore = countInventory(supplyItem);
            batch.rememberCursorOrigin(opened,slot);
            mc.interactionManager.clickSlot(opened.syncId,slot,0,SlotActionType.QUICK_MOVE,mc.player);
            restockTransferTick = tick;
            restockTransferStartedUnfocused = !mc.isWindowFocused();
            nextActionTick = tick + 1;
            activity = "从补给箱取 " + supplyItem.getName().getString() + "，保留一组种料";
            return;
        }
        rememberTakeCapacity(supplyItem, take.minimumTake());
        closeSupplyScreen();
        supplyChest = null;
        resetRestockTransfer();
        resetNavigation();
        activity = "当前背包空间装不下整组；先使用背包现货";
        continueRestock();
    }

    /** Recover only after three minutes without actual movement or stock gain. */
    private boolean recoverStationaryRestock() {
        if (mc.player == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        boolean eligible = state == TaskState.RESTOCK && supplyChest != null
            && (handler == mc.player.playerScreenHandler || handler == ownedSupplyHandler)
            && !autoEater.eating();
        if (!stationaryRestock.stalled(tick, mc.player.getX(), mc.player.getZ(), eligible)) return false;
        if (++stationaryRestockRecoveries > 2) {
            deferSupplyChest("原地三分钟且换位/重开仍无进展");
            activity = "当前补货箱无进展，自动切换同色其它箱子";
            return true;
        }
        if (handler == ownedSupplyHandler) {
            // A pending click or cursor item still belongs to the normal
            // transaction recovery. Never discard or move it during a nudge.
            if (restockTransferTick >= 0 || !handler.getCursorStack().isEmpty()) {
                stationaryRestockRecoveries--;
                activity = "等待补货操作确认或鼠标物品安全收回";
                return false;
            }
            closeSupplyScreen();
            supplyOpenTick = supplyAimTick = -1;
            supplyOpenAttempts = 0;
            supplyInventorySyncId = -1;
            supplyInventoryReadyTick = -1;
            resetNavigation();
            supplyProgressTick = tick;
            activity = "补货原地三分钟，重新打开当前箱子";
            warning("%s：%s", activity, supplyChest.toShortString());
            return true;
        }
        if (supplyStand != null) rejectedSupplyStands.add(supplyStand);
        else rejectedSupplyStands.add(mc.player.getBlockPos().toImmutable());
        supplyStand = findSupplyStandNear(supplyChest, 4);
        if (supplyStand == null) {
            deferSupplyChest("原地三分钟且没有其它安全开箱站位");
            return true;
        }
        resetNavigation();
        supplyProgressTick = tick;
        activity = "补货原地三分钟，换角度靠近箱子";
        warning("%s：%s → %s", activity, supplyChest.toShortString(), supplyStand.toShortString());
        return true;
    }

    private void refreshSupplyAmount() {
        if (supplyItem == null) return;
        supplyAmount = Math.min(supplyAmount, supplyTrip.remaining(supplyItem, countInventory(supplyItem), Integer.MAX_VALUE));
    }

    private void resetRestockTransfer() {
        restockInventoryBefore = 0;
        restockTransferAttempts = 0;
        restockTransferTick = -1;
        restockTransferStartedUnfocused = false;
    }

    private void tickVerify() {
        releaseMovement();
        batch.verifyDrawing();
    }

    private boolean isSatisfied(ProjectionTarget target) {
        boolean satisfied = mc.world.isChunkLoaded(target.pos().getX() >> 4, target.pos().getZ() >> 4)
            && batch.observedBlock(target.pos()).equals(target.state())
            && mc.world.getBlockState(target.pos()).equals(target.state());
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
        return Math.max(0, count - batch.reservedSlots());
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
        return capacity + Math.max(0, emptySlots - reservedSlots - batch.reservedSlots()) * maxStack;
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
        if (StorageBlockUtils.storageSlots(mc.player.currentScreenHandler) == 0) return;
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
        if (!mc.world.isChunkLoaded(goal.getX() >> 4, goal.getZ() >> 4)) {
            if (!goal.equals(unloadedTravelGoal) || unloadedTravelProxy == null || hasReached(unloadedTravelProxy)) {
                unloadedTravelGoal = goal.toImmutable();
                double dx = goal.getX()+0.5-mc.player.getX(), dz = goal.getZ()+0.5-mc.player.getZ();
                double length = Math.hypot(dx, dz);
                if (length < 1) return NavigationResult.MOVING;
                unloadedTravelProxy = BlockPos.ofFloored(mc.player.getX()+dx/length*Math.min(8,length), walkY,
                    mc.player.getZ()+dz/length*Math.min(8,length));
            }
            NavigationResult progress = navigateLoaded(unloadedTravelProxy);
            return progress == NavigationResult.ARRIVED ? NavigationResult.MOVING : progress;
        }
        unloadedTravelGoal = unloadedTravelProxy = null;
        return navigateLoaded(goal);
    }

    private NavigationResult navigateLoaded(BlockPos goal) {
        if (tick < navigationReleaseUntil && goal.equals(navigationGoal)) {
            releaseMovement();activity="地毯卡位：松开前进后重新站稳";
            return NavigationResult.MOVING;
        }
        if (navigationNudge != null) {
            if (goal.equals(navigationGoal) && tick < nudgeUntil && !hasReachedPathStep(navigationNudge)
                && canWalkDirectlyTo(navigationNudge)) {
                faceToward(navigationNudge); pressForward(); return NavigationResult.MOVING;
            }
            navigationNudge = null; releaseMovement(); path.clear();
        }
        if (hasReached(goal)) {
            releaseMovement();
            navigationGoal = null;
            return NavigationResult.ARRIVED;
        }
        if (!isStandUsable(goal)) { releaseMovement(); return NavigationResult.UNREACHABLE; }
        boolean differentGoal = navigationGoal == null || !navigationGoal.equals(goal);
        BlockPos pendingStep = pathIndex < path.size() ? path.get(pathIndex) : null;
        boolean carpetTransition = pendingStep != null
            && (mc.world.getBlockState(pendingStep).getBlock() instanceof CarpetBlock
                || mc.world.getBlockState(mc.player.getBlockPos()).getBlock() instanceof CarpetBlock);
        int timeout = carpetTransition ? Math.min(stuckTimeout.get(), 40) : stuckTimeout.get();
        // Placement confirmations update lastProgressTick too. Independently
        // count real walking so those updates cannot conceal a blocked carpet lip.
        boolean stuck = !path.isEmpty() && (tick - lastProgressTick >= timeout
            || walkingProgress.stalled(carpetTransition ? 12 : 20));
        if (differentGoal || path.isEmpty() || stuck) {
            if (differentGoal) {
                resetNavigation();
                navigationGoal = goal.toImmutable();
            } else if (stuck) {
                // A tiny oscillation at a carpet lip is not route progress.
                // Avoid this exact step on the next BFS instead of planning
                // the same blocked edge forever.
                releaseMovement();
                walkingProgress.reset();
                navigationReleaseUntil = tick + 3;
                pathAttempts++;
                if (pathAttempts > routeRetries.get()) return NavigationResult.UNREACHABLE;
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
                // Actually leave the carpet lip before planning again; rotate the escape direction on retries.
                BlockPos feet = new BlockPos(mc.player.getBlockX(), walkY, mc.player.getBlockZ());
                for (int offset = 0; offset < HORIZONTAL_DIRECTIONS.length; offset++) {
                    BlockPos candidate = feet.offset(HORIZONTAL_DIRECTIONS[(pathAttempts + offset) % HORIZONTAL_DIRECTIONS.length]);
                    if (!candidate.equals(pendingStep) && isStandUsable(candidate) && canWalkDirectlyTo(candidate)) {
                        navigationNudge = candidate; nudgeUntil = navigationReleaseUntil + 12;
                        path.clear();pathIndex=0;return NavigationResult.MOVING;
                    }
                }
                // Even without an escape cell, perform the full key release
                // before retrying. Do not press forward again in this tick.
                path.clear();pathIndex=0;return NavigationResult.MOVING;
            }
            if (pathAttempts > routeRetries.get()) { releaseMovement(); return NavigationResult.UNREACHABLE; }
            pathAttempts++;
            if (!planPath(goal)) {
                // A transient carpet lip may have marked the sole edge cell
                // as avoided. Retry once with real collision checks before
                // declaring a narrow route impossible.
                if (avoidedRouteCells.isEmpty()) { releaseMovement(); return NavigationResult.UNREACHABLE; }
                avoidedRouteCells.clear();
                if (!planPath(goal)) { releaseMovement(); return NavigationResult.UNREACHABLE; }
            }
        }
        advanceStraightSweepPath(goal);
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
        pressForward(shouldSprintToSupply(next));
        return NavigationResult.MOVING;
    }

    private void advanceStraightSweepPath(BlockPos goal) {
        if (state != TaskState.WORK || returningFromRestock || finishingLane >= 0
            || footingRecoveryGoal != null || navigationNudge != null || sweepEndpointStand != null
            || snakeEntryWaypoints.contains(snakeWaypointIndex) || snakeWaypointIndex >= snakeWaypoints.size()
            || !goal.equals(snakeWaypoints.get(snakeWaypointIndex))) return;
        int next = SweepStride.lookahead(mc.player.getX(), mc.player.getZ(), goal.getX(), goal.getZ(),
            pathIndex, path.size(), i -> path.get(i).getX(), i -> path.get(i).getZ(),
            i -> path.get(i).getY() == goal.getY() && isStandUsable(path.get(i)) && canWalkDirectlyTo(path.get(i)));
        if (next > pathIndex) {
            pathIndex = next;
            lastPathDistance = Double.POSITIVE_INFINITY;
        }
    }

    private boolean shouldSprintToSupply(BlockPos next) {
        if (!sprintToSupply.get()) return false;
        // Sprint only on the outbound material trip, never while printing,
        // returning to the canvas, opening a chest or performing a recovery nudge.
        if (state != TaskState.RESTOCK || returningFromRestock || supplyChest == null
            || navigationNudge != null || tick < navigationReleaseUntil || supplyOpenTick >= 0
            || mc.player.currentScreenHandler != mc.player.playerScreenHandler
            || !mc.player.isOnGround() || mc.player.isSneaking() || mc.player.isUsingItem()
            || mc.player.isTouchingWater() || mc.player.horizontalCollision
            || !mc.player.isCreative() && mc.player.getHungerManager().getFoodLevel() <= 6) return false;
        // Brake well before interaction range and before short turns. The
        // navigation layer has already smoothed this into a visible safe leg.
        return horizontalDistanceSquared(supplyChest) > 36.0
            && horizontalDistanceSquared(next) > 9.0 && canWalkDirectlyTo(next);
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
                if (avoidedRouteCells.contains(pos) || !mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) || !isStandUsable(pos)) return Double.NaN;
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

    /** An exact border cell may already be occupied; use a nearby connected floor. */
    private BlockPos findSafeEdgeStand(BlockPos focus, int radius) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = focus.getX()-radius; x <= focus.getX()+radius; x++) {
            for (int z = focus.getZ()-radius; z <= focus.getZ()+radius; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (candidate.equals(focus) || !isStandUsable(candidate)
                    || Math.abs(standSurfaceY(candidate)-mc.player.getY()) > 0.6
                    || !canWalkDirectlyTo(candidate)) continue;
                double score = candidate.getSquaredDistance(focus) * 4 + distanceToPlayer(candidate);
                if (score < bestScore) { best = candidate; bestScore = score; }
            }
        }
        return best;
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
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
            || !mc.world.getFluidState(pos).isEmpty() || !mc.world.getFluidState(pos.up()).isEmpty()) return false;
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
        navigationNudge = null;
        navigationReleaseUntil = 0;
        walkingProgress.reset();
    }

    /** A chosen escape must finish before nearby placement can steal its movement. */
    private boolean serviceNavigationRecovery() {
        if (navigationGoal == null || (navigationNudge == null && tick >= navigationReleaseUntil)) return false;
        if (batchPlacementPending()) { releaseMovement(); return true; }
        NavigationResult result = navigateTo(navigationGoal);
        if (result != NavigationResult.MOVING) {
            navigationNudge=null;navigationReleaseUntil=0;releaseMovement();
        }
        return true;
    }

    private void pressForward() {
        pressForward(false);
    }

    private void pressForward(boolean sprintToSupply) {
        if (mc.options == null) return;
        setKeyPressed(mc.options.forwardKey, true);
        ownsForwardKey = true;
        if (sprintToSupply) {
            setKeyPressed(mc.options.sprintKey, true);
            ownsSupplySprintKey = true;
            mc.player.setSprinting(true);
        } else {
            releaseSupplySprint();
            // Construction and precise approaches retain normal walking.
            if (mc.player != null && mc.player.isSprinting()) mc.player.setSprinting(false);
        }
    }

    private void releaseSupplySprint() {
        if (!ownsSupplySprintKey) return;
        if (mc.options != null) setKeyPressed(mc.options.sprintKey, false);
        if (mc.player != null) mc.player.setSprinting(false);
        ownsSupplySprintKey = false;
    }

    private void releaseMovement() {
        releaseSupplySprint();
        sweepStride.reset();
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
        sweepCollecting = false;
        restockBandDemand = Map.of();
        restockSurplus.stop(); surplusRestockTarget = null; skipRestockSurplusOnce = false; nextSurplusCheckTick = 0;
        clearPreciseRepair();repairVisits.clear();deferredBandRepairs.clear();bandSupplyAttempts.clear();supplyWindowLanes.clear();openingBands.reset();boundaryEntryStand=sweepEndpointStand=null;
        observationCursor = 0;finishingLane = -1;deferredResumeTargets.clear();resumeSupplyPending = returningToCheckpoint = false;
        navigationNudge = unloadedTravelGoal = unloadedTravelProxy = null;
        ownedSupplyHandler = null;
        supplyInventorySyncId=-1;supplyInventoryReadyTick=-1;
        cursorSpaceAttempts = 0; cursorSpaceCount = -1; cursorSpaceItem = null;
        cursorSpaceTarget = null; cursorSpaceStand = cursorSpaceOrigin = null;
        cursorSpaceReturning = false; cursorSpacePlanAt = 0; cursorSpaceRejected.clear(); cursorSpaceRejectedStands.clear();
        cancelQueuedPlacements();
        workingBandWidth = 1;
        workOrigin = null;
        workGoalTarget = null;
        restockReturnPos = null;
        returningFromRestock = false;
        supplyCheckedLane = -1;
        nextLocalSupplyCheckTick = 0;
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
        minimumTakeCapacity.clear();
        supplyTrip.clear();
        resetNavigation();
        targetIndex = 0;
        verificationIndex = 0;
        verificationPass = 0;
        supplyItem = null;
        supplyAmount = 0;
        reservedSupplySlots = 0;
        supplyReceivedThisRound = 0;
        supplyLastTakeTick = -1;
        supplyChest = null;
        supplyChestLabel = null;
        supplyStand = null;
        rejectedSupplyStands.clear();
        stationaryRestock.reset();
        stationaryRestockRecoveries = 0;
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
        supplyRetryAt = 0;
        unreachableSupplyUntil.clear();
        producerSupplyReserve.clear();
        nextActionTick = 0;
        reviewingRepair = false;
        reviewAttempted.clear();
        reviewPass = 0;
        reviewRestocking = false;
        reviewSupplyTried.clear();
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
        return (batch.printing() ? batch.status() + " " : "") + activity + " [" + completedMatrixCells() + "/" + targets.size() + "]";
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


    private Setting<String> batchPosition(String name, String description) {
        return sgLocations.add(new StringSetting.Builder().name(name).description(description).defaultValue("").build());
    }

    private Setting<Boolean> batchSelector(String name, BatchLocation location) {
        return sgLocations.add(new BoolSetting.Builder().name(name)
            .description("隐藏设置后右键目标方块登记坐标，按 Esc 返回。登记操作不会同时使用该方块。")
            .defaultValue(false).onChanged(enabled -> { if (enabled) beginBatchSelection(location); }).build());
    }

    private void onBatchReset(boolean enabled) {
        if (!enabled) return;
        resetBatch.set(false);
        validateBatchDirectory(null);
    }

    private void beginBatchSelection(BatchLocation location) {
        if (mc.player == null || mc.world == null) { clearBatchSelectors(); return; }
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            clearBatchSelectors(); warning("请先放回鼠标持有的物品"); return;
        }
        batch.stop();
        releaseMovement();
        Setting<Boolean> current = switch (location) {
            case CANVAS -> selectCanvas; case LEVER -> selectLever; case MAPS -> selectMaps;
            case TABLE -> selectCartography; case PRODUCT -> selectProduct;
            default -> throw new IllegalArgumentException("Unknown location mode");
        };
        for (Setting<Boolean> selector : List.of(selectCanvas, selectLever, selectMaps, selectCartography, selectProduct))
            if (selector != current && selector.get()) selector.set(false);
        if (registerSupply.get()) registerSupply.set(false);
        batchLocation = location;
        if (!isActive()) toggle();
        if (!isActive()) return;
        state = TaskState.REGISTERING;
        mc.setScreen(null);
    }

    private void clearBatchSelectors() {
        batchLocation = BatchLocation.NONE;
        for (Setting<Boolean> selector : List.of(selectCanvas, selectLever, selectMaps, selectCartography, selectProduct))
            if (selector.get()) selector.set(false);
    }

    private void selectBatchPosition() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)) return;
        BlockPos position = hit.getBlockPos().toImmutable();
        Setting<String> setting;
        switch (batchLocation) {
            case CANVAS -> { position = position.up(); setting = canvasOrigin; }
            case LEVER -> {
                if (!mc.world.getBlockState(position).isOf(net.minecraft.block.Blocks.LEVER)) { warning("请右键点击拉杆"); return; }
                setting = clearLever;
            }
            case TABLE -> {
                if (!mc.world.getBlockState(position).isOf(net.minecraft.block.Blocks.CARTOGRAPHY_TABLE)) { warning("请右键点击制图台"); return; }
                setting = cartographyTable;
            }
            case MAPS, PRODUCT -> {
                if (!StorageBlockUtils.isStorage(mc, position)) { warning("请右键点击箱子、木桶或潜影盒"); return; }
                setting = batchLocation == BatchLocation.MAPS ? mapSupply : productStorage;
            }
            default -> { return; }
        }
        setting.set(StorageBlockUtils.position(position));
        info("%s 已登记：%s", setting.title, position.toShortString());
    }

    @EventHandler
    private void onBatchPacket(PacketEvent.Receive event) {
        if (event.packet instanceof net.minecraft.network.packet.s2c.play.InventoryS2CPacket inventory
            && inventory.syncId() > 0) supplyInventorySyncId=inventory.syncId();
        batch.receive(event.packet);
    }

    @Override
    public MapArtBatchController.Configuration batchConfiguration() {
        return new MapArtBatchController.Configuration(batchFolder.get(),
            StorageBlockUtils.parsePosition(canvasOrigin.get()), StorageBlockUtils.parsePosition(clearLever.get()),
            StorageBlockUtils.parsePosition(mapSupply.get()), StorageBlockUtils.parsePosition(cartographyTable.get()),
            StorageBlockUtils.parsePosition(productStorage.get()),
            colorFilterEnabled.get() ? "true:" + excludedColors.get().stream().sorted().toList() : "false");
    }

    @Override public boolean batchExcluded(BlockState block) { return isExcludedColor(block); }

    @Override
    public String batchPreflight() {
        int empty = 0;
        for (int i=0; i<36; i++) if (mc.player.getInventory().getStack(i).isEmpty()) empty++;
        if (empty < 2 && RestockSurplusController.carpetMaterials().stream().noneMatch(item -> countInventory(item) > 0))
            return "开始清理前请为本轮地图和玻璃板预留两个空背包槽";
        Map<Item, Integer> demand = new HashMap<>();
        for (ProjectionTarget target : projectionLayer) {
            if (!isExcludedColor(target.state())) demand.merge(target.state().getBlock().asItem(), 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> entry : demand.entrySet()) {
            if (countInventory(entry.getKey()) < entry.getValue() && supplies.getOrDefault(entry.getKey(), List.of()).isEmpty())
                return "尚未登记所需颜色的材料箱：" + entry.getKey().getName().getString() + "；尚未清理画布";
        }
        return null;
    }

    @Override
    public void batchPrepare(BatchPainting painting) {
        resetRuntime();
        progressTimer.clearDrawing();
        minX = painting.minX(); maxX = minX + 127;
        minZ = painting.minZ(); maxZ = minZ + 127;
        walkY = painting.y();
        workOrigin = mc.player.getBlockPos().toImmutable();
        for (int z=0; z<128; z++) for (int x=0; x<128; x++) {
            BlockState required = painting.at(x,z);
            if (!required.isAir()) projectionLayer.add(new ProjectionTarget(painting.position(x,z),required,0));
        }
        rebuildFilteredMatrix();
        state = TaskState.BATCH;
    }

    @Override
    public void batchStartDrawing() {
        progressTimer.beginDrawing(System.nanoTime(), targets.size(), completedMatrixCells());
        state = TaskState.WORK;
        supplyCheckedLane = -1;
        verificationPass = 0;
        buildSnakeWaypoints();
        activity = "开工准备：补货时清出无关颜色，再按条带备货和铺设";
    }

    @Override
    public BatchPrintCheckpoint batchDrawingCheckpoint() {
        if (mc.player == null || snakeWaypoints.isEmpty() || snakeWaypoints.size() != snakeWaypointLanes.size()) return null;
        List<BatchPrintCheckpoint.Step> route = new ArrayList<>();
        for (int i=0; i<snakeWaypoints.size(); i++) {
            BlockPos point = snakeWaypoints.get(i);
            route.add(new BatchPrintCheckpoint.Step(point.getX(), point.getZ(), snakeWaypointLanes.get(i), snakeEntryWaypoints.contains(i)));
        }
        BlockPos origin = workOrigin == null ? mc.player.getBlockPos() : workOrigin;
        // During a supply trip resume the saved working position, not the chest.
        BlockPos resume = restockReturnPos != null ? restockReturnPos
            : cursorSpaceOrigin == null ? mc.player.getBlockPos() : cursorSpaceOrigin;
        return new BatchPrintCheckpoint(workingBandWidth, reverseSnake.get(), snakeWaypointIndex,
            origin.getX(), origin.getZ(), resume.getX(), resume.getZ(), verificationPass, route,
            resumeSupplyPending || state == TaskState.RESTOCK || state == TaskState.WAITING_SUPPLY_RECHECK || state == TaskState.WAITING_CAPACITY,
            false, 0);
    }

    @Override
    public void batchResumeDrawing(BatchPrintCheckpoint checkpoint) {
        progressTimer.beginDrawing(System.nanoTime(), targets.size(), completedMatrixCells());
        if (checkpoint == null) {
            batchStartDrawing();
            info("断点续铺：旧进度没有路点记录，已根据实际方块重建剩余工作线，不重新清理画布");
            return;
        }
        workOrigin = new BlockPos(checkpoint.originX(), walkY, checkpoint.originZ());
        if (checkpoint.width() != workingBandWidth || checkpoint.reverse() != reverseSnake.get()) {
            batchStartDrawing();
            info("断点续铺：条带宽度或方向已调整，保留原起点并按新参数重建剩余路线");
            return;
        }
        for (var step : checkpoint.route()) {
            if (step.x() < minX-1 || step.x() > maxX+1 || step.z() < minZ-1 || step.z() > maxZ+1
                || step.lane() >= (maxZ-minZ+workingBandWidth)/workingBandWidth)
                throw new IllegalArgumentException("保存的蛇形路点超出本轮画布，已暂停，不重新清理");
        }
        snakeWaypoints.clear();snakeWaypointLanes.clear();snakeEntryWaypoints.clear();
        for (var step : checkpoint.route()) {
            if (step.entry()) snakeEntryWaypoints.add(snakeWaypoints.size());
            snakeWaypoints.add(new BlockPos(step.x(), walkY, step.z()));
            snakeWaypointLanes.add(step.lane());
        }
        snakeWaypointIndex = checkpoint.cursor();
        verificationPass = checkpoint.verificationPass();
        supplyCheckedLane = -1;
        snakeSweepCompleted = false;
        state = TaskState.WORK;
        openingBands.reset();
        supplyWindowLanes.clear();
        deferredResumeTargets.clear();
        int overlap = (int) Math.ceil(placeRange.get());
        for (ProjectionTarget target : targets) {
            if (checkpoint.precedesResume(target.lane(), target.pos().getX(), overlap))
                deferredResumeTargets.add(target.pos());
        }
        resetNavigation();
        if (snakeWaypointIndex >= snakeWaypoints.size()) {
            state = TaskState.VERIFY;
            activity = "原蛇形工作线已走完，继续最终查漏";
        } else {
            restockReturnPos = new BlockPos(checkpoint.resumeX(), walkY, checkpoint.resumeZ());
            returningToCheckpoint = returningFromRestock = true;
            resumeSupplyPending = checkpoint.restocking();
            activity = "断点续铺：条带 " + (snakeWaypointLanes.get(snakeWaypointIndex) + 1);
            info("断点续铺：保留路点 %d/%d，%s返回 %s 继续；前段缺口留到最终查漏。",
                snakeWaypointIndex + 1, snakeWaypoints.size(), resumeSupplyPending ? "按现有背包补货后" : "直接",
                restockReturnPos.toShortString());
        }
    }

    @Override
    public void batchRestoreReviewContext(BatchPrintCheckpoint checkpoint) {
        if (checkpoint == null) return;
        workOrigin = new BlockPos(checkpoint.originX(), walkY, checkpoint.originZ());
        verificationPass = checkpoint.verificationPass();
    }

    @Override
    public void batchStopWorker() {
        restockSurplus.stop(); surplusRestockTarget = null;
        releaseMovement();
        cancelQueuedPlacements();
        if (state == TaskState.RESTOCK && mc.player != null && supplyOpenTick >= 0) closeSupplyScreen();
        resetNavigation();
        state = TaskState.BATCH;
    }

    @Override
    public boolean batchPlacementPending() {
        checkDispatchedPlacements();
        return printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()
            || tick - lastPlacementDispatchTick < 2;
    }
    @Override public boolean batchWorkSweepFinished() { return state == TaskState.VERIFY; }
    @Override public void batchHoldForReview() { releaseMovement(); }
    @Override public boolean batchOwnsContainer() {
        return state == TaskState.RESTOCK && supplyChest != null && supplyOpenTick >= 0
                && mc.player.currentScreenHandler == ownedSupplyHandler;
    }
    @Override public boolean batchCursorStorageAllowed(ItemStack stack) {
        return batchOwnsContainer() && supplyItem != null && stack.isOf(supplyItem);
    }
    @Override public boolean batchFreeCursorSpace() { return freeCursorSpace(); }
    @Override public void batchCursorRecovered() {
        cursorSpaceAttempts = 0; cursorSpaceCount = -1; cursorSpaceItem = null;
        cursorSpaceTarget = null; cursorSpaceStand = null; cursorSpaceRejected.clear(); cursorSpaceRejectedStands.clear();
        cursorSpaceReturning = cursorSpaceOrigin != null && !hasReached(cursorSpaceOrigin);
        if (!cursorSpaceReturning) cursorSpaceOrigin = null;
        resetNavigation();
        resetRestockTransfer();
        supplyProgressTick = tick;
        supplyBestDistance = supplyChest == null ? Double.POSITIVE_INFINITY : distanceToPlayer(supplyChest);
    }
    @Override public boolean batchReturnAfterCursorRecovery() {
        if (!cursorSpaceReturning || cursorSpaceOrigin == null) return false;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return false;
        NavigationResult result = navigateTo(cursorSpaceOrigin);
        if (result == NavigationResult.MOVING) return true;
        cursorSpaceReturning = false; cursorSpaceOrigin = null; resetNavigation();
        if (result == NavigationResult.UNREACHABLE) warning("原腾位断点暂不可达，将从保存的下一蛇形路点继续。");
        return false;
    }
    @Override public void batchBeginReviewRepairs(int pass) {
        deferredResumeTargets.clear();
        resumeSupplyPending = returningToCheckpoint = false;
        reviewPass = pass;
        reviewRestocking = false;
        reviewSupplyTried.clear();
        blockedTargets.clear();
        reviewAttempted.clear();
        clearPreciseRepair();repairVisits.clear();deferredBandRepairs.clear();bandSupplyAttempts.clear();supplyWindowLanes.clear();finishingLane=-1;boundaryEntryStand=sweepEndpointStand=null;
        placementStandTarget = footingRecoveryGoal = null;
        placementStandSince = -1;
        // Do not turn unloaded, already-built strips into false material
        // demand. Newly missing cells re-enter the matrix as we walk past.
        for (ProjectionTarget target : targets) {
            BlockPos pos = target.pos();
            if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState actual = mc.world.getBlockState(pos);
            if (!isSatisfied(target) && !actual.isAir() && !actual.isReplaceable()) blockedTargets.add(pos);
        }
    }
    @Override public boolean batchRepairNearby() {
        // Repair sweeps may replenish materials, but never fall back to the
        // ordinary PRINT loop or wait indefinitely for an unavailable color.
        if (reviewRestocking) {
            if (state == TaskState.RESTOCK) tickRestock();
            else if (returningFromRestock) tickReturnFromRestock();
            if (state == TaskState.RESTOCK || returningFromRestock) return true;
            reviewRestocking = false;
            closeSupplyScreen();
            restockReturnPos = null;
            resetNavigation();
            state = TaskState.BATCH;
            return true;
        }
        if (mc.currentScreen instanceof Generic3x3ContainerScreen
            && mc.player.currentScreenHandler instanceof Generic3x3ContainerScreenHandler) {
            mc.player.closeHandledScreen();
            return true;
        }
        checkDispatchedPlacements();
        if (printer.hasQueuedProjectionPlacement() || !dispatchedPlacements.isEmpty()) {
            releaseMovement();
            return true;
        }
        if (serviceNavigationRecovery()) return true;
        if (footingRecoveryGoal != null) {
            NavigationResult result = navigateTo(footingRecoveryGoal);
            if (result != NavigationResult.MOVING) { footingRecoveryGoal = null; resetNavigation(); }
            return true;
        }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler || preciseRepairTarget != null) return false;
        if (serviceRestockSurplusForCapacity()) { reviewRestocking = true; return true; }
        ProjectionTarget best = null;
        ProjectionTarget missingMaterial = null;
        double nearest = Double.POSITIVE_INFINITY;
        double nearestMissing = Double.POSITIVE_INFINITY;
        int radius = (int) Math.ceil(placeRange.get());
        BlockPos feet = mc.player.getBlockPos();
        for (int x = feet.getX()-radius; x <= feet.getX()+radius; x++) {
            for (int z = feet.getZ()-radius; z <= feet.getZ()+radius; z++) {
                BlockPos pos = new BlockPos(x,walkY,z);
                Integer index = targetIndices.get(pos);
                if (index == null || blockedTargets.contains(pos) || reviewAttempted.contains(pos)
                    || !mc.world.isChunkLoaded(x>>4,z>>4)) continue;
                ProjectionTarget candidate = targets.get(index);
                // Empty/replaceable cells are missing; never break a wrongly occupied solid block.
                BlockState actual = batch.observedBlock(pos);
                if ((!actual.isAir() && !actual.isReplaceable()) || candidate.state().isAir()
                    || !isWithinPlaceRange(candidate)
                    || playerIntersectsTarget(candidate)) continue;
                if (isSatisfied(candidate)) continue; // Refresh the cache from the real block before choosing a repair.
                double distance = mc.player.getEyePos().squaredDistanceTo(placementAim(candidate));
                Item item = candidate.state().getBlock().asItem();
                if (!hasItem(item)) {
                    if (!reviewSupplyTried.contains(item) && hasUsableSupply(item)
                        && inventoryCapacity(item, 0) > 0 && distance < nearestMissing) {
                        nearestMissing = distance;
                        missingMaterial = candidate;
                    }
                    continue;
                }
                if (distance < nearest) { nearest = distance; best = candidate; }
            }
        }
        if (missingMaterial != null) {
            reviewRestocking = true;
            beginRestock(missingMaterial);
            // A reserved-slot/full-stack constraint can reject a trip before
            // selectNextSupply chooses anything. Do not restart that no-op at
            // the same empty cell on every tick of this pass.
            if (state != TaskState.RESTOCK) reviewSupplyTried.add(missingMaterial.state().getBlock().asItem());
            return true;
        }
        if (best == null) return false;
        reviewingRepair = true;
        try { placeTarget(best); }
        finally { reviewingRepair = false; }
        return true;
    }
    @Override public void batchObserveBlock(BlockPos pos) {
        Integer index = targetIndices.get(pos);
        if (index != null) isSatisfied(targets.get(index));
    }
    @Override public boolean batchRepairEligible(BlockPos pos) {
        return targetIndices.containsKey(pos) && repairVisits.getOrDefault(pos, 0) < 3;
    }
    @Override public boolean batchRepairTarget(BlockPos pos) {
        Integer index = targetIndices.get(pos);
        if (index == null) return false;
        ProjectionTarget target = targets.get(index);
        if (isSatisfied(target)) { clearPreciseRepair(); return false; }
        Item item = target.state().getBlock().asItem();
        if (!hasItem(item)) {
            if (!reviewSupplyTried.contains(item) && hasUsableSupply(item) && inventoryCapacity(item, 0) > 0) {
                reviewRestocking = true;beginRestock(target);
                if (state != TaskState.RESTOCK) reviewSupplyTried.add(item);
                return true;
            }
            clearPreciseRepair(); return false;
        }
        return tickPreciseRepair(target);
    }
    @Override public int batchReviewWidth() { return workingBandWidth; }
    @Override public boolean batchReviewReverse() { return reverseSnake.get() ^ ((reviewPass & 1) != 0); }
    @Override public int batchReviewOriginX() { return workOrigin == null ? 0 : workOrigin.getX() - minX; }
    @Override public int batchReviewOriginZ() { return workOrigin == null ? 0 : workOrigin.getZ() - minZ; }
    @Override public int batchAllowedMissingBlocks() { return allowedMissingBlocks.get(); }
    @Override public int batchLane() { return snakeWaypointIndex < snakeWaypointLanes.size() ? snakeWaypointLanes.get(snakeWaypointIndex) : -1; }
    @Override public boolean batchWorkerPaused() { return state == TaskState.PAUSED; }
    @Override public void batchPauseWorker(String reason) {
        releaseMovement(); restockSurplus.stop(); surplusRestockTarget = null;
        state = TaskState.PAUSED; activity = reason;
    }
    @Override public void batchStatus(String text) { activity = text; }
    @Override public void batchMessage(String text) {
        info(Text.empty()
            .append(Text.literal(batch.queueProgress() + " | ").formatted(Formatting.LIGHT_PURPLE))
            .append(Text.literal(text).formatted(
                text.contains("暂停") || text.contains("失败") ? Formatting.RED
                    : text.contains("不可达") || text.contains("未读取") ? Formatting.YELLOW
                    : text.contains("已确认入箱") || text.contains("全部文件已完成") ? Formatting.GREEN : Formatting.AQUA)));
    }
    @Override public void batchFinish() { disable(); }

    private enum NavigationResult { MOVING, ARRIVED, UNREACHABLE }

    private enum TaskState { IDLE, REGISTERING, WORK, RESTOCK, VERIFY, WAITING_SUPPLY_RECHECK, WAITING_CAPACITY, PAUSED, BATCH }
    private enum BatchLocation { NONE, CANVAS, LEVER, MAPS, TABLE, PRODUCT }
}
