package com.buildertoolbox.fullauto;

/** Counts commanded walking without meaningful displacement; placement waits do not erase it. */
public final class WalkingProgress {
    private long lastTick = -1;
    private double anchorX, anchorZ;
    private int stalledTicks;

    public void sample(long tick, double x, double z, boolean forwardWasHeld) {
        if (lastTick < 0 || tick <= lastTick) {
            anchorX=x;anchorZ=z;stalledTicks=0;
        } else if ((x-anchorX)*(x-anchorX)+(z-anchorZ)*(z-anchorZ) >= 0.15*0.15) {
            anchorX=x;anchorZ=z;stalledTicks=0;
        } else if (forwardWasHeld && tick == lastTick+1) stalledTicks++;
        lastTick=tick;
    }
    public boolean stalled(int ticks) { return stalledTicks >= Math.max(1,ticks); }
    public void reset() { lastTick=-1;stalledTicks=0; }
}
