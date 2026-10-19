package com.buildertoolbox.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The route's first band, not coordinate/insertion order, must drive loading. */
public final class MapArtSnakeSupplyTest {
    public static void main(String[] args) {
        var route = MapArtBandGeometry.snakeSweeps(0, 127, 0, 9, 5, Set.of(0, 1), 127, 9);
        List<List<String>> bands = new ArrayList<>();
        for (var sweep : route) {
            List<String> materials = new ArrayList<>();
            int direction = sweep.lastX() >= sweep.firstX() ? 1 : -1;
            for (int x = sweep.firstX(); ; x += direction) {
                // Lane 1 is partially completed after a restart: only its
                // final 70 columns remain, with five carpet rows per column.
                if (sweep.lane() != 1 || x >= 58) {
                    for (int row = 0; row < 5; row++) materials.add(sweep.lane() == 1 ? "purple" : "white");
                }
                if (x == sweep.lastX()) break;
            }
            bands.add(materials);
        }
        var plan = MapArtSupplyPlanner.plan(bands, Map.of(
            "purple", new MapArtSupplyPlanner.Stock(64, 64, 64, true, false),
            "white", new MapArtSupplyPlanner.Stock(0, 0, 64, true, false)), 8);
        check(route.getFirst().lane() == 1, "start with nearest high-Z band");
        check(bands.getFirst().size() == 350, "exclude completed cells, count full five-row width");
        check(plan.desired().get("purple") == 350, "satisfy upcoming band's demand");
        check(plan.desired().get("purple") - 64 == 286, "subtract backpack inventory");
        check(plan.extraSlots().get("purple") == 5, "reserve enough whole stacks for current band");
        check(plan.desired().get("white") == 192, "later color only gets three spare slots");

        // Two workers share a start/reference side but select opposite
        // directions. With only one free slot they must load opposite colors.
        for (boolean reverse : new boolean[] {false, true}) {
            var workerRoute = MapArtBandGeometry.snakeSweeps(0, 31, 0, 1, 1, Set.of(0, 1), 0, 0, reverse);
            var workerBands = workerRoute.stream().map(sweep -> java.util.Collections.nCopies(32,
                sweep.lane() == 0 ? "white" : "purple")).toList();
            var workerPlan = MapArtSupplyPlanner.plan(workerBands, Map.of(
                "purple", new MapArtSupplyPlanner.Stock(0, 0, 64, true, false),
                "white", new MapArtSupplyPlanner.Stock(0, 0, 64, true, false)), 1);
            String first = reverse ? "purple" : "white";
            String later = reverse ? "white" : "purple";
            check(workerPlan.desired().get(first) == 32, "load the selected direction's first band");
            check(workerPlan.extraSlots().get(first) == 1, "reserve its only available slot");
            check(workerPlan.desired().getOrDefault(later, 0) == 0, "later band's color must wait");
        }
        System.out.println("MapArtSnakeSupply: route/inventory alignment and reverse restocking checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
