package com.buildertoolbox.utils;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;
import net.minecraft.block.CarpetBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetCursorItemS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Returns cursor items without dropping them or exchanging unrelated stacks. */
public final class CursorRecovery {
    private static final int MAX_ATTEMPTS_WITHOUT_PROGRESS = 3;
    private static final long PREDICTION_HISTORY_NANOS = 10_000_000_000L;
    private final MinecraftClient mc;
    private final ConcurrentLinkedQueue<CursorReceipt> receipts = new ConcurrentLinkedQueue<>();
    private ScreenHandler originalHandler, pendingHandler, observedHandler;
    private ItemStack observedCursor = ItemStack.EMPTY;
    private int originalSlot=-1, attempts;
    private long nextAction, settleUntil, historyUntil, lastAction;
    private boolean recovered, needsSpace, lastActionWasReturn;
    private String status="";

    private record CursorReceipt(int syncId,ItemStack stack,long receivedAt) { }
    public CursorRecovery(MinecraftClient mc) { this.mc=mc; }
    public String status() { return status; }
    public void rememberOrigin(ScreenHandler handler,int slot) { originalHandler=handler;originalSlot=slot; }
    /** A stable local return may be provisional: vanilla omits replies when prediction matches. */
    public boolean consumeRecovered() { boolean result=recovered;recovered=false;return result; }
    public boolean needsSpace() { return needsSpace; }

    /** Receive callbacks only copy observations; inventory is never mutated on the networking thread. */
    public void onPacket(Packet<?> packet) {
        if(packet instanceof InventoryS2CPacket inventory)
            receipts.add(new CursorReceipt(inventory.syncId(),inventory.cursorStack().copy(),System.nanoTime()));
        else if(packet instanceof SetCursorItemS2CPacket cursor)
            receipts.add(new CursorReceipt(-1,cursor.contents().copy(),System.nanoTime()));
        else if(packet instanceof ScreenHandlerSlotUpdateS2CPacket slot&&slot.getSyncId()==-1&&slot.getSlot()==-1)
            receipts.add(new CursorReceipt(-1,slot.getStack().copy(),System.nanoTime()));
    }

    public void reset() {
        originalHandler=pendingHandler=observedHandler=null;originalSlot=-1;attempts=0;
        observedCursor=ItemStack.EMPTY;nextAction=settleUntil=historyUntil=lastAction=0;
        recovered=needsSpace=lastActionWasReturn=false;status="";receipts.clear();
    }
    public boolean tick(boolean allowed) { return tick(allowed,stack->false,stack->false); }
    public boolean tick(boolean allowed,Predicate<ItemStack> storageCursorAllowed) {
        return tick(allowed,storageCursorAllowed,stack->false);
    }

    /** Both storage predicates apply only after the caller has verified current handler ownership. */
    public boolean tick(boolean allowed,Predicate<ItemStack> storageCursorAllowed,Predicate<ItemStack> storageUnloadAllowed) {
        if(mc.player==null||mc.interactionManager==null)return false;
        ScreenHandler handler=mc.player.currentScreenHandler;
        ItemStack cursor=handler.getCursorStack();
        long now=System.nanoTime();
        int receipt=latestReceipt(handler);
        if(lastActionWasReturn&&receipt!=0) {
            // A received correction may still be queued for vanilla's main-thread handler.
            if(receipt<0&&cursor.isEmpty()||receipt>0&&!cursor.isEmpty()) {
                status="已收到服务器鼠标状态，等待界面应用更正";
                nextAction=now+250_000_000L;
                return true;
            }
            if(receipt>0&&cursor.isEmpty()) {
                historyUntil=0;attempts=0;needsSpace=false;
                observedHandler=null;observedCursor=ItemStack.EMPTY;
            } else if(receipt<0) {
                // Preserve the previous count: identical rejected cursors cannot gain a new budget.
                historyUntil=now+PREDICTION_HISTORY_NANOS;
            }
        }
        if(cursor.isEmpty()) {
            if(pendingHandler==handler&&now<settleUntil) {
                status="客户端已收回鼠标物品，等待可能的库存更正";return true;
            }
            if(pendingHandler!=null)recovered=true;
            pendingHandler=null;needsSpace=false;
            // Keep provisional history so a late server rollback does not restart attempts forever.
            if(now>=historyUntil) {
                observedHandler=null;observedCursor=ItemStack.EMPTY;attempts=0;
                originalHandler=null;originalSlot=-1;nextAction=0;
            }
            return false;
        }
        if(CursorRecoveryPolicy.progressed(handler!=observedHandler,
            ItemStack.areItemsAndComponentsEqual(cursor,observedCursor),observedCursor.getCount(),cursor.getCount())) {
            observedHandler=handler;attempts=0;needsSpace=false;nextAction=0;
        }
        observedCursor=cursor.copy();
        if(!allowed) {status="鼠标持有物品：请关闭手动打开的界面，之后自动收回";return true;}
        if(now<nextAction) {
            status=needsSpace?"鼠标物品等待安全空位，先使用已有材料腾出空间":"正在安全收回鼠标物品，等待库存更新";
            return !needsSpace;
        }
        int slot=findDestination(handler,storageCursorAllowed);
        if(slot<0) {
            if(parkAcceptedHotbarStack(handler,storageUnloadAllowed)) {
                recordAction(handler,now,false);
                status="将本箱对应的整组材料退存空槽，为鼠标物品腾出背包格";
                return true;
            }
            needsSpace=++attempts>=MAX_ATTEMPTS_WITHOUT_PROGRESS;
            nextAction=now+(needsSpace?5_000_000_000L:500_000_000L);
            status="鼠标物品保留中：暂无兼容空位，继续寻找安全腾位方式";
            return !needsSpace;
        }
        mc.interactionManager.clickSlot(handler.syncId,slot,0,SlotActionType.PICKUP,mc.player);
        recordAction(handler,now,true);
        status="正在将鼠标物品放回原槽、背包或对应的已登记箱子";
        return true;
    }

    /** +1 is server-empty, -1 is server-nonempty; no response is also normal in vanilla. */
    private int latestReceipt(ScreenHandler handler) {
        int result=0;
        for(CursorReceipt receipt;(receipt=receipts.poll())!=null;) {
            if((receipt.syncId()<0||receipt.syncId()==handler.syncId)&&receipt.receivedAt()>=lastAction)
                result=receipt.stack().isEmpty()?1:-1;
        }
        return result;
    }
    private void recordAction(ScreenHandler handler,long now,boolean cursorReturn) {
        pendingHandler=handler;lastAction=now;lastActionWasReturn=cursorReturn;
        settleUntil=now+500_000_000L;historyUntil=now+PREDICTION_HISTORY_NANOS;
        needsSpace=++attempts>=MAX_ATTEMPTS_WITHOUT_PROGRESS;
        nextAction=now+(cursorReturn&&needsSpace?5_000_000_000L:500_000_000L);
    }
    private int findDestination(ScreenHandler handler,Predicate<ItemStack> storageCursorAllowed) {
        if(handler==originalHandler&&originalSlot>=0&&originalSlot<handler.slots.size()
            &&fits(handler.getSlot(originalSlot),handler.getCursorStack()))return originalSlot;
        for(boolean empty:new boolean[]{false,true})for(int i=0;i<handler.slots.size();i++) {
            Slot slot=handler.getSlot(i);
            if(slot.inventory!=mc.player.getInventory()||slot.getIndex()<0||slot.getIndex()>=36
                ||slot.getStack().isEmpty()!=empty)continue;
            if(fits(slot,handler.getCursorStack()))return i;
        }
        // Only ordinary carpet is parked offhand. Job maps and glass panes must keep their tracked main-inventory slots.
        if(handler==mc.player.playerScreenHandler&&handler.getCursorStack().getItem() instanceof BlockItem block
            &&block.getBlock() instanceof CarpetBlock) {
            for(int i=0;i<handler.slots.size();i++) {
                Slot slot=handler.getSlot(i);
                if(slot.inventory==mc.player.getInventory()&&slot.getIndex()==40
                    &&CursorRecoveryPolicy.playerDestination(slot.getIndex(),true)
                    &&fits(slot,handler.getCursorStack()))return i;
            }
        }
        if(storageSlots(handler)>0&&storageCursorAllowed.test(handler.getCursorStack())) {
            for(boolean empty:new boolean[]{false,true})for(int i=0;i<storageSlots(handler);i++) {
                Slot slot=handler.getSlot(i);
                if(slot.inventory==mc.player.getInventory()||slot.getStack().isEmpty()!=empty)continue;
                if(fits(slot,handler.getCursorStack()))return i;
            }
        }
        return -1;
    }
    private boolean parkAcceptedHotbarStack(ScreenHandler handler,Predicate<ItemStack> storageUnloadAllowed) {
        int storage=storageSlots(handler);
        if(storage<=0)return false;
        for(int hotbar=0;hotbar<9;hotbar++) {
            ItemStack stack=mc.player.getInventory().getStack(hotbar);
            if(stack.isEmpty())continue;
            boolean accepted=storageUnloadAllowed.test(stack);
            if(!accepted)continue;
            for(int destination=0;destination<storage;destination++) {
                Slot slot=handler.getSlot(destination);
                if(!slot.canInsert(stack)||!CursorRecoveryPolicy.canParkWholeHotbarStack(true,accepted,
                    slot.getStack().isEmpty(),stack.getCount(),slot.getMaxItemCount(stack)))continue;
                // SWAP with a verified empty storage slot transfers this entire hotbar stack and leaves the cursor untouched.
                mc.interactionManager.clickSlot(handler.syncId,destination,hotbar,SlotActionType.SWAP,mc.player);
                return true;
            }
        }
        return false;
    }
    private int storageSlots(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler storage?storage.getRows()*9
            :handler instanceof ShulkerBoxScreenHandler?27:0;
    }
    private boolean fits(Slot slot,ItemStack cursor) {
        ItemStack existing=slot.getStack();
        return slot.canInsert(cursor)&&(existing.isEmpty()||ItemStack.areItemsAndComponentsEqual(cursor,existing))
            &&existing.getCount()<slot.getMaxItemCount(cursor);
    }
}
