package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Band coordinates are anchored to the full projection, not its unfinished subset. */
public final class MapArtBandGeometry {
    private MapArtBandGeometry() { }

    public static int centerZ(int projectionMinZ, int projectionMaxZ, int width, int lane) {
        int start = projectionMinZ + lane * width;
        int end = Math.min(projectionMaxZ, start + width - 1);
        return start + (end - start) / 2;
    }

    public static int maximumWidth(double reach, double eyeHeight) {
        // Reserve a full forward step to fill the cell just vacated, plus
        // lateral tracking error. Mere sideways reach overestimates coverage.
        double lateral = Math.sqrt(Math.max(0, reach * reach - eyeHeight * eyeHeight - 1.0)) - 0.25;
        return 2 * Math.max(0, (int) Math.floor(lateral)) + 1;
    }

    public record Sweep(int lane, int firstX, int lastX, int centerZ) { }

    public static List<Sweep> snakeSweeps(int minX, int maxX, int minZ, int maxZ, int width,
                                         Set<Integer> activeLanes, int playerX, int playerZ) {
        return snakeSweeps(minX, maxX, minZ, maxZ, width, activeLanes, playerX, playerZ, false);
    }

    public static List<Sweep> snakeSweeps(int minX, int maxX, int minZ, int maxZ, int width,
                                         Set<Integer> activeLanes, int playerX, int playerZ, boolean reverseTraversal) {
        List<Integer> lanes = new ArrayList<>(activeLanes);
        Collections.sort(lanes);
        if (lanes.isEmpty()) return List.of();
        boolean reverse = false;
        boolean leftToRight = true;
        double best = Double.POSITIVE_INFINITY;
        // Enter at the nearest of the four sweep corners, then alternate
        // endpoints. Never insert a current-X entry followed by backtracking.
        for (boolean highFirst : new boolean[] {false, true}) {
            int lane = highFirst ? lanes.getLast() : lanes.getFirst();
            int z = centerZ(minZ, maxZ, width, lane);
            for (boolean fromLeft : new boolean[] {true, false}) {
                double dx = (fromLeft ? minX : maxX) - (double) playerX;
                double dz = z - (double) playerZ;
                double distance = dx * dx + dz * dz;
                if (distance < best) {
                    best = distance;
                    reverse = highFirst;
                    leftToRight = fromLeft;
                }
            }
        }
        if (reverse) Collections.reverse(lanes);
        List<Sweep> result = new ArrayList<>();
        for (int lane : lanes) {
            result.add(new Sweep(lane, leftToRight ? minX : maxX, leftToRight ? maxX : minX,
                centerZ(minZ, maxZ, width, lane)));
            leftToRight = !leftToRight;
        }
        // Reverse the complete route, including each band's X direction.
        // Just flipping lane order breaks the joins for an even band count.
        if (reverseTraversal) {
            Collections.reverse(result);
            result.replaceAll(sweep -> new Sweep(sweep.lane(), sweep.lastX(), sweep.firstX(), sweep.centerZ()));
        }
        return result;
    }
}
