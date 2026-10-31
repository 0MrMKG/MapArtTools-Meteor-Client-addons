package com.buildertoolbox.fullauto;

/** Detects a restock worker remaining in one place without a confirmed take. */
public final class StationaryRestockWatchdog {
    public static final long TIMEOUT_TICKS = 20L * 60L * 3L;
    private static final double MOVEMENT_SQUARED = 0.2 * 0.2;
    private long since;
    private double anchorX, anchorZ;
    private boolean tracking;

    public boolean stalled(long tick, double x, double z, boolean eligible) {
        if (!eligible) { reset(); return false; }
        double dx = x - anchorX, dz = z - anchorZ;
        if (!tracking || tick < since || dx * dx + dz * dz >= MOVEMENT_SQUARED) {
            tracking = true;
            since = tick;
            anchorX = x;
            anchorZ = z;
            return false;
        }
        if (tick - since < TIMEOUT_TICKS) return false;
        since = tick; // One bounded recovery per full stationary window.
        return true;
    }

    public void reset() { tracking = false; }
}
