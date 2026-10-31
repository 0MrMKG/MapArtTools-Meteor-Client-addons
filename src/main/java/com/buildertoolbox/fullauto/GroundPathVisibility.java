package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks every grid cell touched by the player's swept footprint. */
public final class GroundPathVisibility {
    private static final double EPSILON = 1.0e-9;

    private GroundPathVisibility() { }

    @FunctionalInterface
    public interface Surface {
        /** Walkable floor height, or NaN for obstacles, holes and unloaded cells. */
        double height(int x, int z);
    }

    public static boolean canWalk(double fromX, double fromZ, double toX, double toZ,
                                  double halfWidth, double maxStep, int sampleLimit, Surface surface) {
        if (!Double.isFinite(fromX) || !Double.isFinite(fromZ) || !Double.isFinite(toX)
            || !Double.isFinite(toZ) || !Double.isFinite(halfWidth) || halfWidth < 0
            || sampleLimit < 1) return false;
        if (Math.abs(toX - fromX) + Math.abs(toZ - fromZ) > sampleLimit) return false;
        // The set of touched block cells changes only where one of the four
        // body edges crosses a grid line. Inspect each interval exactly; do
        // not enlarge the body by a sampling margin at a narrow canvas edge.
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(1.0);
        addCrossings(cuts, fromX, toX, halfWidth);
        addCrossings(cuts, fromZ, toZ, halfWidth);
        if (cuts.size() > sampleLimit * 2L + 2) return false;
        Collections.sort(cuts);
        Map<Cell, Double> heights = new HashMap<>();
        double previousMin = Double.NaN;
        double previousMax = Double.NaN;
        int inspected = 0;
        for (int index = 1; index < cuts.size(); index++) {
            if (cuts.get(index) - cuts.get(index - 1) <= EPSILON) continue;
            if (++inspected > sampleLimit) return false;
            double t = (cuts.get(index - 1) + cuts.get(index)) / 2;
            double x = fromX + (toX - fromX) * t;
            double z = fromZ + (toZ - fromZ) * t;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int cellX = (int) Math.floor(x - halfWidth + EPSILON); cellX <= (int) Math.floor(x + halfWidth - EPSILON); cellX++) {
                for (int cellZ = (int) Math.floor(z - halfWidth + EPSILON); cellZ <= (int) Math.floor(z + halfWidth - EPSILON); cellZ++) {
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

    private static void addCrossings(List<Double> cuts, double from, double to, double halfWidth) {
        double travel = to - from;
        if (Math.abs(travel) <= EPSILON) return;
        int first = (int) Math.floor(Math.min(from, to) - halfWidth);
        int last = (int) Math.ceil(Math.max(from, to) + halfWidth);
        for (int line = first; line <= last; line++) {
            double leading = (line - halfWidth - from) / travel;
            double trailing = (line + halfWidth - from) / travel;
            if (leading > 0 && leading < 1) cuts.add(leading);
            if (trailing > 0 && trailing < 1) cuts.add(trailing);
        }
    }

    private record Cell(int x, int z) { }
}
