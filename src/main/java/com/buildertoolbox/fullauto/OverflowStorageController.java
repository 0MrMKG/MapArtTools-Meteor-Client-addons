package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

/** Optional restock prelude: put whole surplus stacks into registered storage, then yield to supplies. */
public final class OverflowStorageController {
    private record Update(int sync, int slot, List<ItemStack> contents, ItemStack stack, long receivedAt) { }
    private final MinecraftClient mc;
    private final BatchWorkstationAccess access;
    private final ConcurrentLinkedQueue<Update> updates = new ConcurrentLinkedQueue<>();
    private final Map<BlockPos, Long> unavailableUntil = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final StationaryRestockWatchdog stationary = new StationaryRestockWatchdog();
    private Map<Item, Integer> keep = Map.of();
    private Set<Item> materials = Set.of();
    private volatile boolean active;
    private BlockPos target;
    private int moved, attempts, pendingSlot = -1, pendingInventorySlot = -1, pendingPlayerSlot = -1, pendingSync = -1;
    private ItemStack pendingStack = ItemStack.EMPTY;
    private int acknowledgedCount = -1;
    private boolean verifyingTransfer;
    private long sentAt, transferTick = -1, nextClickTick;
    private String status = "空闲", warning;

    public OverflowStorageController(MinecraftClient mc) { this.mc = mc; access = new BatchWorkstationAccess(mc); }
    public boolean active() { return active; }
    public String status() { return status; }
    public String warning() { return warning; }
    public boolean owns(ScreenHandler handler) { return access.owns(handler); }
    public boolean closingMenu() { return access.closingMenu(); }
    public boolean hidesScreen(Screen screen) { return access.hidesScreen(screen); }
    public void release(long tick) { access.release(tick); }
    public void claimPendingHandler(long tick) { access.claimPendingHandler(tick); }

    public boolean begin(int floorY, List<BlockPos> containers, Map<Item, Integer> demand, Set<Item> eligible,
                         long tick) {
        if (active || mc.player == null || containers.isEmpty()) return false;
        keep = Map.copyOf(demand); materials = Set.copyOf(eligible);
        int slots = surplus().size();
        if (slots == 0
            || containers.stream().noneMatch(pos -> unavailableUntil.getOrDefault(pos, 0L) <= tick)) return false;
        access.start(floorY); visited.clear(); stationary.reset(); updates.clear(); clearTransfer();
        active = true; target = null; moved = attempts = 0; nextClickTick = tick; warning = null;
        status = "补货前回存多余材料：可腾出 " + slots + " 格";
        return true;
    }

    public void receive(Packet<?> packet) {
        if (packet instanceof InventoryS2CPacket inventory) {
            access.inventoryReceived(inventory.syncId());
            if (active) updates.add(new Update(inventory.syncId(), -1,
                inventory.contents().stream().map(ItemStack::copy).toList(), ItemStack.EMPTY, System.nanoTime()));
        } else if (active && packet instanceof ScreenHandlerSlotUpdateS2CPacket slot) {
            updates.add(new Update(slot.getSyncId(), slot.getSlot(), List.of(), slot.getStack().copy(), System.nanoTime()));
        }
    }

    /** true while cleanup still owns the worker; false once normal restocking may continue. */
    public boolean tick(List<BlockPos> containers, long tick) {
        if (!active || mc.player == null || mc.world == null) return false;
        access.claimPendingHandler(tick);
        drainUpdates();
        if (transferTick >= 0) {
            access.release(tick);
            if (verifyingTransfer) {
                ScreenHandler synchronizedBox = access.open(target, false, tick);
                if (access.failure() != null) { deferTarget(tick, access.failure()); return true; }
                if (synchronizedBox != null) {
                    // An exact vanilla prediction can suppress individual slot
                    // corrections. A reopened full inventory is authoritative too.
                    acknowledgedCount = sourceCount(mc.player.getInventory().getStack(pendingInventorySlot));
                    if (acknowledgedCount < 0 || acknowledgedCount >= pendingStack.getCount()) {
                        clearTransfer();
                        if (++attempts >= 3) deferTarget(tick, "同步后仍未确认物品转移");
                        status = "重新同步完成，待重试未转移的材料";
                        return true;
                    }
                }
            }
            if (acknowledgedCount >= 0 && acknowledgedCount < pendingStack.getCount()
                && sourceCount(mc.player.getInventory().getStack(pendingInventorySlot)) == acknowledgedCount) {
                moved += pendingStack.getCount() - acknowledgedCount;
                clearTransfer(); attempts = 0; stationary.reset(); nextClickTick = tick + 1;
                status = "已确认回存 " + moved + " 个多余材料";
                return true;
            }
            if (!verifyingTransfer && tick - transferTick >= 6) {
                access.reset(); verifyingTransfer = true;
                status = "重新开箱核对服务器回存结果";
                return true;
            }
            if (tick - transferTick < 60) { status = "等待服务器确认回存，已回存 " + moved + " 个"; return true; }
            clearTransfer();
            // Do not retry against client prediction after a missing acknowledgement.
            // Reopen a fully synchronized container before choosing another source stack.
            access.reset(); nextClickTick = tick + 2;
            if (++attempts >= 3) deferTarget(tick, "回存连续未获服务器确认");
            status = "回存未确认，重新同步箱子和背包";
            return true;
        }
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            access.release(tick); status = "等待自动收回鼠标物品后继续回存"; return true;
        }
        List<Integer> surplus = surplus();
        if (surplus.isEmpty()) { finish("材料回存完成，共 " + moved + " 个"); return false; }
        if (target != null && !containers.contains(target)) { access.reset(); target = null; }
        if (target == null) {
            target = containers.stream().filter(pos -> !visited.contains(pos) && unavailableUntil.getOrDefault(pos, 0L) <= tick)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getX(), mc.player.getY(), mc.player.getZ())))
                .orElse(null);
            if (target == null) {
                warning = "回收箱均已满、暂不可达或无法确认；保留背包内材料，继续正常补货/铺设";
                finish(warning); return false;
            }
            visited.add(target); stationary.reset(); attempts = 0;
        }
        if (stationary.stalled(tick, mc.player.getX(), mc.player.getZ(), true)) {
            deferTarget(tick, "三分钟没有移动或回存进展"); return true;
        }
        ScreenHandler opened = access.open(target, false, tick);
        if (access.failure() != null) { deferTarget(tick, access.failure()); return true; }
        if (opened == null) { status = "前往回收箱 " + target.toShortString() + "：" + access.activity(); return true; }
        if (tick < nextClickTick) return true;
        int storageSlots = StorageBlockUtils.storageSlots(opened);
        for (int inventorySlot : surplus) {
            ItemStack stack = mc.player.getInventory().getStack(inventorySlot);
            int handlerSlot = opened.getSlotIndex(mc.player.getInventory(), inventorySlot).orElse(-1);
            if (handlerSlot < 0 || stack.isEmpty() || !fitsWhole(opened, storageSlots, stack)) continue;
            ItemStack shown = opened.getSlot(handlerSlot).getStack();
            if (shown.getCount() != stack.getCount() || !ItemStack.areItemsAndComponentsEqual(shown, stack)) continue;
            pendingSlot = handlerSlot; pendingInventorySlot = inventorySlot; pendingSync = opened.syncId;
            pendingPlayerSlot = mc.player.playerScreenHandler.getSlotIndex(mc.player.getInventory(), inventorySlot).orElse(-1);
            pendingStack = stack.copy(); acknowledgedCount = -1;
            updates.clear(); sentAt = System.nanoTime(); transferTick = tick;
            mc.interactionManager.clickSlot(opened.syncId, handlerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
            status = "回存 " + stack.getItem().getName().getString() + " 到 " + target.toShortString();
            return true;
        }
        deferTarget(tick, "剩余空间装不下待回存的完整物品组");
        return true;
    }

    private List<Integer> surplus() {
        List<OverflowMaterialPlanner.Stack<Item>> stacks = new ArrayList<>();
        if (mc.player != null) for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty()) stacks.add(new OverflowMaterialPlanner.Stack<>(slot, stack.getItem(), stack.getCount()));
        }
        return OverflowMaterialPlanner.surplusSlots(stacks, keep, materials);
    }

    private boolean fitsWhole(ScreenHandler handler, int storageSlots, ItemStack stack) {
        int capacity = 0;
        for (int slot = 0; slot < storageSlots; slot++) {
            var destination = handler.getSlot(slot);
            ItemStack current = destination.getStack();
            if (!destination.canInsert(stack) || !current.isEmpty() && !ItemStack.areItemsAndComponentsEqual(current, stack)) continue;
            capacity += Math.max(0, destination.getMaxItemCount(stack) - current.getCount());
            if (capacity >= stack.getCount()) return true;
        }
        return false;
    }

    private void drainUpdates() {
        Update update;
        while ((update = updates.poll()) != null) {
            if (transferTick < 0 || update.receivedAt() < sentAt) continue;
            if (update.slot() < 0 && update.sync() == pendingSync && pendingSlot < update.contents().size())
                acknowledgedCount = sourceCount(update.contents().get(pendingSlot));
            else if (update.sync() == pendingSync && update.slot() == pendingSlot
                || update.sync() == -2 && update.slot() == pendingInventorySlot
                || update.sync() == 0 && pendingPlayerSlot >= 0 && update.slot() == pendingPlayerSlot)
                acknowledgedCount = sourceCount(update.stack());
        }
    }

    private int sourceCount(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.areItemsAndComponentsEqual(stack, pendingStack) ? stack.getCount() : -1;
    }

    private void clearTransfer() {
        transferTick = -1; pendingSlot = pendingInventorySlot = pendingPlayerSlot = pendingSync = -1;
        pendingStack = ItemStack.EMPTY; acknowledgedCount = -1; verifyingTransfer = false;
    }

    private void deferTarget(long tick, String reason) {
        if (target != null) unavailableUntil.put(target, tick + 20L * 60);
        status = "回收箱暂不可用，改试下一箱：" + reason;
        access.reset(); target = null; stationary.reset(); clearTransfer();
    }

    private void finish(String message) { access.reset(); active = false; target = null; status = message; updates.clear(); }

    public boolean prepareForAutoEat(long tick) {
        if (!active) return true;
        access.release(tick);
        if (transferTick >= 0 || mc.player == null || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return false;
        if (!access.owns(mc.player.currentScreenHandler) && (access.busyForAutoEat()
            || mc.player.currentScreenHandler != mc.player.playerScreenHandler)) return false;
        access.reset();
        return mc.player.currentScreenHandler == mc.player.playerScreenHandler;
    }

    public void stop() {
        access.reset(); active = false; target = null; visited.clear(); stationary.reset(); clearTransfer(); updates.clear();
        unavailableUntil.clear();
    }
}
