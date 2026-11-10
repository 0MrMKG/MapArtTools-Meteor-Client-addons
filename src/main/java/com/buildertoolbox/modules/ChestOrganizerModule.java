package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.utils.AutomationControl;
import com.buildertoolbox.utils.OrganizerJournal;
import com.buildertoolbox.utils.StorageBlockUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentLinkedQueue;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
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
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;

/** Moves misplaced stacks between item-frame/sign-labeled containers. */
public final class ChestOrganizerModule extends Module {
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int OPEN_WAIT_TICKS = 12;
    private static final int TRANSFER_WAIT_TICKS = 8;

    private final SettingGroup sgStorage = settings.createGroup("Locations");
    private final Setting<Boolean> selectChests = sgStorage.add(new BoolSetting.Builder()
        .name("Select Storage Chests")
        .description("隐藏设置后右键登记带物品标签的箱子，按 Esc 返回。")
        .defaultValue(false).onChanged(this::onSelectChestsChanged).build());
    private final Setting<List<String>> chestList = sgStorage.add(new StringListSetting.Builder()
        .name("Storage Chest List")
        .description("保存“物品 ID → 箱子坐标”，可用加号手动添加条目。搬运前会核对标签。")
        .defaultValue(new String[0]).onChanged(this::restoreChestList).build());
    private final Setting<Boolean> selectOverflow = sgStorage.add(new BoolSetting.Builder()
        .name("Select Overflow Chest")
        .description("右键选择一个无标签的杂物箱，按 Esc 返回。")
        .defaultValue(false).onChanged(this::onSelectOverflowChanged).build());
    private final Setting<List<String>> overflowChestList = sgStorage.add(new StringListSetting.Builder()
        .name("Overflow Chest")
        .description("可选的单个杂物箱坐标。没有可接收该物品的分类箱时使用。")
        .defaultValue(new String[0]).onChanged(this::restoreOverflowChest).build());
    private final Setting<Boolean> resumeSorting = sgStorage.add(new BoolSetting.Builder()
        .name("Resume Sorting")
        .description("添加箱子、腾出空间或修正标签后，点击继续整理。")
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
    private boolean ownsAutomation;
    private boolean restoringTransfer;
    private boolean needsCargoCheck;
    private OrganizerJournal journal;
    private String journalWorld, journalDimension, journalPlayer;
    private OrganizerJournal.Pending pendingTransfer;
    private ScreenHandler ownedContainer;
    private long ownedAt;
    private long inventorySequence;
    private long transferSequence;
    private final ConcurrentLinkedQueue<InventoryUpdate> inventoryUpdates = new ConcurrentLinkedQueue<>();
    private final Map<Integer, ServerInventory> serverInventories = new HashMap<>();
    private String activity = "空闲";

    public ChestOrganizerModule() {
        super(BuilderToolboxAddon.CATEGORY, "Chest Organizer",
            "根据登记箱子的物品展示框标签，将错放物品取出并归类到对应箱子。");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (!AutomationControl.acquire(this)) { disable(); return; }
        ownsAutomation = true;
        resetRuntime();
        if (labels.isEmpty() && !chestList.get().isEmpty()) restoreChestList(chestList.get());
        if (overflowChest == null && !overflowChestList.get().isEmpty()) restoreOverflowChest(overflowChestList.get());
        walkY = mc.player.getBlockPos().getY();
        if (!restoreCargo()) return;
        if (selectChests.get() || selectOverflow.get()) {
            state = State.REGISTERING;
            activity = "右键登记箱子，Esc 返回设置";
        } else if (labels.isEmpty()) {
            state = State.PAUSED;
            warning("请先登记带展示框或告示牌的箱子。", new Object[0]);
        } else if (overflowConflictsWithLabel()) {
            state = State.PAUSED;
            warning("杂物箱与分类箱不能是同一只；请重新指定杂物箱。", new Object[0]);
        } else if (pendingTransfer != null || !cargo.isEmpty()) {
            state = State.DEPOSITING;
            activity = "恢复已保存的待归类物品，先核对服务器库存";
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
        if (!ownsAutomation) return;
        saveCargo();
        if (!cargo.isEmpty()) warning("整理已停止；尚有 %d 种待归类物品留在背包中。", cargo.size());
        closeChest();
        releaseMovement();
        if (selectChests.get()) selectChests.set(false);
        if (selectOverflow.get()) selectOverflow.set(false);
        resetRuntime();
        ownsAutomation = false;
        AutomationControl.release(this);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler
    private void onInventory(PacketEvent.Receive event) {
        if (event.packet instanceof InventoryS2CPacket packet) {
            inventoryUpdates.add(new InventoryUpdate(packet.syncId(), -1,
                packet.contents().stream().map(ItemStack::copy).toList(), ItemStack.EMPTY));
        } else if (event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            inventoryUpdates.add(new InventoryUpdate(packet.getSyncId(), packet.getSlot(), List.of(), packet.getStack().copy()));
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        tick++;
        receiveInventoryUpdates();
        if (state == State.REGISTERING || state == State.PAUSED) { releaseMovement(); return; }
        if (!mc.world.getRegistryKey().getValue().toString().equals(journalDimension)) {
            pause("已切换维度；整理进度仍保存在原维度，返回后重新开启继续");
            return;
        }
        if (mc.currentScreen != null && currentContainer() == null) {
            releaseMovement();
            activity = "检测到其他界面，已暂停操作";
            return;
        }
        if (pendingTransfer != null) { tickTransfer(); return; }
        if (needsCargoCheck) {
            // Reopen a registered chest to obtain a complete authoritative player inventory.
            if (activeChest == null) selectChest(labels.keySet().stream().min(Comparator.comparingDouble(this::distanceToPlayer)).orElse(null));
            if (state == State.PAUSED || activeChest == null) return;
            ScreenHandler opened = tickOpenChest();
            if (opened == null) return;
            if (!validateCargo(serverInventory(opened))) return;
            needsCargoCheck = false;
            closeChest();
            clearChest();
            if (!cargo.isEmpty()) beginDeposit();
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
        if (!labelStillMatches(activeChest)) { skipSource("标签已变化"); return; }

        int containerSlots = containerSlots(opened);
        boolean needsRoom = false;
        boolean hasUnmapped = false;
        Item expected = labels.get(activeChest);
        for (int slot = 0; slot < containerSlots; slot++) {
            ItemStack stack = serverInventory(opened).stacks.get(slot);
            if (stack.isEmpty() || stack.isOf(expected) || rejectedSourceSlots.contains(slot)) continue;
            Item item = stack.getItem();
            if (countInventory(item) > cargo.getOrDefault(item, 0)) {
                // Shift-click cannot distinguish this source stack from the
                // player's own stack of the same item ID.
                hasUnmapped = true;
                continue;
            }
            if (inventoryCapacity(stack) < stack.getCount()) { needsRoom = true; continue; }
            if (beginTransfer(opened, slot, item, true)) activity = "取出错放的 " + item.getName().getString();
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
        if (!activeChest.equals(overflowChest)
            && (!labelStillMatches(activeChest) || !depositItem.equals(labels.get(activeChest)))) {
            nextDestination();
            return;
        }

        int containerSlots = containerSlots(opened);
        Identifier wanted = Registries.ITEM.getId(depositItem);
        for (int slot = containerSlots; slot < opened.slots.size(); slot++) {
            ItemStack stack = serverInventory(opened).stacks.get(slot);
            if (stack.isEmpty() || !wanted.equals(Registries.ITEM.getId(stack.getItem()))) continue;
            if (stack.getCount() > cargo.getOrDefault(depositItem, 0)) {
                pause("当前物品组包含玩家原有物品，已停止以免误存");
                return;
            }
            if (beginTransfer(opened, slot, depositItem, false)) activity = "归类 " + depositItem.getName().getString();
            return;
        }
        if (countInventory(depositItem) == 0) {
            if (cargo.getOrDefault(depositItem, 0) > 0) pause("待归类物品与背包不一致，保留账本等待核对");
        }
        else nextDestination();
    }

    private boolean beginTransfer(ScreenHandler opened, int slot, Item item, boolean extracting) {
        ServerInventory inventory = serverInventory(opened);
        int before = inventoryCount(inventory, item, true, containerSlots(opened));
        if (before != cargo.getOrDefault(item, 0) || before != countInventory(item)) {
            pause("服务器库存与整理账本不一致，已保留物品等待核对");
            return false;
        }
        ItemStack actual = inventory.stacks.get(slot);
        ItemStack displayed = opened.getSlot(slot).getStack();
        if (actual.getCount() != displayed.getCount() || !ItemStack.areItemsAndComponentsEqual(actual, displayed)) {
            reopenForTransfer();
            return false;
        }
        pendingTransfer = new OrganizerJournal.Pending(position(activeChest), Registries.ITEM.getId(item).toString(),
            extracting, slot, before, inventoryCount(inventory, item, false, containerSlots(opened)),
            cargo.getOrDefault(item, 0), actual.getCount());
        // The durable intent must precede the client-predicted shift-click.
        if (!saveCargo()) return false;
        transferSequence = inventory.sequence;
        transferTick = tick;
        restoringTransfer = false;
        InvUtils.shiftClick().slotId(slot);
        return true;
    }

    /** Only server packet snapshots may commit a predicted transfer to the cargo ledger. */
    private void tickTransfer() {
        OrganizerJournal.Pending pending = pendingTransfer;
        if (activeChest == null) selectChest(parsePosition(pending.chest()));
        if (state == State.PAUSED || activeChest == null) return;
        ScreenHandler opened = tickOpenChest();
        if (opened == null) return;
        ServerInventory inventory = serverInventory(opened);
        Item item = Registries.ITEM.get(Identifier.of(pending.item()));
        int actual = inventoryCount(inventory, item, true, containerSlots(opened));
        int inContainer = inventoryCount(inventory, item, false, containerSlots(opened));
        OrganizerJournal.Result result = OrganizerJournal.reconcile(pending, actual, inContainer);
        if ((restoringTransfer || inventory.sequence > transferSequence) && result == OrganizerJournal.Result.CONFIRMED) {
            int moved = Math.abs(actual - pending.playerBefore());
            if (actual > 0) cargo.put(item, actual); else cargo.remove(item);
            pendingTransfer = null;
            transferTick = -1;
            transferFailures = 0;
            if (!saveCargo()) return;
            activity = (pending.extracting() ? "服务器确认取出 " : "服务器确认归类 ") + moved + " 个 " + item.getName().getString();
            if (needsCargoCheck) {
                state = cargo.isEmpty() ? State.EXTRACTING : State.DEPOSITING;
            }
            restoringTransfer = false;
            return;
        }
        if (restoringTransfer) {
            if (result == OrganizerJournal.Result.AMBIGUOUS) {
                pause("未完成转移的服务器库存与记录不一致，已保留物品和账本；请核对后恢复");
                return;
            }
            // A newly opened full snapshot proves that this intent was not executed.
            pendingTransfer = null;
            transferTick = -1;
            restoringTransfer = false;
            if (!saveCargo()) return;
            if (++transferFailures >= 2) {
                if (pending.extracting()) {
                    rejectedSourceSlots.add(pending.slot());
                    unresolved.add(activeChest);
                    warning("箱子 %s 的第 %d 格转移被服务器拒绝，本轮跳过。", activeChest.toShortString(), pending.slot());
                } else {
                    warning("目标箱 %s 无法收下 %s，尝试下一箱。", activeChest.toShortString(), item.getName().getString());
                    nextDestination();
                }
                transferFailures = 0;
            }
            return;
        }
        if (tick - transferTick >= TRANSFER_WAIT_TICKS) reopenForTransfer();
        else activity = "等待服务器确认物品转移";
    }

    private void reopenForTransfer() {
        closeChest();
        ownedContainer = null;
        openTick = aimTick = -1;
        openAttempts = 0;
        serverInventories.clear();
        inventoryUpdates.clear();
        restoringTransfer = pendingTransfer != null;
        activity = "重新打开箱子，核对服务器实际库存";
    }

    private ScreenHandler tickOpenChest() {
        ScreenHandler opened = currentContainer();
        if (ownedContainer != null && opened != ownedContainer) {
            ownedContainer = null;
            openTick = aimTick = -1;
        }
        if (opened != null && ownedContainer == null) {
            if (openTick < 0 || opened.syncId == previousSyncId) {
                releaseMovement();
                activity = "等待其他箱子界面关闭；整理不会关闭玩家或其他功能的界面";
                return null;
            }
            ownedContainer = opened;
            ownedAt = tick;
        }
        if (opened == null && (mc.currentScreen != null || mc.player.currentScreenHandler != mc.player.playerScreenHandler)) {
            releaseMovement();
            activity = "请关闭当前界面后继续";
            return null;
        }
        if (opened != null) {
            releaseMovement();
            ServerInventory inventory = serverInventory(opened);
            if (inventory != null && inventory.stacks.size() == opened.slots.size() && tick - ownedAt >= 2) return opened;
            if (tick - ownedAt > 100) pause("箱子已打开，但服务器未发送完整库存；保留进度等待恢复");
            else activity = "等待服务器完整箱子库存";
            return null;
        }
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
        if (aimTick < 0 || tick - aimTick > 2) {
            faceToward(hit.getPos());
            aimTick = tick;
            activity = "正对箱子";
            return null;
        }
        if (++openAttempts > 3) { chestUnreachable(); return null; }
        faceToward(hit.getPos());
        previousSyncId = mc.player.currentScreenHandler.syncId;
        serverInventories.clear();
        inventoryUpdates.clear();
        BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        openTick = tick;
        aimTick = -1;
        activity = "等待箱子打开";
        return null;
    }

    private void selectChest(BlockPos chest) {
        if (chest == null) { pause("没有登记可供核对库存的箱子"); return; }
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
        ownedContainer = null;
        serverInventories.clear();
        inventoryUpdates.clear();
    }

    private void chestUnreachable() {
        if (pendingTransfer != null) {
            pause("无法返回待确认转移的箱子，已保存转移记录；请恢复通路后重试");
            return;
        }
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
        saveCargo();
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

    private boolean restoreCargo() {
        journalWorld = mc.getServer() != null ? "local:" + mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath()
            : mc.getCurrentServerEntry() == null ? "unknown" : "server:" + mc.getCurrentServerEntry().address;
        journalDimension = mc.world.getRegistryKey().getValue().toString();
        journalPlayer = mc.player.getUuidAsString();
        try {
            String key = journalWorld + "\n" + journalDimension + "\n" + journalPlayer;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            journal = new OrganizerJournal(mc.runDirectory.toPath().resolve("config/builder_toolbox/chest-organizer-" + hash + ".properties"));
            if (journal.exists()) {
                OrganizerJournal.State saved = journal.read();
                if (!saved.world().equals(journalWorld) || !saved.dimension().equals(journalDimension) || !saved.player().equals(journalPlayer))
                    throw new IOException("整理进度所属存档、维度或玩家不同");
                for (var entry : saved.cargo().entrySet()) {
                    Identifier id = Identifier.tryParse(entry.getKey());
                    Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
                    if (item == Items.AIR) throw new IOException("整理记录中的物品不存在：" + entry.getKey());
                    cargo.put(item, entry.getValue());
                }
                pendingTransfer = saved.pending();
                if (pendingTransfer != null) {
                    Identifier id = Identifier.tryParse(pendingTransfer.item());
                    if (id == null || Registries.ITEM.get(id) == Items.AIR || parsePosition(pendingTransfer.chest()) == null)
                        throw new IOException("未完成转移的物品或箱子坐标无效");
                }
                if (!cargo.isEmpty() || pendingTransfer != null) {
                    walkY = saved.walkY();
                    restoringTransfer = pendingTransfer != null;
                    needsCargoCheck = true;
                    info("已恢复整理记录：%d 种待归类物品%s。先核对服务器库存，再回存。",
                        cargo.size(), pendingTransfer == null ? "" : "，1 次待确认转移");
                }
            }
            return true;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            journal = null; // Never overwrite a corrupt or incompatible ledger.
            state = State.PAUSED;
            activity = "整理进度读取失败：" + exception.getMessage();
            warning("%s", activity);
            return false;
        }
    }

    private boolean saveCargo() {
        if (journal == null) return false;
        Map<String, Integer> savedCargo = new HashMap<>();
        cargo.forEach((item, count) -> savedCargo.put(Registries.ITEM.getId(item).toString(), count));
        try {
            journal.write(new OrganizerJournal.State(journalWorld, journalDimension, journalPlayer, walkY, savedCargo, pendingTransfer));
            return true;
        } catch (IOException | RuntimeException exception) {
            releaseMovement();
            state = State.PAUSED;
            activity = "整理进度保存失败，已停止物品操作：" + exception.getMessage();
            warning("%s", activity);
            return false;
        }
    }

    private boolean validateCargo(ServerInventory inventory) {
        for (var entry : cargo.entrySet()) {
            if (inventoryCount(inventory, entry.getKey(), true, containerSlots(ownedContainer)) != entry.getValue()) {
                pause("背包中的待归类物品与已保存记录不一致；不会把玩家原有物品当作整理任务物品");
                return false;
            }
        }
        return true;
    }

    private void receiveInventoryUpdates() {
        InventoryUpdate update;
        while ((update = inventoryUpdates.poll()) != null) {
            if (update.slot < 0) {
                serverInventories.put(update.syncId, new ServerInventory(new ArrayList<>(update.contents), ++inventorySequence));
            } else {
                ServerInventory inventory = serverInventories.get(update.syncId);
                if (inventory != null && update.slot < inventory.stacks.size()) {
                    inventory.stacks.set(update.slot, update.stack);
                    inventory.sequence = ++inventorySequence;
                }
            }
        }
    }

    private ServerInventory serverInventory(ScreenHandler handler) {
        return handler == null ? null : serverInventories.get(handler.syncId);
    }

    private static int inventoryCount(ServerInventory inventory, Item item, boolean player, int containerSlots) {
        int result = 0;
        int first = player ? containerSlots : 0;
        int end = player ? Math.min(inventory.stacks.size(), containerSlots + 36) : containerSlots;
        for (int slot = first; slot < end; slot++) {
            ItemStack stack = inventory.stacks.get(slot);
            if (stack.isOf(item)) result += stack.getCount();
        }
        return result;
    }

    private static String position(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }

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
        if (!isActive()) {
            if (selectChests.get()) selectChests.set(false);
            if (selectOverflow.get()) selectOverflow.set(false);
            return;
        }
        else if (state != State.REGISTERING) {
            closeChest();
            releaseMovement();
            state = State.REGISTERING;
        }
        if (mc.player != null && mc.player.currentScreenHandler == mc.player.playerScreenHandler) mc.setScreen(null);
        else activity = "请先关闭当前箱子，再右键登记位置";
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive() || state != State.REGISTERING || mc.player == null || mc.world == null
            || mc.currentScreen != null
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
        if (mc.player != null && mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;
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
        if (journal == null || !mc.world.getRegistryKey().getValue().toString().equals(journalDimension)) {
            warning("请返回原维度并重新开启模块，以重新读取整理记录。", new Object[0]);
            return;
        }
        if (labels.isEmpty() || overflowConflictsWithLabel()) {
            warning("请先修正分类箱和杂物箱的登记。", new Object[0]);
            return;
        }
        if (pendingTransfer != null || !cargo.isEmpty()) {
            depositItem = null;
            triedDestinations.clear();
            needsCargoCheck = true;
            restoringTransfer = pendingTransfer != null;
            clearChest();
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
        if (mc.player != null && ownedContainer != null && mc.player.currentScreenHandler == ownedContainer
            && ownedContainer.getCursorStack().isEmpty()) mc.player.closeHandledScreen();
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
        pendingTransfer = null;
        restoringTransfer = false;
        needsCargoCheck = false;
        ownedContainer = null;
        journal = null;
        serverInventories.clear();
        inventoryUpdates.clear();
        inventorySequence = transferSequence = 0;
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

    private record InventoryUpdate(int syncId, int slot, List<ItemStack> contents, ItemStack stack) { }
    private static final class ServerInventory {
        private final List<ItemStack> stacks;
        private long sequence;
        private ServerInventory(List<ItemStack> stacks, long sequence) { this.stacks = stacks; this.sequence = sequence; }
    }
}
