package com.buildertoolbox.fullauto;

/** Endpoint arrival is more tolerant than intermediate grid-corner tracking. */
public final class MapArtNavigationPolicy {
    private MapArtNavigationPolicy() { }
    public static boolean reached(double dx, double dz, double dy, boolean grounded, boolean walkable, boolean endpoint) {
        double radius=endpoint?0.60:0.20;
        return grounded&&walkable&&Double.isFinite(dy)&&Math.abs(dy)<=0.18
            &&dx*dx+dz*dz<=radius*radius+1.0e-9;
    }

    /** Leave the bare walking corridor ahead open; fill it after vacating each cell. */
    public static boolean deferCarpetAhead(double playerX, double playerZ, double playerWidth,
                                           int targetX, int targetZ, int direction) {
        double halfWidth=playerWidth/2.0+0.05;
        boolean inCorridor=targetZ+1>playerZ-halfWidth && targetZ<playerZ+halfWidth;
        return inCorridor && direction*(targetX+0.5-playerX)>0;
    }
}
