package com.buildertoolbox.fullauto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.buildertoolbox.client.utils.misc.input.Input;
import com.buildertoolbox.client.utils.world.BlockUtils;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CartographyTableScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Ground travel and an owned, confirmed container/workbench opening session. */
public final class BatchWorkstationAccess {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private final MinecraftClient mc;
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private BlockPos target;
    private BlockPos stand;
    private BlockPos pathGoal;
    private ScreenHandler owned;
    private ScreenHandler recoveryOwned, recoveryCandidate;
    private BlockPos recoveryPosition, recoveryCandidatePosition;
    private boolean requestedCrafting, recoveryCandidateCrafting;
    private int requestedFromSyncId = -1, recoveryCandidateFromSyncId = -1;
    private long recoveryCandidateAt = -1;
    private volatile int inventorySyncId = -1;
    private long ownedAt;
    private int walkY;
    private int pathIndex;
    private int routeAttempts;
    private int openAttempts;
    private long requestedAt = -1;
    private long aimedAt = -1;
    private long approachAimedAt = -1;
    private BlockPos approachTarget;
    private long progressAt;
    private long stoppedAt = -100;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean ownsForward;
    private boolean leverWaterTravel;
    private boolean closingMenu;
    private BlockPos travelProxy;
    private BlockPos unstuckGoal;
    private long unstuckUntil;
    private long currentTick;
    private String failure;
    private String activity = "空闲";

    public BatchWorkstationAccess(MinecraftClient mc) { this.mc = mc; }
    public String failure() { return failure; }
    public String activity() { return activity; }
    public void start(int floorY) { reset(); walkY = floorY; }
    public void inventoryReceived(int syncId) { inventorySyncId = syncId; }

    /** No interaction: claim only a handler backed by our request and its full server inventory. */
    public void claimPendingHandler(long tick) {
        currentTick=tick;
        if(mc.player==null)return;
        ScreenHandler current=mc.player.currentScreenHandler;
        if(recoveryCandidate!=null) {
            if(current!=recoveryCandidate)clearRecoveryCandidate();
            else if(ContainerOwnershipPolicy.mayClaim(true,recoveryCandidatePosition!=null,
                current==mc.player.playerScreenHandler,expectedType(current,recoveryCandidateCrafting),
                current.syncId,recoveryCandidateFromSyncId,inventorySyncId)) {
                recoveryOwned=current;recoveryPosition=recoveryCandidatePosition;clearRecoveryCandidate();
            } else if(tick-recoveryCandidateAt>100)failure="先前打开的自有界面未收到服务器物品回执，请等待同步后恢复";
        }
        if(owned==current)return;
        if(ContainerOwnershipPolicy.mayClaim(requestedAt>=0,target!=null,current==mc.player.playerScreenHandler,
            expectedType(current,requestedCrafting),current.syncId,requestedFromSyncId,inventorySyncId)) {
            owned=current;ownedAt=tick;release(tick);
        } else if(requestedAt>=0&&current!=mc.player.playerScreenHandler
            &&expectedType(current,requestedCrafting)&&tick-requestedAt>100)
            failure="界面已打开，但未收到服务器的完整物品列表";
    }

    private boolean expectedType(ScreenHandler handler,boolean crafting) {
        return crafting?handler instanceof CartographyTableScreenHandler:StorageBlockUtils.storageSlots(handler)>0;
    }

    private void clearRecoveryCandidate() {
        recoveryCandidate=null;recoveryCandidatePosition=null;recoveryCandidateFromSyncId=-1;recoveryCandidateAt=-1;
    }

    public ScreenHandler open(BlockPos pos, boolean crafting, long tick) {
        if (mc.player == null || mc.world == null || pos == null) return null;
        currentTick = tick;
        if (!pos.equals(target)) {
            reset();
            target = pos.toImmutable();
            requestedCrafting = crafting;
        }
        setLeverWaterTravel(false);
        if (failure != null) return null;
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) { approach(pos, tick); return null; }
        if (crafting ? !mc.world.getBlockState(pos).isOf(Blocks.CARTOGRAPHY_TABLE) : !StorageBlockUtils.isStorage(mc, pos)) {
            failure = "目标方块已变化或尚未加载：" + pos.toShortString();
            release(tick);
            return null;
        }
        ScreenHandler current = mc.player.currentScreenHandler;
        if(recoveryMatches(pos,crafting)&&current.getCursorStack().isEmpty()) {
            // This session was already acknowledged before reset. Continue its
            // inputs in place instead of closing a full-inventory cartography table.
            owned=current;ownedAt=tick;inventorySyncId=current.syncId;
            recoveryOwned=null;recoveryPosition=null;
        }
        claimPendingHandler(tick);
        if(!closeRecoveryHandlerIfSafe()) { release(tick);activity="先安全收回自有界面的鼠标物品";return null; }
        current=mc.player.currentScreenHandler;
        if (owned != null && current == owned) {
            release(tick);
            if (inventorySyncId == owned.syncId && tick - ownedAt >= 2) return owned;
            if (tick - ownedAt > 100) failure = "界面已打开，但未收到服务器的完整物品列表";
            activity = "等待服务器同步容器物品";
            return null;
        }
        if (owned != null) {
            owned = null;
            requestedAt = -1;
            openAttempts = 0;
        }
        boolean expected = expectedType(current,crafting);
        if (requestedAt >= 0 && current != mc.player.playerScreenHandler && expected) {
            release(tick);
            activity = "等待服务器同步容器物品";
            return null;
        }
        if ((mc.currentScreen != null && !(mc.currentScreen instanceof GameMenuScreen)) || current != mc.player.playerScreenHandler) {
            release(tick);
            activity = "请关闭其他界面后继续";
            return null;
        }
        if (requestedAt >= 0 && tick - requestedAt < 30) {
            release(tick);
            activity = "等待服务器打开界面";
            return null;
        }
        var hit = findHit(pos, mc.player.getEyePos(), 3.6);
        if (hit == null) {
            if (stand == null) stand = findStand(pos);
            if (stand == null) { failure = "找不到可走到的交互位置：" + pos.toShortString(); return null; }
            int result = navigate(stand, tick);
            if (result != 0) {
                rejectedStands.add(stand);
                stand = null;
                resetPath(tick);
            }
            activity = "前往 " + pos.toShortString();
            return null;
        }
        release(tick);
        Vec3d velocity = mc.player.getVelocity();
        if (tick - stoppedAt < 2 || velocity.x * velocity.x + velocity.z * velocity.z > 0.000625) return null;
        if (mc.player.isSneaking()) { activity = "请松开潜行键以打开容器"; return null; }
        if (aimedAt < 0) {
            face(hit.getPos());
            aimedAt = tick;
            return null;
        }
        if (tick <= aimedAt) return null;
        if (++openAttempts > 3) { failure = "服务器未打开目标界面：" + pos.toShortString(); return null; }
        face(hit.getPos());
        requestedFromSyncId=current.syncId;
        BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        requestedAt = tick;
        aimedAt = -1;
        activity = crafting ? "打开制图台" : "打开储藏方块";
        return null;
    }

    private BlockPos findStand(BlockPos pos) {
        List<BlockPos> options = new ArrayList<>();
        for (int x = pos.getX() - 4; x <= pos.getX() + 4; x++) {
            for (int z = pos.getZ() - 4; z <= pos.getZ() + 4; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                double y = surface(candidate);
                if (!Double.isFinite(y) || rejectedStands.contains(candidate)) continue;
                if (findHit(pos, new Vec3d(x + 0.5, y + 1.62, z + 0.5), 3.6) != null)
                    options.add(candidate);
            }
        }
        return options.stream().min(Comparator.comparingDouble(this::distanceSquared)).orElse(null);
    }

    private double surface(BlockPos pos) {
        if (mc.world == null || !mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return Double.NaN;
        var feet = mc.world.getBlockState(pos);
        if ((!feet.getCollisionShape(mc.world, pos).isEmpty() && !(feet.getBlock() instanceof CarpetBlock))
            || !mc.world.getBlockState(pos.up()).getCollisionShape(mc.world, pos.up()).isEmpty()) return Double.NaN;
        var below = mc.world.getBlockState(pos.down()).getCollisionShape(mc.world, pos.down());
        var feetFluid = mc.world.getFluidState(pos);
        var headFluid = mc.world.getFluidState(pos.up());
        if (below.isEmpty()
            || !LeverWaterPolicy.allowsFluid(leverWaterTravel, feetFluid.isEmpty(), feetFluid.isIn(FluidTags.WATER))
            || !LeverWaterPolicy.allowsFluid(leverWaterTravel, headFluid.isEmpty(), headFluid.isIn(FluidTags.WATER))) return Double.NaN;
        return feet.getBlock() instanceof CarpetBlock ? pos.getY() + 0.0625
            : pos.getY() - 1 + below.getMax(Direction.Axis.Y);
    }

    private boolean direct(BlockPos pos) {
        return GroundPathVisibility.canWalk(mc.player.getX(), mc.player.getZ(), pos.getX() + 0.5, pos.getZ() + 0.5,
            mc.player.getWidth() / 2.0, 0.6, 30000, (x, z) -> surface(new BlockPos(x, walkY, z)));
    }

    /** 0 moving, 1 arrived, -1 no route. */
    private int navigate(BlockPos goal, long tick) {
        if (reached(goal,true)) { resetPath(tick); return 1; }
        if (!goal.equals(pathGoal)) { resetPath(tick); pathGoal = goal; }
        if (unstuckGoal != null) {
            if (tick >= unstuckUntil || reached(unstuckGoal,false) || !direct(unstuckGoal)) {
                unstuckGoal = null; release(tick);
            } else {
                face(new Vec3d(unstuckGoal.getX()+0.5,mc.player.getEyePos().y,unstuckGoal.getZ()+0.5));
                mc.options.forwardKey.setPressed(true);Input.setKeyState(mc.options.forwardKey,true);ownsForward=true;
                if(mc.player.isSprinting())mc.player.setSprinting(false);
                return 0;
            }
        }
        if (!path.isEmpty() && tick - progressAt > 80) {
            path.clear(); release(tick);
            BlockPos feet = new BlockPos(mc.player.getBlockX(),walkY,mc.player.getBlockZ());
            for(int offset=0;offset<HORIZONTAL.length;offset++) {
                BlockPos candidate=feet.offset(HORIZONTAL[(routeAttempts+offset)%HORIZONTAL.length]);
                if(!Double.isFinite(surface(candidate))||!direct(candidate))continue;
                unstuckGoal=candidate;unstuckUntil=tick+12;break;
            }
            if(unstuckGoal!=null)return 0;
        }
        if (path.isEmpty()) {
            if (++routeAttempts > 3 || !plan(goal, tick)) return -1;
        }
        while (pathIndex < path.size() && reached(path.get(pathIndex),false)) {
            pathIndex++;
            bestDistance = Double.POSITIVE_INFINITY;
            progressAt = tick;
        }
        if (pathIndex >= path.size()) {
            release(tick);
            if(reached(goal,true))return 1;
            path.clear();pathIndex=0;
            return routeAttempts>3?-1:0;
        }
        for (int i = Math.min(path.size() - 1, pathIndex + 32); i > pathIndex; i--) {
            if (direct(path.get(i))) { pathIndex = i; bestDistance = Double.POSITIVE_INFINITY; break; }
        }
        BlockPos next = path.get(pathIndex);
        if (!direct(next)) { path.clear(); release(tick); return 0; }
        double distance = Math.sqrt(distanceSquared(next));
        if (distance + 0.04 < bestDistance) { bestDistance = distance; progressAt = tick; }
        face(new Vec3d(next.getX() + 0.5, mc.player.getEyePos().y, next.getZ() + 0.5));
        mc.options.forwardKey.setPressed(true);
        Input.setKeyState(mc.options.forwardKey, true);
        ownsForward = true;
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
        return 0;
    }

    private boolean plan(BlockPos goal, long tick) {
        path.clear();
        pathIndex = 0;
        progressAt = tick;
        bestDistance = Double.POSITIVE_INFINITY;
        if (direct(goal)) { path.add(goal); return true; }
        BlockPos start = new BlockPos(MathHelper.floor(mc.player.getX()), walkY, MathHelper.floor(mc.player.getZ()));
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
            double currentY = surface(current);
            for (Direction dir : HORIZONTAL) {
                BlockPos next = current.offset(dir);
                if (parents.containsKey(next) || next.getX() < Math.min(start.getX(), goal.getX()) - 8
                    || next.getX() > Math.max(start.getX(), goal.getX()) + 8
                    || next.getZ() < Math.min(start.getZ(), goal.getZ()) - 8
                    || next.getZ() > Math.max(start.getZ(), goal.getZ()) + 8) continue;
                double nextY = surface(next);
                if (!Double.isFinite(nextY) || !Double.isFinite(currentY) || Math.abs(nextY - currentY) > 0.6) continue;
                parents.put(next, current);
                open.addLast(next);
            }
        }
        return false;
    }

    private double distanceSquared(BlockPos pos) {
        double dx = mc.player.getX() - pos.getX() - 0.5;
        double dz = mc.player.getZ() - pos.getZ() - 0.5;
        return dx * dx + dz * dz;
    }

    private boolean reached(BlockPos pos,boolean endpoint) {
        double floor=surface(pos);
        if (LeverWaterPolicy.allowsDrift(leverWaterTravel, mc.player.isTouchingWater()))
            return LeverWaterPolicy.reached(mc.player.getX()-pos.getX()-0.5,mc.player.getZ()-pos.getZ()-0.5,
                floor-mc.player.getY(),Double.isFinite(floor),endpoint);
        return MapArtNavigationPolicy.reached(mc.player.getX()-pos.getX()-0.5,mc.player.getZ()-pos.getZ()-0.5,
            floor-mc.player.getY(),mc.player.isOnGround(),Double.isFinite(floor),endpoint);
    }

    private void face(Vec3d point) {
        Vec3d delta = point.subtract(mc.player.getEyePos());
        mc.player.setYaw((float) (MathHelper.atan2(delta.z, delta.x) * 180 / Math.PI - 90));
        mc.player.setPitch((float) (-MathHelper.atan2(delta.y, Math.hypot(delta.x, delta.z)) * 180 / Math.PI));
    }

    public void release(long tick) {
        if (!ownsForward || mc.options == null) return;
        stoppedAt = tick;
        mc.options.forwardKey.setPressed(false);
        Input.setKeyState(mc.options.forwardKey, false);
        ownsForward = false;
    }

    private void resetPath(long tick) {
        release(tick);
        path.clear();
        pathIndex = 0;
        pathGoal = null;
        unstuckGoal = null;
        routeAttempts = 0;
        bestDistance = Double.POSITIVE_INFINITY;
    }

    public void resetTravel() {
        resetPath(currentTick);travelProxy=null;stand=null;rejectedStands.clear();
        aimedAt=approachAimedAt=-1;approachTarget=null;
    }

    public void reset() {
        resetPath(currentTick);
        leverWaterTravel = false;
        claimPendingHandler(currentTick);
        if(mc.player!=null) {
            ScreenHandler current=mc.player.currentScreenHandler;
            if(current==owned) {
                if(canCloseSafely(current))closeScreenPreservingMenu();
                else { recoveryOwned=current;recoveryPosition=target; }
            } else if(current!=recoveryOwned) { recoveryOwned=null;recoveryPosition=null; }
            if(current!=owned&&requestedAt>=0&&current!=mc.player.playerScreenHandler
                &&expectedType(current,requestedCrafting)&&target!=null) {
                recoveryCandidate=current;recoveryCandidatePosition=target;recoveryCandidateCrafting=requestedCrafting;
                recoveryCandidateFromSyncId=requestedFromSyncId;recoveryCandidateAt=currentTick;
            }
        }
        owned = null;
        inventorySyncId = -1;
        target = null;
        travelProxy = null;
        stand = null;
        rejectedStands.clear();
        openAttempts = 0;
        requestedAt = aimedAt = -1;
        requestedFromSyncId = -1;
        approachAimedAt = -1;
        approachTarget = null;
        failure = null;
    }

    private void closeScreenPreservingMenu() {
        GameMenuScreen menu=mc.currentScreen instanceof GameMenuScreen m?m:null;
        closingMenu=menu!=null;
        try { mc.player.closeHandledScreen(); }
        finally {
            closingMenu=false;
            if(menu!=null&&mc.currentScreen==null&&mc.world!=null)mc.setScreen(menu);
        }
    }

    /** A held cursor is never closed: vanilla may otherwise drop it from a full inventory. */
    public boolean closeRecoveryHandlerIfSafe() {
        if(recoveryOwned==null)return true;
        if(mc.player==null)return false;
        if(mc.player.currentScreenHandler!=recoveryOwned) { recoveryOwned=null;recoveryPosition=null;return true; }
        if(!canCloseSafely(recoveryOwned))return false;
        closeScreenPreservingMenu();recoveryOwned=null;recoveryPosition=null;return true;
    }

    public boolean recoveryMatches(BlockPos pos,boolean crafting) {
        return mc.player!=null&&mc.player.currentScreenHandler==recoveryOwned&&recoveryOwned!=null
            &&pos.equals(recoveryPosition)&&expectedType(recoveryOwned,crafting);
    }

    private boolean canCloseSafely(ScreenHandler handler) {
        if(!ContainerOwnershipPolicy.mayClose(owns(handler),handler.getCursorStack().isEmpty()))return false;
        if(!(handler instanceof CartographyTableScreenHandler))return true;
        List<ItemStack> inventory=new ArrayList<>();
        for(int slot=0;slot<36;slot++)inventory.add(mc.player.getInventory().getStack(slot).copy());
        for(int input=0;input<2;input++) {
            ItemStack stack=handler.getSlot(input).getStack();int remaining=stack.getCount();
            if(stack.isEmpty())continue;
            for(ItemStack existing:inventory)if(ItemStack.areItemsAndComponentsEqual(existing,stack)) {
                int moved=Math.min(remaining,Math.max(0,existing.getMaxCount()-existing.getCount()));
                existing.increment(moved);remaining-=moved;
            }
            for(int slot=0;slot<inventory.size()&&remaining>0;slot++)if(inventory.get(slot).isEmpty()) {
                int moved=Math.min(remaining,stack.getMaxCount());ItemStack parked=stack.copy();parked.setCount(moved);
                inventory.set(slot,parked);remaining-=moved;
            }
            if(remaining>0)return false;
        }
        return true;
    }

    public boolean closingMenu() { return closingMenu; }
    public boolean hidesScreen(Screen screen) {
        return (requestedAt>=0||mc.player!=null&&mc.player.currentScreenHandler==recoveryOwned) && (screen instanceof GenericContainerScreen
            || screen instanceof ShulkerBoxScreen || screen instanceof CartographyTableScreen);
    }
    public boolean owns(ScreenHandler handler) { return owned!=null&&owned==handler||recoveryOwned!=null&&recoveryOwned==handler; }
    public BlockPos ownedPosition(ScreenHandler handler) { return owned!=null&&owned==handler?target:recoveryOwned!=null&&recoveryOwned==handler?recoveryPosition:null; }
    public boolean busyForAutoEat() { return requestedAt>=0||owned!=null||recoveryOwned!=null||recoveryCandidate!=null; }
    public int walk(BlockPos goal, long tick) {
        currentTick = tick;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            release(tick); activity = "请关闭其他容器"; return 0;
        }
        BlockPos destination = goal;
        if (!mc.world.isChunkLoaded(goal.getX() >> 4, goal.getZ() >> 4)) {
            if (travelProxy == null || distanceSquared(travelProxy) < 0.09) {
                double dx = goal.getX()+0.5-mc.player.getX(), dz=goal.getZ()+0.5-mc.player.getZ();
                double d=Math.hypot(dx,dz);
                if(d<1) return -1;
                travelProxy = BlockPos.ofFloored(mc.player.getX()+dx/d*Math.min(8,d),
                    walkY,mc.player.getZ()+dz/d*Math.min(8,d));
            }
            destination = travelProxy;
        } else travelProxy = null;
        int result = navigate(destination,tick);
        if(result==1 && !destination.equals(goal)) return 0;
        return result;
    }
    public BlockHitResult approach(BlockPos pos, long tick) {
        return approach(pos, tick, false);
    }

    public BlockHitResult approachLever(BlockPos pos, long tick) {
        return approach(pos, tick, true);
    }

    private void setLeverWaterTravel(boolean enabled) {
        if (leverWaterTravel == enabled) return;
        resetPath(currentTick);
        stand = null;
        rejectedStands.clear();
        approachAimedAt = -1;
        leverWaterTravel = enabled;
    }

    private BlockHitResult approach(BlockPos pos, long tick, boolean waterAllowed) {
        currentTick = tick;
        setLeverWaterTravel(waterAllowed);
        if (failure != null) return null;
        if (!pos.equals(approachTarget)) {
            approachTarget = pos.toImmutable();
            approachAimedAt = -1;
        }
        if(mc.player.currentScreenHandler!=mc.player.playerScreenHandler) {
            release(tick);activity="请关闭其他容器后操作拉杆";return null;
        }
        BlockHitResult hit = findHit(pos,mc.player.getEyePos(),3.6);
        if(hit==null) {
            approachAimedAt = -1;
            if(!mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4)) {
                if(walk(new BlockPos(pos.getX(),walkY,pos.getZ()),tick)<0) failure="无法接近尚未加载的目标";
                return null;
            }
            if(stand==null)stand=findStand(pos);
            if(stand==null) {failure="找不到目标附近的安全站位："+pos.toShortString();release(tick);return null;}
            int result=navigate(stand,tick);
            if(result!=0){rejectedStands.add(stand);stand=null;resetPath(tick);}
            activity="前往 "+pos.toShortString();
            return null;
        }
        release(tick);
        var velocity=mc.player.getVelocity();
        // Water currents do not settle like dry-ground motion. Re-raycast from the
        // actual eye every tick; a valid in-range lever hit is sufficient in water.
        if(tick-stoppedAt<2||!LeverWaterPolicy.allowsDrift(leverWaterTravel,mc.player.isTouchingWater())
            &&velocity.x*velocity.x+velocity.z*velocity.z>0.000625)return null;
        face(hit.getPos());
        if (approachAimedAt < 0) { approachAimedAt = tick; return null; }
        if (tick <= approachAimedAt) return null;
        return hit;
    }
    private BlockHitResult findHit(BlockPos pos, Vec3d eye, double range) {
        if(!mc.world.isChunkLoaded(pos.getX()>>4,pos.getZ()>>4))return null;
        var shape=mc.world.getBlockState(pos).getOutlineShape(mc.world,pos);
        if(shape.isEmpty())return null;
        var box=shape.getBoundingBox();
        for(Direction face:Direction.values()){
            Vec3d point=new Vec3d(pos.getX()+(box.minX+box.maxX)/2+face.getOffsetX()*(box.maxX-box.minX)*0.499,
                pos.getY()+(box.minY+box.maxY)/2+face.getOffsetY()*(box.maxY-box.minY)*0.499,
                pos.getZ()+(box.minZ+box.maxZ)/2+face.getOffsetZ()*(box.maxZ-box.minZ)*0.499);
            if(eye.squaredDistanceTo(point)>range*range)continue;
            var hit=mc.world.raycast(new RaycastContext(eye,point,RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,mc.player));
            if(hit.getBlockPos().equals(pos)&&hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK)return hit;
        }
        return null;
    }
}
