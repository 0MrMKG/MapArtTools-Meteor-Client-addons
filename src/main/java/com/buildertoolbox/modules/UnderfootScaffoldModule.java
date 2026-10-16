package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.List;

/** Independent underfoot bridging based on the Miku Scaffold behavior. */
public final class UnderfootScaffoldModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<ListMode> listMode = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("Block Filter").description("Blacklist or whitelist for bridge blocks.")
        .defaultValue(ListMode.BLACKLIST).build());
    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("Blocks").description("Bridge materials to filter.").build());
    private final Setting<Double> ahead = sgGeneral.add(new DoubleSetting.Builder()
        .name("Ahead Distance").description("Place just ahead of the feet while walking.")
        .defaultValue(0.5).range(0, 0.9).sliderRange(0, 0.9).build());
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("Place Range").description("Maximum distance to the support face.")
        .defaultValue(3.6).range(2, 4.5).sliderRange(2, 4.5).build());

    private BlockPos waitingFor;
    private BlockPos cooldownPos;
    private int waitingTicks;
    private int cooldownTicks;
    private boolean rotating;
    private int activationId;

    public UnderfootScaffoldModule() {
        super(BuilderToolboxAddon.CATEGORY, "Underfoot Scaffold",
            "Automatically places solid hotbar blocks under your feet as you walk.");
    }

    @Override public void onActivate() { reset(); }
    @Override public void onDeactivate() { reset(); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { disable(); }

    @EventHandler private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) { disable(); return; }
        if (mc.currentScreen != null || mc.interactionManager == null) return;
        if (cooldownTicks > 0) cooldownTicks--;
        if (waitingFor != null) {
            if (!mc.world.getBlockState(waitingFor).isReplaceable()) waitingFor = null;
            else if (++waitingTicks < 6) return;
            else {
                cooldownPos = waitingFor;
                cooldownTicks = 8;
                waitingFor = null;
            }
        }
        if (rotating || !mc.player.isOnGround()) return;

        int y = mc.player.getBlockPos().getY() - 1;
        Vec3d motion = mc.player.getVelocity();
        double speed = Math.hypot(motion.x, motion.z);
        if (speed > 0.025 && ahead.get() > 0) {
            BlockPos front = BlockPos.ofFloored(
                mc.player.getX() + motion.x / speed * ahead.get(), y,
                mc.player.getZ() + motion.z / speed * ahead.get());
            if (tryPlace(front)) return;
        }
        tryPlace(BlockPos.ofFloored(mc.player.getX(), y, mc.player.getZ()));
    }

    private boolean tryPlace(BlockPos target) {
        if (cooldownTicks > 0 && target.equals(cooldownPos)) return false;
        if (!mc.world.getBlockState(target).isReplaceable()) return false;
        int slot = findBlock(target);
        if (slot < 0) return false;
        BlockHitResult hit = findSupport(target);
        if (hit == null) return false;
        rotating = true;
        int scheduledActivation = activationId;
        Vec3d point = hit.getPos();
        Rotations.rotate(Rotations.getYaw(point), Rotations.getPitch(point), 40, () -> {
            if (scheduledActivation != activationId) return;
            rotating = false;
            if (!isActive() || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || !mc.world.getBlockState(target).isReplaceable()) return;
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() instanceof FallingBlock ||
                mc.player.getEyePos().squaredDistanceTo(point) > range.get() * range.get()) return;
            if (listMode.get() == ListMode.BLACKLIST && blocks.get().contains(item.getBlock())) return;
            if (listMode.get() == ListMode.WHITELIST && !blocks.get().contains(item.getBlock())) return;
            BlockState support = mc.world.getBlockState(hit.getBlockPos());
            if (support.isReplaceable() || BlockUtils.isClickable(support.getBlock())) return;
            boolean swapped = mc.player.getInventory().getSelectedSlot() != slot;
            if (swapped) InvUtils.swap(slot, true);
            try {
                var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                if (result.isAccepted()) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitingFor = target.toImmutable();
                    waitingTicks = 0;
                } else {
                    cooldownPos = target.toImmutable();
                    cooldownTicks = 8;
                }
            } finally {
                if (swapped) InvUtils.swapBack();
            }
        });
        return true;
    }

    private int findBlock(BlockPos target) {
        int selected = mc.player.getInventory().getSelectedSlot();
        for (int offset = 0; offset < 9; offset++) {
            int slot = (selected + offset) % 9;
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!(stack.getItem() instanceof BlockItem item)) continue;
            Block block = item.getBlock();
            if (block instanceof FallingBlock) continue;
            if (listMode.get() == ListMode.BLACKLIST && blocks.get().contains(block)) continue;
            if (listMode.get() == ListMode.WHITELIST && !blocks.get().contains(block)) continue;
            if (!block.getDefaultState().isFullCube(mc.world, target)) continue;
            return slot;
        }
        return -1;
    }

    private BlockHitResult findSupport(BlockPos target) {
        BlockHitResult best = null;
        double bestDistance = range.get() * range.get();
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue;
            BlockPos neighbor = target.offset(side);
            BlockState state = mc.world.getBlockState(neighbor);
            if (state.isReplaceable() || state.getCollisionShape(mc.world, neighbor).isEmpty()
                || BlockUtils.isClickable(state.getBlock())) continue;
            Direction face = side.getOpposite();
            Vec3d point = Vec3d.ofCenter(neighbor).add(
                face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            double distance = mc.player.getEyePos().squaredDistanceTo(point);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            best = new BlockHitResult(point, face, neighbor, false);
        }
        return best;
    }

    private void reset() {
        activationId++;
        waitingFor = cooldownPos = null;
        waitingTicks = cooldownTicks = 0;
        rotating = false;
    }

    private enum ListMode { BLACKLIST, WHITELIST }
}
