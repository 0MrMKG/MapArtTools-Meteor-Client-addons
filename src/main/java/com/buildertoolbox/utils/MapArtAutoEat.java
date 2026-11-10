package com.buildertoolbox.utils;

import java.util.function.Consumer;
import com.buildertoolbox.client.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

/** Per-worker food session; never sorts inventory or uses cursor pickup clicks. */
public final class MapArtAutoEat {
    private final MinecraftClient mc;
    private final Consumer<String> warning;
    private boolean eating, ownsUse;
    private int previousSlot=-1, foodSlot=-1, swappedFrom=-1, swapHotbar=-1;
    private ItemStack displaced=ItemStack.EMPTY;
    private long tick, cooldownUntil, progressAt, lastAttempt=-100, readyAt;
    private int hungerBefore, countBefore, attempts, prepareAttempts;

    public MapArtAutoEat(MinecraftClient mc, Consumer<String> warning) { this.mc=mc;this.warning=warning; }
    public boolean eating() { return eating; }
    public boolean wanted(boolean enabled) {
        return mc.player!=null && (eating || System.currentTimeMillis()>=cooldownUntil)
            && AutoEatPolicy.shouldEat(enabled,mc.player.isCreative(),mc.player.getHungerManager().getFoodLevel(),eating);
    }
    public String status() { return "自动进食："+mc.player.getHungerManager().getFoodLevel()+"/20，吃满后继续"; }

    public boolean tick(boolean enabled) {
        tick++;
        if(!wanted(enabled)) { stop();return false; }
        if(mc.player==null||mc.interactionManager==null||mc.player.currentScreenHandler!=mc.player.playerScreenHandler
            ||!mc.player.currentScreenHandler.getCursorStack().isEmpty()) { stop();return false; }
        if(!eating) {
            if(mc.player.isUsingItem())return false;
            if(findFood()<0)return fail("背包中没有可用的普通食物，请补充食物；当前任务继续运行");
            eating=true;previousSlot=mc.player.getInventory().getSelectedSlot();
            hungerBefore=mc.player.getHungerManager().getFoodLevel();progressAt=tick;attempts=prepareAttempts=0;
        }
        int hunger=mc.player.getHungerManager().getFoodLevel();
        if(hunger>hungerBefore){hungerBefore=hunger;progressAt=tick;attempts=prepareAttempts=0;}
        if(foodSlot<0||!edible(mc.player.getInventory().getStack(foodSlot))) {
            releaseUse();restoreSwap();
            int slot=findFood();
            if(slot<0)return fail("食物已用完，饱食度未满；请补充食物，当前任务继续运行");
            if(++prepareAttempts>3)return fail("食物切换连续未获确认，已恢复任务，稍后重试");
            prepare(slot);return true;
        }
        InvUtils.swap(foodSlot,false);
        ItemStack held=mc.player.getMainHandStack();
        if(held.getCount()<countBefore){countBefore=held.getCount();progressAt=tick;attempts=prepareAttempts=0;}
        var consumable=held.get(DataComponentTypes.CONSUMABLE);
        if(tick-progressAt>AutoEatPolicy.timeoutTicks(consumable==null?1.6f:consumable.consumeSeconds()))
            return fail("自动进食未获确认，已释放使用键并恢复任务，稍后重试");
        if(tick<readyAt)return true;
        if(mc.player.isUsingItem()) { pressUse();return true; }
        if(tick-lastAttempt<10)return true;
        if(attempts>=3)return fail("服务器连续拒绝进食，已恢复任务，稍后重试");
        pressUse();
        mc.interactionManager.interactItem(mc.player,Hand.MAIN_HAND);
        lastAttempt=tick;attempts++;
        return true;
    }

    private void prepare(int slot) {
        foodSlot=slot;
        if(slot>=9) {
            swapHotbar=previousSlot;swappedFrom=slot;
            displaced=mc.player.getInventory().getStack(swapHotbar).copy();
            // One SWAP packet: works even with a full hotbar, without a cursor stack.
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,slot,swapHotbar,SlotActionType.SWAP,mc.player);
            foodSlot=swapHotbar;
        }
        InvUtils.swap(foodSlot,false);
        countBefore=mc.player.getInventory().getStack(foodSlot).getCount();
        progressAt=tick;readyAt=tick+2;attempts=0;lastAttempt=tick-10;
    }

    private int findFood() {
        int best=-1;float score=-1;
        for(int slot=0;slot<36;slot++) {
            ItemStack stack=mc.player.getInventory().getStack(slot);
            if(!edible(stack))continue;
            var food=stack.get(DataComponentTypes.FOOD);
            float value=food.nutrition()*4f+food.saturation();
            if(value>score){score=value;best=slot;}
        }
        return best;
    }
    private boolean edible(ItemStack stack) {
        var food=stack.get(DataComponentTypes.FOOD);
        return !stack.isEmpty()&&food!=null&&food.nutrition()>0&&stack.get(DataComponentTypes.CONSUMABLE)!=null
            &&!stack.isOf(Items.CHORUS_FRUIT)&&!stack.isOf(Items.PUFFERFISH)&&!stack.isOf(Items.SPIDER_EYE)
            &&!stack.isOf(Items.POISONOUS_POTATO)&&!stack.isOf(Items.ROTTEN_FLESH)
            &&!stack.isOf(Items.CHICKEN)&&!stack.isOf(Items.SUSPICIOUS_STEW);
    }

    private void pressUse() {
        if(!mc.options.useKey.isPressed())ownsUse=true;
        mc.options.useKey.setPressed(true);
    }
    private void releaseUse() {
        if(!ownsUse)return;
        if(mc.options!=null)mc.options.useKey.setPressed(false);
        if(mc.player!=null&&mc.interactionManager!=null&&mc.player.isUsingItem())mc.interactionManager.stopUsingItem(mc.player);
        ownsUse=false;
    }
    private void restoreSwap() {
        if(swappedFrom<0)return;
        if(mc.player!=null&&mc.interactionManager!=null&&mc.player.currentScreenHandler==mc.player.playerScreenHandler
            &&mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            ItemStack current=mc.player.getInventory().getStack(swappedFrom);
            boolean same=current.isEmpty()&&displaced.isEmpty()
                ||current.getCount()==displaced.getCount()&&ItemStack.areItemsAndComponentsEqual(current,displaced);
            if(same)mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId,swappedFrom,swapHotbar,SlotActionType.SWAP,mc.player);
            else warning.accept("进食期间背包内容发生变化，未盲目换回物品；任务将重新选择材料");
        }
        swappedFrom=swapHotbar=-1;displaced=ItemStack.EMPTY;
    }
    public void stop() {
        releaseUse();restoreSwap();
        if(mc.player!=null&&previousSlot>=0)InvUtils.swap(previousSlot,false);
        eating=false;previousSlot=foodSlot=-1;
    }
    private boolean fail(String reason) {
        stop();cooldownUntil=System.currentTimeMillis()+10000;warning.accept(reason);return false;
    }
}
