package com.buildertoolbox.fullauto;

/** A short sweep movement window, never a timer that forces walking past work. */
public final class SweepStride {
    public static final double LENGTH = 0.8;
    private static final long MAX_TICKS = 16;
    private double originX, originZ;
    private long startedAt;
    private boolean active;

    public void begin(double x, double z, long tick) {
        if (active) return;
        originX = x; originZ = z; startedAt = tick; active = true;
    }

    public double remaining(double x, double z, long tick) {
        if (!active || tick < startedAt || tick - startedAt >= MAX_TICKS) return 0;
        return Math.max(0, LENGTH - Math.hypot(x - originX, z - originZ));
    }

    /** Moving closer is harmless; moving an in-range cell beyond the margin is not. */
    public static boolean wouldLeaveReach(double currentSquared, double futureSquared, double range) {
        double safe = Math.max(0, range - 0.1);
        return currentSquared <= range * range && futureSquared > currentSquared && futureSquared > safe * safe;
    }

    public void reset() { active = false; }
}
