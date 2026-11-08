package com.buildertoolbox.fullauto;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/** Reach-limited sweep travel with a bounded fallback for stalled movement. */
public final class SweepStride {
    // Allow a complete hand-range diameter between drained stops. The worker's
    // per-cell reach guard, not a two-block step, chooses the actual stop.
    public static final double LENGTH = 8.0;
    private static final long MAX_TICKS = 60;
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

    /** Skip intermediate centers only along an unbroken, walkable straight path. */
    public static int lookahead(double x, double z, int goalX, int goalZ, int start, int size,
                                IntUnaryOperator pathX, IntUnaryOperator pathZ, IntPredicate walkable) {
        if (start >= size || Math.abs(z - goalZ - 0.5) > 0.2) return start;
        double direction = Math.signum(goalX + 0.5 - x);
        int best = start;
        for (int i = start; i < Math.min(size, start + 8); i++) {
            int px = pathX.applyAsInt(i);
            if (pathZ.applyAsInt(i) != goalZ) break;
            if (i > start && (px - pathX.applyAsInt(i - 1)) * direction <= 0) break;
            double ahead = (px + 0.5 - x) * direction;
            if (ahead > 4 || ahead < -0.6) break;
            if (ahead <= 0) continue;
            if (!walkable.test(i)) break;
            best = i;
        }
        return best;
    }
}
