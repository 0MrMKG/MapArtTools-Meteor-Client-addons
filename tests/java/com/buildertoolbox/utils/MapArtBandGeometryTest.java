package com.buildertoolbox.utils;

import java.util.HashSet;
import java.util.Set;

public final class MapArtBandGeometryTest {
    public static void main(String[] args) {
        equal(1, MapArtBandGeometry.centerZ(0, 127, 4, 0), "first four-row band");
        equal(5, MapArtBandGeometry.centerZ(0, 127, 4, 1), "next band stays four rows away");
        equal(-6, MapArtBandGeometry.centerZ(-7, 120, 4, 0), "negative projection coordinates");
        equal(4, MapArtBandGeometry.centerZ(0, 5, 4, 1), "two-row tail uses its own middle");
        equal(8, MapArtBandGeometry.centerZ(0, 8, 4, 2), "one-row tail");
        equal(5, MapArtBandGeometry.maximumWidth(3.6, 1.745), "safe maximum with forward step reserve");
        equal(3, MapArtBandGeometry.maximumWidth(2.5, 1.745), "short reach narrows bands");
        var route = MapArtBandGeometry.snakeSweeps(0, 127, 0, 12, 5, Set.of(0, 1, 2), 0, 0);
        equal(3, route.size(), "one straight sweep per band");
        Set<Integer> covered = new HashSet<>();
        for (int i = 0; i < route.size(); i++) {
            var sweep = route.get(i);
            equal(i, sweep.lane(), "ascending lanes");
            equal(i % 2 == 0 ? 0 : 127, sweep.firstX(), "alternate entry endpoint");
            equal(i % 2 == 0 ? 127 : 0, sweep.lastX(), "alternate exit endpoint");
            if (i > 0) equal(route.get(i - 1).lastX(), sweep.firstX(), "turn connects at same X");
            for (int z = i * 5; z <= Math.min(12, i * 5 + 4); z++) {
                if (!covered.add(z)) throw new AssertionError("row assigned twice");
                double dz = Math.abs(z - sweep.centerZ()) + 0.25;
                if (1 + dz * dz + 1.745 * 1.745 > 3.6 * 3.6) throw new AssertionError("unreachable edge row");
            }
        }
        equal(13, covered.size(), "all rows covered with no seam gaps, including short last band");
        var resumed = MapArtBandGeometry.snakeSweeps(0, 127, 0, 12, 5, Set.of(0, 2), 120, 12);
        equal(2, resumed.getFirst().lane(), "nearest corner after restart");
        equal(127, resumed.getFirst().firstX(), "no extra current-X entry");
        equal(0, resumed.getFirst().lastX(), "one pass toward opposite endpoint");
        equal(0, resumed.getLast().firstX(), "completed band is skipped without diagonal backtrack");
        equal(2, resumed.getLast().centerZ(), "completed cells do not shift center");
        System.out.println("MapArtBandGeometry: serpentine continuity, reach and coverage checks passed.");
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
