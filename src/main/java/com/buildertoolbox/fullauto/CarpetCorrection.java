package com.buildertoolbox.fullauto;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.CarpetBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.HashMap;
import java.util.Map;

/** Manual carpet mining; never predicts server acceptance or holds the attack key. */
public final class CarpetCorrection {
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Map<BlockPos, Long> retryAfter = new HashMap<>();
    private BlockPos target;
    private long started;
    private int previousSlot = -1;
    private int miningSlot = -1;
    public BlockPos target() { return target; }
    public static boolean wrong(BlockState expected, BlockState actual) {
        return expected.getBlock() instanceof CarpetBlock && actual.getBlock() instanceof CarpetBlock
            && expected.getBlock() != actual.getBlock();
    }
    public boolean tick(BlockPos pos, BlockState expected, BlockState observed, double range, long tick) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { stop(); return false; }
        if (retryAfter.getOrDefault(pos, 0L) > tick) return false;
        var decision = CarpetCorrectionGate.decide(wrong(expected, observed),
            wrong(expected, mc.world.getBlockState(pos)), target != null, tick-started);
        if (decision == CarpetCorrectionGate.Action.STOP) { stop(); return false; }
        if (target != null && (!target.equals(pos) || decision == CarpetCorrectionGate.Action.TIMEOUT)) {
            retryAfter.put(target, tick + 100); stop(); return false;
        }
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) { stop(); return false; }
        // The local world may already predict air. Wait for the watched server state;
        // never let the ray fall through to the support block.
        if (decision == CarpetCorrectionGate.Action.WAIT) return true;
        Vec3d aim = new Vec3d(pos.getX()+0.5, pos.getY()+0.03125, pos.getZ()+0.5);
        Vec3d eye = mc.player.getEyePos();
        var hit = mc.world.raycast(new RaycastContext(eye, aim, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, mc.player));
        if (eye.squaredDistanceTo(aim) > range*range || hit.getType() != HitResult.Type.BLOCK
            || !hit.getBlockPos().equals(pos)) { stop(); return false; }
        Vec3d delta = aim.subtract(eye);
        mc.player.setYaw((float)(Math.toDegrees(Math.atan2(delta.z, delta.x))-90));
        mc.player.setPitch((float)-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
        if (target == null) {
            int slot = -1;
            for (int i=0;i<9;i++) if (mc.player.getInventory().getStack(i).isEmpty()) { slot=i; break; }
            // A held building block mines like a bare hand and consumes no tool durability.
            if (slot < 0) for (int i=0;i<9;i++)
                if (mc.player.getInventory().getStack(i).getItem() instanceof BlockItem) { slot=i; break; }
            if (slot < 0) { retryAfter.put(pos.toImmutable(), tick+100); return false; }
            previousSlot = mc.player.getInventory().getSelectedSlot();
            InvUtils.swap(slot, false);
            miningSlot = slot;
            target=pos.toImmutable(); started=tick;
            return true; // Send the new look/held slot before starting the attack.
        }
        // Do not accidentally use a tool if another action changed the held slot.
        if (!mc.player.getInventory().getStack(miningSlot).isEmpty()
            && !(mc.player.getInventory().getStack(miningSlot).getItem() instanceof BlockItem)) {
            stop(); return false;
        }
        InvUtils.swap(miningSlot, false);
        mc.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }
    public void stop() {
        if (target != null && mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        if (previousSlot >= 0 && mc.player != null) InvUtils.swap(previousSlot, false);
        target=null; previousSlot=-1; miningSlot=-1;
    }
    public void reset() { stop(); retryAfter.clear(); }
}
