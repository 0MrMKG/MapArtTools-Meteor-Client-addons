package com.buildertoolbox.fullauto;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Shared storage selection, slot layout and real block-face interaction checks. */
public final class StorageBlockUtils {
    private StorageBlockUtils() { }

    public static boolean isStorage(MinecraftClient mc, BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        var block = mc.world.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    public static int storageSlots(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler chest) return chest.getRows() * 9;
        return handler instanceof ShulkerBoxScreenHandler ? 27 : 0;
    }

    public static BlockHitResult findHit(MinecraftClient mc, BlockPos pos, Vec3d eye, double range) {
        if (mc.world == null || mc.player == null) return null;
        for (Direction face : Direction.values()) {
            Vec3d point = Vec3d.ofCenter(pos).add(face.getOffsetX() * 0.49,
                face.getOffsetY() * 0.49, face.getOffsetZ() * 0.49);
            if (eye.squaredDistanceTo(point) > range * range) continue;
            HitResult result = mc.world.raycast(new RaycastContext(eye, point,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (result instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    public static String position(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static BlockPos parsePosition(String text) {
        try {
            String[] parts = text.trim().split(",");
            if (parts.length != 3) return null;
            return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
