package com.buildertoolbox.fullauto;

/** Water tolerance only for operating the canvas-cleaning lever. */
public final class LeverWaterPolicy {
    private LeverWaterPolicy() { }

    public static boolean allowsFluid(boolean leverTravel, boolean empty, boolean water) {
        return empty || leverTravel && water;
    }

    public static boolean allowsDrift(boolean leverTravel, boolean touchingWater) {
        return leverTravel && touchingWater;
    }

    public static boolean reached(double dx, double dz, double dy, boolean supported, boolean endpoint) {
        double radius = endpoint ? 0.60 : 0.20;
        return supported && Double.isFinite(dy) && Math.abs(dy) <= 0.60
            && dx * dx + dz * dz <= radius * radius + 1e-9;
    }
}
