package com.buildertoolbox.utils;

import java.util.HashMap;
import java.util.Map;

/** Checks the player's swept footprint, not just a ray between cell centers. */
public final class GroundPathVisibility {
    private static final double SAMPLE_SPACING = 0.2;

    private GroundPathVisibility() { }

    @FunctionalInterface
    public interface Surface {
        /** Walkable floor height, or NaN for obstacles, holes and unloaded cells. */
        double height(int x, int z);
    }

    public static boolean canWalk(double fromX, double fromZ, double toX, double toZ,
                                  double halfWidth, double maxStep, int sampleLimit, Surface surface) {
        double distance = Math.hypot(toX - fromX, toZ - fromZ);
        int steps = Math.max(1, (int) Math.ceil(distance / SAMPLE_SPACING));
        if (steps > sampleLimit) return false;
        // Expanding each sample by half the sample spacing covers the body
        // between samples as well, including corners a center ray would miss.
        double radius = halfWidth + SAMPLE_SPACING / 2 + 0.02;
        Map<Cell, Double> heights = new HashMap<>();
        double previousMin = Double.NaN;
        double previousMax = Double.NaN;
        for (int index = 0; index <= steps; index++) {
            double t = index / (double) steps;
            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int cellX = (int) Math.floor(x - radius); cellX <= (int) Math.floor(x + radius); cellX++) {
                for (int cellZ = (int) Math.floor(z - radius); cellZ <= (int) Math.floor(z + radius); cellZ++) {
                    double height = heights.computeIfAbsent(new Cell(cellX, cellZ), cell -> surface.height(cell.x(), cell.z()));
                    if (!Double.isFinite(height)) return false;
                    min = Math.min(min, height);
                    max = Math.max(max, height);
                }
            }
            if (max - min > maxStep || (!Double.isNaN(previousMin)
                && (max - previousMin > maxStep || previousMax - min > maxStep))) return false;
            previousMin = min;
            previousMax = max;
        }
        return true;
    }

    private record Cell(int x, int z) { }
}
