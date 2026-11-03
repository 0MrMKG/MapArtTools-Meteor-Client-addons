package com.buildertoolbox.fullauto;

/** Geometry for a straight sweep; positive travel is expressed in the sweep direction. */
public final class MovingPlacementWindow {
    private MovingPlacementWindow() { }
    public static double remainingTravel(double along, double lateral, double height, double range) {
        double reach = range * range - lateral * lateral - height * height;
        return reach < 0 ? Double.NEGATIVE_INFINITY : along + Math.sqrt(reach);
    }
    public static boolean mustHold(double along, double lateral, double height, double range, double advance) {
        double squared = along*along + lateral*lateral + height*height;
        return squared <= range*range && remainingTravel(along,lateral,height,range) <= advance;
    }
}
