package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.utils.StorageBlockUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
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
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Moves misplaced stacks between item-frame/sign-labeled containers. */
public final class ChestOrganizerModule extends Module {
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int OPEN_WAIT_TICKS = 12;
    private static final int TRANSFER_WAIT_TICKS = 12;

    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final Setting<Boolean> selectChests = sgStorage.add(new BoolSetting.Builder()
        .name("Select Storage Chests")
        .description("Hide settings, right-click labeled chests to add them, then press Esc to return.")
        .defaultValue(false).onChanged(this::onSelectChestsChanged).build());
    private final Setting<List<String>> chestList = sgStorage.add(new StringListSetting.Builder()
        .name("Storage Chest List")
        .description("Registered item ID -> chest position. Use + to edit entries manually; labels are checked before transfer.")
        .defaultValue(new String[0]).onChanged(this::restoreChestList).build());
    private final Setting<Boolean> selectOverflow = sgStorage.add(new BoolSetting.Builder()
        .name("Select Overflow Chest")
        .description("Right-click one unlabeled chest for miscellaneous items; press Esc to return.")
        .defaultValue(false).onChanged(this::onSelectOverflowChanged).build());
    private final Setting<List<String>> overflowChestList = sgStorage.add(new StringListSetting.Builder()
        .name("Overflow Chest")
        .description("Optional single X,Y,Z position. Used only when no labeled destination can receive an item.")
        .defaultValue(new String[0]).onChanged(this::restoreOverflowChest).build());
    private final Setting<Boolean> resumeSorting = sgStorage.add(new BoolSetting.Builder()
        .name("Resume Sorting")
        .description("Retry after adding a chest, freeing space, or correcting a label.")
        .defaultValue(false).onChanged(this::onResumeSortingChanged).build());

    private final Map<BlockPos, Item> labels = new HashMap<>();
    private final Map<Item, List<BlockPos>> destinations = new HashMap<>();
    private final Map<Item, Integer> cargo = new HashMap<>();
    private final Set<BlockPos> scanned = new HashSet<>();
    private final Set<BlockPos> unreachable = new HashSet<>();
    private final Set<BlockPos> unresolved = new HashSet<>();
    private final Set<BlockPos> triedDestinations = new HashSet<>();
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private final Set<Integer> rejectedSourceSlots = new HashSet<>();
    private final List<BlockPos> path = new ArrayList<>();

    private State state = State.PAUSED;
    private BlockPos activeChest;
    private BlockPos overflowChest;
    private BlockPos stand;
    private BlockPos navigationGoal;
    private Item depositItem;
    private Item transferItem;
    private boolean extractingTransfer;
    private int transferSlot;
    private int transferBefore;
    private int transferFailures;
    private int pathIndex;
    private int routeFailures;
    private int walkY;
    private int previousSyncId;
    private int openAttempts;
    private long tick;
    private long openTick = -1;
    private long aimTick = -1;
    private long transferTick = -1;
    private long lastProgressTick;
    private long lastMovementReleaseTick = -100;
    private double bestStepDistance = Double.POSITIVE_INFINITY;
    private boolean ownsForward;
    private boolean updatingList;
    private String activity = "空闲";

    public ChestOrganizerModule() {
        super(BuilderToolboxAddon.CATEGORY, "Chest Organizer",
            "Sorts misplaced stacks between registered item-frame-labeled chests without touching unrelated inventory items.");
    }

    @Override
    public void onActivate() {
        resetRuntime();
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (labels.isEmpty() && !chestList.get().isEmpty()) restoreChestList(chestList.get());
        if (overflowChest == null && !overflowChestList.get().isEmpty()) restoreOverflowChest(overflowChestList.get());
        walkY = mc.player.getBlockPos().getY();
        if (selectChests.get() || selectOverflow.get()) {
            state = State.REGISTERING;
            activity = "右键登记箱子，Esc 返回设置";
        } else if (labels.isEmpty()) {
            state = State.PAUSED;
            warning("请先登记带展示框或告示牌的箱子。", new Object[0]);
        } else if (overflowConflictsWithLabel()) {
            state = State.PAUSED;
            warning("杂物箱与分类箱不能是同一只；请重新指定杂物箱。", new Object[0]);
        } else if (hasInitialSortItems()) {
            state = State.PAUSED;
            warning("背包里已有登记颜色的物品。请先移走这些物品再启动，避免误存玩家原有物品。", new Object[0]);
        } else {
            state = State.EXTRACTING;
            activity = "扫描已登记箱子中的错放物品";
        }
    }

    @Override
    public void onDeactivate() {
        if (!cargo.isEmpty()) warning("整理已停止；尚有 %d 种待归类物品留在背包中。", cargo.size());
        closeChest();
        releaseMovement();
        if (selectChests.get()) selectChests.set(false);
        if (selectOverflow.get()) selectOverflow.set(false);
        resetRuntime();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        tick++;
        if (state == State.REGISTERING || state == State.PAUSED) { releaseMovement(); return; }
        if (mc.currentScreen != null && currentContainer() == null) {
            releaseMovement();
            activity = "检测到其他界面，已暂停操作";
            return;
        }
        if (state == State.EXTRACTING) tickExtract();
        else tickDeposit();
    }

    private void tickExtract() {
        if (activeChest == null) {
            BlockPos next = labels.keySet().stream()
                .filter(pos -> !scanned.contains(pos) && !unreachable.contains(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
            if (next == null) {
                if (!cargo.isEmpty()) beginDeposit();
                else finishSorting();
                return;
            }
            selectChest(next);
        }
        ScreenHandler opened = tickOpenChest();
        if (opened == null) return;
        if (tickTransfer()) return;
        if (!labelStillMatches(activeChest)) { skipSource("标签已变化"); return; }

        int containerSlots = containerSlots(opened);
        boolean needsRoom = false;
        boolean hasUnmapped = false;
        Item expected = labels.get(activeChest);
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (stack.isEmpty() || stack.isOf(expected) || rejectedSourceSlots.contains(slot)) continue;
            Item item = stack.getItem();
            if (countInventory(item) > cargo.getOrDefault(item, 0)) {
                // Shift-click cannot distinguish this source stack from the
                // player's own stack of the same item ID.
                hasUnmapped = true;
                continue;
            }
            if (inventoryCapacity(stack) < stack.getCount()) { needsRoom = true; continue; }
            transferItem = item;
            transferBefore = countInventory(item);
            transferSlot = slot;
            extractingTransfer = true;
            InvUtils.shiftClick().slotId(slot);
            transferTick = tick;
            activity = "取出错放的 " + item.getName().getString();
            return;
        }
        if (needsRoom) {
            if (!cargo.isEmpty()) { beginDeposit(); return; }
            pause("背包空间不足，无法取出箱子里的整组物品");
            return;
        }
        if (hasUnmapped || !rejectedSourceSlots.isEmpty()) unresolved.add(activeChest);
        scanned.add(activeChest);
        closeChest();
        clearChest();
    }

    private void tickDeposit() {
        if (depositItem == null || cargo.getOrDefault(depositItem, 0) <= 0) {
            closeChest();
            clearChest();
            depositItem = nextCargoItem();
            triedDestinations.clear();
            if (depositItem == null) {
                state = State.EXTRACTING;
                activity = "背包分类完成，继续检查剩余箱子";
                return;
            }
        }
        if (countInventory(depositItem) > cargo.getOrDefault(depositItem, 0)) {
            pause("背包中的目标物品数量超过本轮取出量，已停止以保护原有物品");
            return;
        }
        if (activeChest == null) {
            BlockPos next = destinations.getOrDefault(depositItem, List.of()).stream()
                .filter(pos -> !triedDestinations.contains(pos))
                .min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null);
            if (next == null && overflowChest != null && !triedDestinations.contains(overflowChest)) next = overflowChest;
            if (next == null) {
                String reason = destinations.getOrDefault(depositItem, List.of()).isEmpty() && overflowChest == null
                    ? "未登记 " + depositItem.getName().getString() + " 的目标箱或杂物箱；物品保留在背包"
                    : "没有可用空间存放 " + depositItem.getName().getString() + "；物品保留在背包";
                pause(reason);
                return;
            }
            triedDestinations.add(next);
            selectChest(next);
        }
        ScreenHandler opened = tickOpenChest();
        if (opened == null) return;
        if (tickTransfer()) return;
        if (!activeChest.equals(overflowChest)
            && (!labelStillMatches(activeChest) || !depositItem.equals(labels.get(activeChest)))) {
            nextDestination();
            return;
        }

        int containerSlots = containerSlots(opened);
        Identifier wanted = Registries.ITEM.getId(depositItem);
        for (int slot = containerSlots; slot < opened.slots.size(); slot++) {
            ItemStack stack = opened.getSlot(slot).getStack();
            if (stack.isEmpty() || !wanted.equals(Registries.ITEM.getId(stack.getItem()))) continue;
            if (stack.getCount() > cargo.getOrDefault(depositItem, 0)) {
                pause("当前物品组包含玩家原有物品，已停止以免误存");
                return;
            }
            transferItem = depositItem;
            transferBefore = countInventory(depositItem);
            transferSlot = slot;
            extractingTransfer = false;
            InvUtils.shiftClick().slotId(slot);
            transferTick = tick;
            activity = "归类 " + depositItem.getName().getString();
            return;
        }
        if (countInventory(depositItem) == 0) cargo.remove(depositItem);
        else nextDestination();
    }

    /** Wait for the server inventory update before issuing another click. */
    private boolean tickTransfer() {
        if (transferTick < 0) return false;
        int now = countInventory(transferItem);
        int moved = extractingTransfer ? now - transferBefore : transferBefore - now;
        if (moved > 0) {
            if (extractingTransfer) cargo.merge(transferItem, moved, Integer::sum);
            else cargo.computeIfPresent(transferItem, (item, amount) -> amount > moved ? amount - moved : null);
            transferTick = -1;
            transferFailures = 0;
            activity = (extractingTransfer ? "已取出 " : "已归类 ") + moved + " 个 " + transferItem.getName().getString();
            return true;
        }
        if (tick - transferTick < TRANSFER_WAIT_TICKS) return true;
        transferTick = -1;
        if (++transferFailures >= 2) {
            if (extractingTransfer) {
                rejectedSourceSlots.add(transferSlot);
                unresolved.add(activeChest);
                warning("箱子 %s 的第 %d 格转移未确认，本轮跳过该格。", activeChest.toShortString(), transferSlot);
            } else {
                warning("目标箱 %s 无法收下 %s，尝试同物品的下一箱。",
                    activeChest.toShortString(), transferItem.getName().getString());
                nextDestination();
            }
            transferFailures = 0;
        }
        return true;
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
        if (openTick >= 0 && tick - openTick < OPEN_WAIT_TICKS) {
            releaseMovement();
            activity = "等待箱子界面确认";
            return null;
        }

        BlockHitResult hit = findContainerHit(activeChest, mc.player.getEyePos());
        if (hit == null) {
            if (stand == null) stand = findChestStand(activeChest);
            if (stand == null) { chestUnreachable(); return null; }
            RouteResult route = navigateTo(stand);
            if (route == RouteResult.UNREACHABLE || route == RouteResult.ARRIVED) {
                rejectedStands.add(stand);
                stand = null;
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
        if (openTick >= 0 && ++openAttempts >= 3) { chestUnreachable(); return null; }
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

    private void selectChest(BlockPos chest) {
        closeChest();
        clearChest();
        activeChest = chest.toImmutable();
        activity = "检查箱子 " + chest.toShortString();
    }

    private void clearChest() {
        releaseMovement();
        resetNavigation();
        activeChest = null;
        stand = null;
        rejectedStands.clear();
        rejectedSourceSlots.clear();
        openTick = -1;
        aimTick = -1;
        openAttempts = 0;
        transferTick = -1;
        transferFailures = 0;
    }

    private void chestUnreachable() {
        if (state == State.EXTRACTING) {
            unreachable.add(activeChest);
            warning("无法打开来源箱 %s，本轮跳过。", activeChest.toShortString());
            closeChest();
            clearChest();
        } else {
            warning("无法打开目标箱 %s，尝试下一箱。", activeChest.toShortString());
            nextDestination();
        }
    }

    private void skipSource(String reason) {
        unresolved.add(activeChest);
        scanned.add(activeChest);
        warning("来源箱 %s %s，已跳过。", activeChest.toShortString(), reason);
        closeChest();
        clearChest();
    }

    private void nextDestination() {
        closeChest();
        clearChest();
    }

    private void beginDeposit() {
        closeChest();
        clearChest();
        state = State.DEPOSITING;
        depositItem = null;
        activity = "背包装有待归类物品，前往目标箱";
    }

    private Item nextCargoItem() {
        Item best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<Item, Integer> entry : cargo.entrySet()) {
            if (entry.getValue() <= 0) continue;
            List<BlockPos> options = destinations.getOrDefault(entry.getKey(), List.of());
            if (options.isEmpty() && overflowChest != null) options = List.of(overflowChest);
            if (options.isEmpty() && best == null) best = entry.getKey();
            for (BlockPos pos : options) {
                double distance = distanceToPlayer(pos);
                if (distance < bestDistance) { best = entry.getKey(); bestDistance = distance; }
            }
        }
        return best;
    }

    private void finishSorting() {
        releaseMovement();
        if (!unresolved.isEmpty() || !unreachable.isEmpty()) {
            warning("本轮检查结束：%d 个箱子仍有无法归类的物品或标签问题，%d 个箱子无法到达。",
                unresolved.size(), unreachable.size());
            state = State.PAUSED;
            activity = "部分箱子未完成；可登记更多目标箱后重试";
            return;
        }
        info("箱子整理完成：已检查 %d 个箱子。", scanned.size());
        disable();
    }

    private void pause(String reason) {
        closeChest();
        releaseMovement();
        state = State.PAUSED;
        activity = reason;
        warning("%s", reason);
    }

    private boolean hasInitialSortItems() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && destinations.containsKey(stack.getItem())) return true;
        }
        return false;
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

    private void onSelectChestsChanged(boolean enabled) {
        if (!enabled) return;
        if (selectOverflow.get()) selectOverflow.set(false);
        enterRegistration();
    }

    private void onSelectOverflowChanged(boolean enabled) {
        if (!enabled) return;
        if (selectChests.get()) selectChests.set(false);
        enterRegistration();
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
        if (selectOverflow.get()) {
            if (labels.containsKey(pos)) {
                warning("杂物箱不能同时作为分类箱，请选另一只空箱。", new Object[0]);
                return;
            }
            overflowChest = pos;
            overflowChestList.set(List.of(pos.getX() + "," + pos.getY() + "," + pos.getZ()));
            info("杂物箱已登记：%s", pos.toShortString());
            return;
        }
        Item item = findNearestLabelItem(pos, 4);
        if (item == null) {
            warning("附近没有可用的物品展示框或告示牌标签。", new Object[0]);
            return;
        }
        if (pos.equals(overflowChest)) {
            warning("此箱已指定为杂物箱，请先更换杂物箱。", new Object[0]);
            return;
        }
        labels.put(pos, item);
        rebuildDestinations();
        saveChestList();
        info("分类箱已加入：%s -> %s", Registries.ITEM.getId(item), pos.toShortString());
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || state != State.REGISTERING || event.action != KeyAction.Press
            || event.key() != GLFW.GLFW_KEY_ESCAPE) return;
        event.cancel();
        if (selectChests.get()) selectChests.set(false);
        if (selectOverflow.get()) selectOverflow.set(false);
        scanned.clear();
        unreachable.clear();
        unresolved.clear();
        triedDestinations.clear();
        clearChest();
        if (overflowConflictsWithLabel()) state = State.PAUSED;
        else if (!cargo.isEmpty()) state = State.DEPOSITING;
        else if (labels.isEmpty() || hasInitialSortItems()) state = State.PAUSED;
        else state = State.EXTRACTING;
        activity = state == State.PAUSED ? "请登记分类箱并清空背包中的同类物品" : "继续整理箱子";
        mc.setScreen(GuiThemes.get().moduleScreen(this));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || state != State.REGISTERING || mc.world == null) return;
        Color yellow = new Color(255, 220, 45, 255);
        for (BlockPos pos : labels.keySet()) event.renderer.box(pos, null, yellow, ShapeMode.Lines, 0);
        if (overflowChest != null) event.renderer.box(overflowChest, null,
            new Color(255, 145, 35, 255), ShapeMode.Lines, 0);
    }

    private void saveChestList() {
        List<String> entries = new ArrayList<>();
        labels.forEach((pos, item) -> entries.add(Registries.ITEM.getId(item) + " -> "
            + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        entries.sort(String::compareTo);
        updatingList = true;
        chestList.set(entries);
        updatingList = false;
    }

    private void restoreChestList(List<String> entries) {
        if (updatingList) return;
        labels.clear();
        for (String entry : entries) {
            String[] parts = entry.split(" -> ", 2);
            if (parts.length != 2) continue;
            Identifier id = Identifier.tryParse(parts[0]);
            if (id == null) continue;
            Item item = Registries.ITEM.get(id);
            BlockPos pos = parsePosition(parts[1]);
            if (item == null || item == Items.AIR || pos == null) continue;
            labels.put(pos, item);
        }
        rebuildDestinations();
    }

    private void restoreOverflowChest(List<String> entries) {
        overflowChest = entries.isEmpty() ? null : parsePosition(entries.getFirst());
    }

    private static BlockPos parsePosition(String text) {
        String[] xyz = text.split(",", 3);
        if (xyz.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(xyz[0].trim()),
                Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (NumberFormatException ignored) { return null; }
    }

    private void rebuildDestinations() {
        destinations.clear();
        labels.forEach((pos, item) -> destinations.computeIfAbsent(item, ignored -> new ArrayList<>()).add(pos));
    }

    private boolean overflowConflictsWithLabel() {
        return overflowChest != null && labels.containsKey(overflowChest);
    }

    private void onResumeSortingChanged(boolean enabled) {
        if (!enabled) return;
        resumeSorting.set(false);
        if (!isActive() || state != State.PAUSED || mc.player == null || mc.world == null) return;
        if (labels.isEmpty() || overflowConflictsWithLabel()) {
            warning("请先修正分类箱和杂物箱的登记。", new Object[0]);
            return;
        }
        if (!cargo.isEmpty()) {
            depositItem = null;
            triedDestinations.clear();
            state = State.DEPOSITING;
        } else {
            if (hasInitialSortItems()) {
                warning("请先移走背包中与分类箱相同 ID 的原有物品。", new Object[0]);
                return;
            }
            scanned.clear();
            unreachable.clear();
            unresolved.clear();
            state = State.EXTRACTING;
        }
        activity = "重新尝试整理箱子";
    }

    private Item findNearestLabelItem(BlockPos container, int radius) {
        List<ItemFrameEntity> frames = mc.world.getEntitiesByClass(ItemFrameEntity.class,
            new net.minecraft.util.math.Box(container).expand(radius),
            frame -> !frame.getHeldItemStack().isEmpty());
        for (ItemFrameEntity frame : frames) {
            if (frame.getAttachedBlockPos().equals(container)) return frame.getHeldItemStack().getItem();
        }
        Item nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ItemFrameEntity frame : frames) {
            double dx = frame.getX() - (container.getX() + 0.5);
            double dy = frame.getY() - (container.getY() + 0.5);
            double dz = frame.getZ() - (container.getZ() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearestDistance) {
                nearest = frame.getHeldItemStack().getItem();
                nearestDistance = distance;
            }
        }
        for (int y = container.getY() - radius; y <= container.getY() + radius; y++) {
            for (int z = container.getZ() - radius; z <= container.getZ() + radius; z++) {
                for (int x = container.getX() - radius; x <= container.getX() + radius; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity entity = mc.world.getBlockEntity(pos);
                    if (!(entity instanceof SignBlockEntity sign)) continue;
                    Item item = findItemOnSign(sign);
                    if (item == null) continue;
                    double distance = pos.getSquaredDistance(container);
                    if (distance < nearestDistance) { nearest = item; nearestDistance = distance; }
                }
            }
        }
        return nearest;
    }

    private Item findItemOnSign(SignBlockEntity sign) {
        for (boolean front : List.of(true, false)) {
            for (net.minecraft.text.Text line : sign.getText(front).getMessages(false)) {
                String label = line.getString().trim();
                if (label.isEmpty()) continue;
                Identifier id = Identifier.tryParse(label);
                if (id != null) {
                    Item exact = Registries.ITEM.get(id);
                    if (exact != null && exact != Items.AIR) return exact;
                }
                String normalized = label.toLowerCase(java.util.Locale.ROOT).replaceAll("[ _:-]", "");
                for (Item item : Registries.ITEM) {
                    Identifier itemId = Registries.ITEM.getId(item);
                    if (item != Items.AIR && itemId != null &&
                        (itemId.toString().replaceAll("[ _:-]", "").equalsIgnoreCase(normalized)
                            || itemId.getPath().replaceAll("[ _:-]", "").equalsIgnoreCase(normalized)
                            || item.getName().getString().replaceAll("[ _:-]", "").equalsIgnoreCase(normalized))) return item;
                }
            }
        }
        return null;
    }

    private boolean isContainer(BlockPos pos) {
        return StorageBlockUtils.isStorage(mc, pos);
    }

    private boolean labelStillMatches(BlockPos chest) {
        if (!isContainer(chest)) return false;
        Item expected = labels.get(chest);
        Item actual = findNearestLabelItem(chest, 4);
        return expected != null && actual != null
            && Registries.ITEM.getId(expected).equals(Registries.ITEM.getId(actual));
    }

    private ScreenHandler currentContainer() {
        if (mc.player == null) return null;
        ScreenHandler handler = mc.player.currentScreenHandler;
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler
            ? handler : null;
    }

    private int containerSlots(ScreenHandler handler) {
        return StorageBlockUtils.storageSlots(handler);
    }

    private void closeChest() {
        if (mc.player != null && currentContainer() != null) mc.player.closeHandledScreen();
    }

    private BlockHitResult findContainerHit(BlockPos pos, Vec3d eye) {
        if (!isContainer(pos)) return null;
        return StorageBlockUtils.findHit(mc, pos, eye, 4.0);
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
        scanned.clear();
        unreachable.clear();
        unresolved.clear();
        triedDestinations.clear();
        rejectedStands.clear();
        rejectedSourceSlots.clear();
        activeChest = null;
        stand = null;
        depositItem = null;
        transferItem = null;
        transferTick = -1;
        openTick = -1;
        aimTick = -1;
        tick = 0;
        lastMovementReleaseTick = -100;
        state = State.PAUSED;
        activity = "空闲";
    }

    private enum State { REGISTERING, EXTRACTING, DEPOSITING, PAUSED }
    private enum RouteResult { MOVING, ARRIVED, UNREACHABLE }
}
