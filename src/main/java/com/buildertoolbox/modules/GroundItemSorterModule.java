package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/** Collects ground drops on the starting floor and stores only newly picked-up items. */
public final class GroundItemSorterModule extends Module {
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final long SKIP_TICKS = 20L * 10L;
    private static final double FLOOR_TOLERANCE = 0.45;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final Setting<Integer> searchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("Search Radius")
        .description("Horizontal search around the player's starting position.")
        .defaultValue(64).range(8, 256).sliderRange(16, 128).build());
    private final Setting<Boolean> selectStorage = sgStorage.add(new BoolSetting.Builder()
        .name("Select Storage Chests")
        .description("Right-click any chest, barrel or shulker box to register it; press Esc to return.")
        .defaultValue(false).onChanged(this::onSelectStorageChanged).build());
    private final Setting<List<String>> storageList = sgStorage.add(new StringListSetting.Builder()
        .name("Storage Chest List")
        .description("All registered containers are shared; collected items can be mixed in them. Use + to edit.")
        .defaultValue(new String[0]).onChanged(this::restoreStorageList).build());
    private final Setting<Boolean> resumeSorting = sgStorage.add(new BoolSetting.Builder()
        .name("Resume Collection")
        .description("Retry after adding storage or freeing inventory space.")
        .defaultValue(false).onChanged(this::onResumeChanged).build());

    private final List<BlockPos> storage = new ArrayList<>();
    private final Map<Item, Integer> cargo = new HashMap<>();
    private final Map<Item, Integer> observedInventory = new HashMap<>();
    private final Set<Item> nearbyDropItems = new HashSet<>();
    private final Map<UUID, Long> skippedDrops = new HashMap<>();
    private final Set<BlockPos> triedChests = new HashSet<>();
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private final List<BlockPos> path = new ArrayList<>();

    private State state = State.PAUSED;
    private BlockPos origin;
    private int walkY;
    private double collectionY;
    private ItemEntity targetDrop;
    private Item targetItem;
    private long nearDropTick = -1;
    private long approachTick = -1;
    private BlockPos activeChest;
    private BlockPos chestStand;
    private Item depositItem;
    private BlockPos navigationGoal;
    private int pathIndex;
    private int routeFailures;
    private int openAttempts;
    private int previousSyncId;
    private int transferBefore;
    private int transferFailures;
    private long transferTick = -1;
    private long openTick = -1;
    private long aimTick = -1;
    private long tick;
    private long lastProgressTick;
    private long lastMovementReleaseTick = -100;
    private long lastDropSeenTick;
    private double bestStepDistance = Double.POSITIVE_INFINITY;
    private boolean ownsForward;
    private boolean updatingStorage;
    private String activity = "空闲";

    public GroundItemSorterModule() {
        super(BuilderToolboxAddon.CATEGORY, "Ground Item Collector",
            "Collects drops without leaving the starting floor, then stores them in shared registered containers.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (storage.isEmpty() && !storageList.get().isEmpty()) restoreStorageList(storageList.get());
        origin = mc.player.getBlockPos().toImmutable();
        walkY = origin.getY();
        collectionY = mc.player.getY();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                observedInventory.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        refreshNearbyDrops();
        if (selectStorage.get()) {
            state = State.REGISTERING;
            activity = "右键登记箱子，Esc 返回设置";
        } else if (storage.isEmpty()) {
            state = State.PAUSED;
            warning("请先登记存储箱。", new Object[0]);
        } else {
            state = State.COLLECTING;
            activity = "搜索同一高度的掉落物";
        }
    }

    @Override
    public void onDeactivate() {
        if (!cargo.isEmpty()) warning("尚有 %d 种本轮拾取的物品留在背包中。", cargo.size());
        closeChest();
        releaseMovement();
        if (selectStorage.get()) selectStorage.set(false);
        resetRuntime();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        tick++;
        skippedDrops.entrySet().removeIf(entry -> entry.getValue() <= tick);
        trackInventoryPickups();
        refreshNearbyDrops();
        if (state == State.REGISTERING || state == State.PAUSED) { releaseMovement(); return; }
        if (Math.abs(mc.player.getY() - collectionY) > FLOOR_TOLERANCE) {
            pause("玩家已离开起始高度，停止移动；不会下楼或爬升");
            return;
        }
        if (mc.currentScreen != null && currentContainer() == null) {
            releaseMovement();
            activity = "其他界面打开，已暂停操作";
            return;
        }
        if (state == State.COLLECTING) tickCollect();
        else tickDeposit();
    }

    private void tickCollect() {
        if (targetDrop != null && targetItem != null && targetDrop.getStack().getItem() != targetItem) clearTarget();
        if (!isEligibleDrop(targetDrop)) clearTarget();
        if (targetDrop == null) {
            targetDrop = nearestDrop();
            if (targetDrop != null) {
                targetItem = targetDrop.getStack().getItem();
                lastDropSeenTick = tick;
            }
        }
        if (targetDrop == null) {
            releaseMovement();
            if (!cargo.isEmpty()) { beginDeposit(); return; }
            if (tick - lastDropSeenTick >= 100) finishCollection();
            else activity = "等待同层掉落物";
            return;
        }
        ItemStack stack = targetDrop.getStack();
        if (inventoryCapacity(stack) <= 0) {
            if (!cargo.isEmpty()) beginDeposit();
            else pause("背包已满，无法拾取掉落物");
            return;
        }
        Vec3d dropPos = new Vec3d(targetDrop.getX(), targetDrop.getY(), targetDrop.getZ());
        double dx = mc.player.getX() - dropPos.x;
        double dz = mc.player.getZ() - dropPos.z;
        if (dx * dx + dz * dz <= 0.35 * 0.35) {
            releaseMovement();
            if (nearDropTick < 0) nearDropTick = tick;
            if (tick - nearDropTick > 40) skipDrop();
            else activity = "等待拾取 " + stack.getName().getString();
            return;
        }
        nearDropTick = -1;
        if (approachTick >= 0) {
            BlockPos cell = new BlockPos(MathHelper.floor(dropPos.x), walkY, MathHelper.floor(dropPos.z));
            if (tick - approachTick > 40 || !isStandUsable(cell)
                || !isSafeDirectApproach(dropPos)) { skipDrop(); return; }
            faceToward(dropPos);
            pressForward();
            return;
        }
        BlockPos stand = findDropStand(targetDrop);
        if (stand == null) { skipDrop(); return; }
        RouteResult route = navigateTo(stand);
        if (route == RouteResult.UNREACHABLE) { skipDrop(); return; }
        if (route == RouteResult.ARRIVED) {
            approachTick = tick;
            releaseMovement();
        } else activity = "前往掉落物 " + stack.getName().getString();
    }

    private void trackInventoryPickups() {
        Map<Item, Integer> current = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) current.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        if (state == State.COLLECTING || state == State.DEPOSITING) {
            current.forEach((item, count) -> {
                int gained = count - observedInventory.getOrDefault(item, 0);
                if (gained > 0 && nearbyDropItems.contains(item)) {
                    cargo.merge(item, gained, Integer::sum);
                    lastDropSeenTick = tick;
                    activity = "已拾取 " + gained + " 个 " + item.getName().getString();
                }
            });
        }
        observedInventory.clear();
        observedInventory.putAll(current);
    }

    private void refreshNearbyDrops() {
        nearbyDropItems.clear();
        if (mc.player == null || mc.world == null) return;
        Box aroundPlayer = mc.player.getBoundingBox().expand(2.5);
        for (ItemEntity drop : mc.world.getEntitiesByClass(ItemEntity.class, aroundPlayer,
            entity -> entity.isAlive() && !entity.getStack().isEmpty()
                && Math.abs(entity.getY() - collectionY) <= FLOOR_TOLERANCE)) {
            nearbyDropItems.add(drop.getStack().getItem());
        }
    }

    private ItemEntity nearestDrop() {
        int radius = searchRadius.get();
        Box area = new Box(origin.getX() - radius, collectionY - FLOOR_TOLERANCE, origin.getZ() - radius,
            origin.getX() + radius + 1, collectionY + FLOOR_TOLERANCE, origin.getZ() + radius + 1);
        return mc.world.getEntitiesByClass(ItemEntity.class, area, this::isEligibleDrop).stream()
            .min(Comparator.comparingDouble(drop -> drop.squaredDistanceTo(mc.player))).orElse(null);
    }

    private boolean isEligibleDrop(ItemEntity drop) {
        if (drop == null || !drop.isAlive() || skippedDrops.containsKey(drop.getUuid())) return false;
        ItemStack stack = drop.getStack();
        if (stack.isEmpty()) return false;
        if (Math.abs(drop.getY() - collectionY) > FLOOR_TOLERANCE) return false;
        int radius = searchRadius.get();
        return Math.abs(drop.getX() - (origin.getX() + 0.5)) <= radius
            && Math.abs(drop.getZ() - (origin.getZ() + 0.5)) <= radius;
    }

    private void skipDrop() {
        if (targetDrop != null) skippedDrops.put(targetDrop.getUuid(), tick + SKIP_TICKS);
        clearTarget();
        lastDropSeenTick = tick;
        activity = "暂时跳过无法接近的掉落物";
    }

    private void clearTarget() {
        targetDrop = null;
        targetItem = null;
        nearDropTick = -1;
        approachTick = -1;
        resetNavigation();
    }

    private BlockPos findDropStand(ItemEntity drop) {
        BlockPos center = new BlockPos(MathHelper.floor(drop.getX()), walkY, MathHelper.floor(drop.getZ()));
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
            for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (!isStandUsable(candidate)) continue;
                double dx = x + 0.5 - drop.getX();
                double dz = z + 0.5 - drop.getZ();
                double score = (dx * dx + dz * dz) * 4 + distanceToPlayer(candidate) * 0.01;
                if (score < bestScore) { best = candidate; bestScore = score; }
            }
        }
        return best;
    }

    private boolean isSafeDirectApproach(Vec3d target) {
        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz) / 0.2));
        for (int i = 1; i <= samples; i++) {
            double fraction = (double) i / samples;
            BlockPos feet = new BlockPos(MathHelper.floor(mc.player.getX() + dx * fraction), walkY,
                MathHelper.floor(mc.player.getZ() + dz * fraction));
            if (!isStandUsable(feet)) return false;
        }
        return true;
    }

    private void beginDeposit() {
        clearTarget();
        releaseMovement();
        state = State.DEPOSITING;
        depositItem = null;
        activity = "开始存放本轮拾取的物品";
    }

    private void tickDeposit() {
        if (depositItem == null || cargo.getOrDefault(depositItem, 0) <= 0) {
            closeChest();
            clearChest();
            depositItem = nextCargoItem();
            triedChests.clear();
            if (depositItem == null) {
                state = State.COLLECTING;
                lastDropSeenTick = tick;
                activity = "存放完成，继续捡物品";
                return;
            }
        }
        if (activeChest == null) {
            BlockPos next = storage.stream().filter(pos -> !triedChests.contains(pos) && isContainer(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
            if (next == null) {
                pause("登记的存储箱均不可用或已满；剩余 " + depositItem.getName().getString() + " 留在背包");
                return;
            }
            triedChests.add(next);
            selectChest(next);
        }
        ScreenHandler opened = tickOpenChest();
        if (opened == null) return;
        if (tickTransfer()) return;
        int containerSlots = containerSlots(opened);
        Identifier expectedId = Registries.ITEM.getId(depositItem);
        for (int slot = containerSlots; slot < opened.slots.size(); slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (stack.isEmpty() || !expectedId.equals(Registries.ITEM.getId(stack.getItem()))) continue;
            int allowed = cargo.getOrDefault(depositItem, 0);
            if (allowed <= 0) break;
            transferBefore = countInventory(depositItem);
            if (stack.getCount() > allowed) {
                if (!transferPartialStack(opened, slot, allowed, containerSlots)) {
                    nextChest();
                    return;
                }
            } else InvUtils.shiftClick().slotId(slot);
            transferTick = tick;
            activity = "向箱子存放 " + depositItem.getName().getString();
            return;
        }
        if (countInventory(depositItem) == 0) cargo.remove(depositItem);
        else nextChest();
    }

    private boolean tickTransfer() {
        if (transferTick < 0) return false;
        int moved = transferBefore - countInventory(depositItem);
        if (moved > 0) {
            cargo.computeIfPresent(depositItem, (item, amount) -> amount > moved ? amount - moved : null);
            transferTick = -1;
            transferFailures = 0;
            activity = "已存放 " + moved + " 个 " + depositItem.getName().getString();
            return true;
        }
        if (tick - transferTick < 12) return true;
        transferTick = -1;
        if (++transferFailures >= 2) {
            warning("箱子 %s 无法收下 %s，尝试下一只。", activeChest.toShortString(), depositItem.getName().getString());
            transferFailures = 0;
            nextChest();
        }
        return true;
    }

    /** Move only the newly collected count when it merged into a pre-existing inventory stack. */
    private boolean transferPartialStack(ScreenHandler opened, int sourceSlot, int allowed, int containerSlots) {
        if (mc.interactionManager == null || !opened.getCursorStack().isEmpty()) return false;
        ItemStack source = opened.getSlot(sourceSlot).getStack();
        int destination = -1;
        int space = 0;
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stored = opened.getSlot(slot).getStack();
            if (!opened.getSlot(slot).canInsert(source)) continue;
            if (!stored.isEmpty() && !ItemStack.areItemsAndComponentsEqual(source, stored)) continue;
            int room = source.getMaxCount() - stored.getCount();
            if (room > space) { destination = slot; space = room; }
        }
        if (destination < 0 || space <= 0) return false;
        int amount = Math.min(allowed, space);
        mc.interactionManager.clickSlot(opened.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        for (int i = 0; i < amount; i++) {
            mc.interactionManager.clickSlot(opened.syncId, destination, 1, SlotActionType.PICKUP, mc.player);
        }
        mc.interactionManager.clickSlot(opened.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    private Item nextCargoItem() {
        return cargo.entrySet().stream().filter(entry -> entry.getValue() > 0)
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .map(Map.Entry::getKey).orElse(null);
    }

    private void finishCollection() {
        releaseMovement();
        int remaining = countRemainingDrops();
        if (remaining > 0) warning("本轮结束时，同层区域仍有 %d 个掉落物暂时不可达。", remaining);
        else info("同层掉落物已收集并存放完成。", new Object[0]);
        disable();
    }

    private int countRemainingDrops() {
        int radius = searchRadius.get();
        Box area = new Box(origin.getX() - radius, collectionY - FLOOR_TOLERANCE, origin.getZ() - radius,
            origin.getX() + radius + 1, collectionY + FLOOR_TOLERANCE, origin.getZ() + radius + 1);
        return mc.world.getEntitiesByClass(ItemEntity.class, area,
            drop -> drop.isAlive() && !drop.getStack().isEmpty()).size();
    }

    private void pause(String reason) {
        closeChest();
        releaseMovement();
        state = State.PAUSED;
        activity = reason;
        warning("%s", reason);
    }

    private int countInventory(Item item) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int inventoryCapacity(ItemStack incoming) {
        int capacity = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack held = mc.player.getInventory().getStack(slot);
            if (held.isEmpty()) capacity += incoming.getMaxCount();
            else if (ItemStack.areItemsAndComponentsEqual(held, incoming))
                capacity += Math.max(0, held.getMaxCount() - held.getCount());
        }
        return capacity;
    }

    private void onSelectStorageChanged(boolean enabled) {
        if (enabled) enterRegistration();
    }

    private void enterRegistration() {
        if (!isActive()) toggle();
        else if (state != State.REGISTERING) {
            closeChest();
            releaseMovement();
            state = State.REGISTERING;
        }
        if (mc.player != null) mc.setScreen(null);
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive() || state != State.REGISTERING || mc.player == null || mc.world == null
            || event.action != KeyAction.Press || event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        event.cancel();
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || !isContainer(hit.getBlockPos())) {
            warning("请右键点击箱子、木桶或潜影盒。", new Object[0]);
            return;
        }
        BlockPos pos = hit.getBlockPos().toImmutable();
        if (!storage.contains(pos)) storage.add(pos);
        saveStorageList();
        info("存储箱已登记：%s", pos.toShortString());
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || state != State.REGISTERING || event.action != KeyAction.Press
            || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        if (selectStorage.get()) selectStorage.set(false);
        clearChest();
        if (!cargo.isEmpty()) state = State.DEPOSITING;
        else state = storage.isEmpty() ? State.PAUSED : State.COLLECTING;
        lastDropSeenTick = tick;
        activity = state == State.PAUSED ? "请先登记箱子" : "继续拾取和存放";
        mc.setScreen(GuiThemes.get().moduleScreen(this));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || state != State.REGISTERING || mc.world == null) return;
        Color yellow = new Color(255, 220, 45, 255);
        for (BlockPos pos : storage) event.renderer.box(pos, null, yellow, ShapeMode.Lines, 0);
    }

    private void onResumeChanged(boolean enabled) {
        if (!enabled) return;
        resumeSorting.set(false);
        if (!isActive() || state != State.PAUSED || mc.player == null || mc.world == null) return;
        if (storage.isEmpty()) {
            warning("请先登记至少一只存储箱。", new Object[0]);
            return;
        }
        if (Math.abs(mc.player.getY() - collectionY) > FLOOR_TOLERANCE) {
            warning("玩家不在启动时的高度，请回到原平面再继续。", new Object[0]);
            return;
        }
        if (!cargo.isEmpty()) {
            depositItem = null;
            triedChests.clear();
            state = State.DEPOSITING;
        } else {
            skippedDrops.clear();
            lastDropSeenTick = tick;
            state = State.COLLECTING;
        }
        activity = "重新尝试拾取和存放";
    }

    private void saveStorageList() {
        List<String> entries = new ArrayList<>();
        for (BlockPos pos : storage) entries.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        entries.sort(String::compareTo);
        updatingStorage = true;
        storageList.set(entries);
        updatingStorage = false;
    }

    private void restoreStorageList(List<String> entries) {
        if (updatingStorage) return;
        storage.clear();
        for (String entry : entries) {
            BlockPos pos = parsePosition(entry);
            if (pos != null && !storage.contains(pos)) storage.add(pos);
        }
    }

    private static BlockPos parsePosition(String text) {
        String[] xyz = text.split(",", 3);
        if (xyz.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(xyz[0].trim()),
                Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (NumberFormatException ignored) { return null; }
    }

    private boolean isContainer(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
            || state.getBlock() instanceof ShulkerBoxBlock;
    }

    private ScreenHandler currentContainer() {
        if (mc.player == null) return null;
        ScreenHandler handler = mc.player.currentScreenHandler;
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler
            ? handler : null;
    }

    private int containerSlots(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler generic ? generic.getRows() * 9 : 27;
    }

    private void closeChest() {
        if (mc.player != null && currentContainer() != null) mc.player.closeHandledScreen();
    }

    private BlockHitResult findContainerHit(BlockPos pos, Vec3d eye) {
        if (!isContainer(pos)) return null;
        for (Direction face : Direction.values()) {
            Vec3d point = Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.49,
                face.getOffsetY() * 0.49, face.getOffsetZ() * 0.49);
            if (eye.squaredDistanceTo(point) > 16.0) continue;
            HitResult trace = mc.world.raycast(new RaycastContext(eye, point,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (trace instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    private BlockPos findChestStand(BlockPos chest) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = chest.getX() - 4; x <= chest.getX() + 4; x++) {
            for (int z = chest.getZ() - 4; z <= chest.getZ() + 4; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                if (!isStandUsable(candidate) || rejectedStands.contains(candidate)) continue;
                Vec3d eye = new Vec3d(x + 0.5, walkY + 1.62, z + 0.5);
                if (findContainerHit(chest, eye) != null) candidates.add(candidate);
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
    }

    private ScreenHandler tickOpenChest() {
        ScreenHandler opened = currentContainer();
        if (opened != null && (openTick < 0 || opened.syncId == previousSyncId)) {
            mc.player.closeHandledScreen();
            opened = null;
        }
        if (opened == null && mc.currentScreen != null) {
            releaseMovement();
            activity = "请关闭当前界面后继续";
            return null;
        }
        if (opened != null) { releaseMovement(); return opened; }
        if (openTick >= 0 && tick - openTick < 12) {
            releaseMovement();
            activity = "等待箱子界面确认";
            return null;
        }

        BlockHitResult hit = findContainerHit(activeChest, mc.player.getEyePos());
        if (hit == null) {
            if (chestStand == null) chestStand = findChestStand(activeChest);
            if (chestStand == null) { nextChest(); return null; }
            RouteResult route = navigateTo(chestStand);
            if (route == RouteResult.UNREACHABLE || route == RouteResult.ARRIVED) {
                rejectedStands.add(chestStand);
                chestStand = null;
                resetNavigation();
            }
            activity = "前往箱子 " + activeChest.toShortString();
            return null;
        }
        resetNavigation();
        Vec3d velocity = mc.player.getVelocity();
        if (tick - lastMovementReleaseTick < 2
            || velocity.x * velocity.x + velocity.z * velocity.z > 0.025 * 0.025) {
            activity = "停稳后打开箱子";
            return null;
        }
        if (openTick >= 0 && ++openAttempts >= 3) { nextChest(); return null; }
        if (aimTick < 0 || tick - aimTick > 2) {
            faceToward(hit.getPos());
            aimTick = tick;
            activity = "正对箱子";
            return null;
        }
        faceToward(hit.getPos());
        previousSyncId = mc.player.currentScreenHandler.syncId;
        BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        openTick = tick;
        aimTick = -1;
        activity = "等待箱子打开";
        return null;
    }

    private void selectChest(BlockPos pos) {
        closeChest();
        clearChest();
        activeChest = pos.toImmutable();
    }

    private void nextChest() {
        if (activeChest != null) warning("箱子 %s 无法打开或已满，尝试下一只。", activeChest.toShortString());
        closeChest();
        clearChest();
    }

    private void clearChest() {
        resetNavigation();
        activeChest = null;
        chestStand = null;
        rejectedStands.clear();
        transferTick = -1;
        transferFailures = 0;
        openTick = -1;
        aimTick = -1;
        openAttempts = 0;
    }

    private RouteResult navigateTo(BlockPos goal) {
        if (hasReached(goal)) { resetNavigation(); return RouteResult.ARRIVED; }
        if (!isStandUsable(goal)) return RouteResult.UNREACHABLE;
        boolean changed = navigationGoal == null || !navigationGoal.equals(goal);
        boolean stuck = !path.isEmpty() && tick - lastProgressTick > 60;
        if (changed || path.isEmpty() || stuck) {
            if (changed) { resetNavigation(); navigationGoal = goal.toImmutable(); }
            else if (stuck) { releaseMovement(); path.clear(); }
            if (++routeFailures > 3 || !planPath(goal)) return RouteResult.UNREACHABLE;
        }
        while (pathIndex < path.size() && hasReached(path.get(pathIndex))) {
            pathIndex++;
            lastProgressTick = tick;
            bestStepDistance = Double.POSITIVE_INFINITY;
        }
        if (pathIndex >= path.size()) return hasReached(goal) ? RouteResult.ARRIVED : RouteResult.MOVING;
        BlockPos next = path.get(pathIndex);
        if (!isStandUsable(next)) { path.clear(); return RouteResult.MOVING; }
        if (!isSafeDirectApproach(Vec3d.ofCenter(next))) return RouteResult.UNREACHABLE;
        double distance = Math.sqrt(horizontalDistanceSquared(next));
        if (distance + 0.04 < bestStepDistance) { bestStepDistance = distance; lastProgressTick = tick; }
        faceToward(Vec3d.ofCenter(next));
        pressForward();
        return RouteResult.MOVING;
    }

    private boolean planPath(BlockPos goal) {
        path.clear();
        pathIndex = 0;
        lastProgressTick = tick;
        bestStepDistance = Double.POSITIVE_INFINITY;
        BlockPos start = new BlockPos(MathHelper.floor(mc.player.getX()), walkY,
            MathHelper.floor(mc.player.getZ()));
        int minX = Math.min(start.getX(), goal.getX()) - 8;
        int maxX = Math.max(start.getX(), goal.getX()) + 8;
        int minZ = Math.min(start.getZ(), goal.getZ()) - 8;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + 8;
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        open.add(start);
        parents.put(start, null);
        int examined = 0;
        while (!open.isEmpty() && examined++ < 30000) {
            BlockPos current = open.removeFirst();
            if (current.equals(goal)) {
                ArrayDeque<BlockPos> reverse = new ArrayDeque<>();
                for (BlockPos cursor = goal; cursor != null; cursor = parents.get(cursor)) reverse.addFirst(cursor);
                path.addAll(reverse);
                pathIndex = path.size() > 1 ? 1 : 0;
                return true;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.offset(direction).toImmutable();
                if (next.getX() < minX || next.getX() > maxX || next.getZ() < minZ || next.getZ() > maxZ
                    || next.getY() != walkY || parents.containsKey(next) || !isStandUsable(next)) continue;
                parents.put(next, current);
                open.addLast(next);
            }
        }
        return false;
    }

    private boolean isStandUsable(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        return (feet.getCollisionShape(mc.world, pos).isEmpty() || feet.getBlock() instanceof CarpetBlock)
            && mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty()
            && !mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down()).isEmpty();
    }

    private boolean hasReached(BlockPos pos) {
        return horizontalDistanceSquared(pos) <= 0.20 * 0.20
            && Math.abs(mc.player.getY() - walkY) <= 0.6;
    }

    private double horizontalDistanceSquared(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    private double distanceToPlayer(BlockPos pos) {
        return mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private void faceToward(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        mc.player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI - 90.0));
        mc.player.setPitch((float) (-MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    private void pressForward() {
        setKeyPressed(mc.options.forwardKey, true);
        ownsForward = true;
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
    }

    private void releaseMovement() {
        if (!ownsForward || mc.options == null) return;
        lastMovementReleaseTick = tick;
        setKeyPressed(mc.options.forwardKey, false);
        ownsForward = false;
    }

    private static void setKeyPressed(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
        Input.setKeyState(key, pressed);
    }

    private void resetNavigation() {
        releaseMovement();
        path.clear();
        navigationGoal = null;
        pathIndex = 0;
        routeFailures = 0;
        bestStepDistance = Double.POSITIVE_INFINITY;
    }

    private void resetRuntime() {
        releaseMovement();
        resetNavigation();
        cargo.clear();
        observedInventory.clear();
        nearbyDropItems.clear();
        skippedDrops.clear();
        triedChests.clear();
        rejectedStands.clear();
        targetDrop = null;
        targetItem = null;
        activeChest = null;
        chestStand = null;
        depositItem = null;
        origin = null;
        nearDropTick = -1;
        approachTick = -1;
        transferTick = -1;
        openTick = -1;
        aimTick = -1;
        tick = 0;
        lastDropSeenTick = 0;
        lastMovementReleaseTick = -100;
        state = State.PAUSED;
        activity = "空闲";
    }

    private enum State { REGISTERING, COLLECTING, DEPOSITING, PAUSED }
    private enum RouteResult { MOVING, ARRIVED, UNREACHABLE }
}
