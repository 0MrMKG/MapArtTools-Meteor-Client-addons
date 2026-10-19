package com.buildertoolbox.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Ground travel and an owned, confirmed container/workbench opening session. */
public final class WorkstationAccess {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private final MinecraftClient mc;
    private final List<BlockPos> path = new ArrayList<>();
    private final Set<BlockPos> rejectedStands = new HashSet<>();
    private BlockPos target;
    private BlockPos stand;
    private BlockPos pathGoal;
    private ScreenHandler owned;
    private volatile int inventorySyncId = -1;
    private long ownedAt;
    private int walkY;
    private int pathIndex;
    private int routeAttempts;
    private int openAttempts;
    private long requestedAt = -1;
    private long aimedAt = -1;
    private long progressAt;
    private long stoppedAt = -100;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private boolean ownsForward;
    private String failure;
    private String activity = "空闲";

    public WorkstationAccess(MinecraftClient mc) { this.mc = mc; }
    public String failure() { return failure; }
    public String activity() { return activity; }
    public void start(int floorY) { reset(); walkY = floorY; }
    public void inventoryReceived(int syncId) { inventorySyncId = syncId; }

    public ScreenHandler open(BlockPos pos, boolean crafting, long tick) {
        if (mc.player == null || mc.world == null || pos == null) return null;
        if (!pos.equals(target)) {
            reset();
            target = pos.toImmutable();
        }
        if (failure != null) return null;
        if (crafting ? !mc.world.getBlockState(pos).isOf(Blocks.CRAFTING_TABLE) : !StorageBlockUtils.isStorage(mc, pos)) {
            failure = "目标方块已变化或尚未加载：" + pos.toShortString();
            release(tick);
            return null;
        }
        ScreenHandler current = mc.player.currentScreenHandler;
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
        boolean expected = crafting ? current instanceof CraftingScreenHandler : StorageBlockUtils.storageSlots(current) > 0;
        if (requestedAt >= 0 && current != mc.player.playerScreenHandler && expected) {
            owned = current;
            ownedAt = tick;
            release(tick);
            activity = "等待服务器同步容器物品";
            return null;
        }
        if (mc.currentScreen != null || current != mc.player.playerScreenHandler) {
            release(tick);
            activity = "请关闭其他界面后继续";
            return null;
        }
        if (requestedAt >= 0 && tick - requestedAt < 30) {
            release(tick);
            activity = "等待服务器打开界面";
            return null;
        }
        var hit = StorageBlockUtils.findHit(mc, pos, mc.player.getEyePos(), 3.6);
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
        BlockUtils.interact(hit, Hand.MAIN_HAND, true);
        requestedAt = tick;
        aimedAt = -1;
        activity = crafting ? "打开工作台" : "打开储藏方块";
        return null;
    }

    private BlockPos findStand(BlockPos pos) {
        List<BlockPos> options = new ArrayList<>();
        for (int x = pos.getX() - 4; x <= pos.getX() + 4; x++) {
            for (int z = pos.getZ() - 4; z <= pos.getZ() + 4; z++) {
                BlockPos candidate = new BlockPos(x, walkY, z);
                double y = surface(candidate);
                if (!Double.isFinite(y) || rejectedStands.contains(candidate)) continue;
                if (StorageBlockUtils.findHit(mc, pos, new Vec3d(x + 0.5, y + 1.62, z + 0.5), 3.6) != null)
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
        if (below.isEmpty() || !mc.world.getFluidState(pos).isEmpty()) return Double.NaN;
        return feet.getBlock() instanceof CarpetBlock ? pos.getY() + 0.0625
            : pos.getY() - 1 + below.getMax(Direction.Axis.Y);
    }

    private boolean direct(BlockPos pos) {
        return GroundPathVisibility.canWalk(mc.player.getX(), mc.player.getZ(), pos.getX() + 0.5, pos.getZ() + 0.5,
            mc.player.getWidth() / 2.0, 0.6, 30000, (x, z) -> surface(new BlockPos(x, walkY, z)));
    }

    /** 0 moving, 1 arrived, -1 no route. */
    private int navigate(BlockPos goal, long tick) {
        if (distanceSquared(goal) < 0.04) { resetPath(tick); return 1; }
        if (!goal.equals(pathGoal)) { resetPath(tick); pathGoal = goal; }
        if (!path.isEmpty() && tick - progressAt > 80) { path.clear(); release(tick); }
        if (path.isEmpty()) {
            if (++routeAttempts > 3 || !plan(goal, tick)) return -1;
        }
        while (pathIndex < path.size() && distanceSquared(path.get(pathIndex)) < 0.04) {
            pathIndex++;
            bestDistance = Double.POSITIVE_INFINITY;
            progressAt = tick;
        }
        if (pathIndex >= path.size()) return 1;
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
        routeAttempts = 0;
        bestDistance = Double.POSITIVE_INFINITY;
    }

    public void reset() {
        resetPath(0);
        if (mc.player != null && mc.player.currentScreenHandler == owned && owned.getCursorStack().isEmpty())
            mc.player.closeHandledScreen();
        owned = null;
        inventorySyncId = -1;
        target = null;
        stand = null;
        rejectedStands.clear();
        openAttempts = 0;
        requestedAt = aimedAt = -1;
        failure = null;
    }
}
