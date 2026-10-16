package com.buildertoolbox.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Standalone regression scenarios without Minecraft or a test-framework dependency. */
public final class MapArtSupplyPlannerTest {
    public static void main(String[] args) {
        var sufficient = plan(List.of(repeat("purple", 20)), Map.of("purple", stock(40, 64)), 2);
        equal(20, sufficient.desired().get("purple"), "existing 40 covers final 20");
        equal(0, sufficient.extraSlots().getOrDefault("purple", 0), "no unnecessary restock slot");

        var nearFirst = plan(List.of(repeat("white", 200), repeat("purple", 1000)),
            Map.of("white", stock(40, 64), "purple", stock(0, 0)), 4);
        equal(200, nearFirst.desired().get("white"), "current band gets its full demand despite held >= 16");
        equal(3, nearFirst.extraSlots().get("white"), "three incoming stacks merge with existing 40");
        equal(64, nearFirst.desired().get("purple"), "later band gets only spare space");

        var capacityLimited = plan(List.of(repeat("white", 300), repeat("purple", 50)),
            Map.of("white", stock(0, 0), "purple", stock(0, 0)), 2);
        equal(128, capacityLimited.desired().get("white"), "load what the bag can hold");
        check(!capacityLimited.demand().containsKey("purple"), "do not allocate later band before current fits");

        var fiveColors = plan(List.of(join(repeat("a", 500), List.of("b", "c", "d", "e"))),
            Map.of("a", stock(0, 0), "b", stock(0, 0), "c", stock(0, 0),
                "d", stock(0, 0), "e", stock(0, 0)), 5);
        for (String item : List.of("a", "b", "c", "d", "e")) {
            equal(1, fiveColors.extraSlots().get(item), "reserve one slot for " + item);
        }

        var absentColorFirst = plan(List.of(join(repeat("a", 50), repeat("b", 20))),
            Map.of("a", stock(40, 64), "b", stock(0, 0)), 1);
        equal(1, absentColorFirst.extraSlots().get("b"), "missing color wins last empty slot");
        equal(0, absentColorFirst.extraSlots().getOrDefault("a", 0), "existing partial stack remains usable");

        var rounding = plan(List.of(repeat("a", 50)), Map.of("a", stock(40, 64)), 1);
        equal(1, rounding.extraSlots().get("a"), "full incoming 64 needs space even when deficit is 10");
        equal(50, rounding.desired().get("a"), "quota describes demand, not surplus after full-stack transfer");

        var exhausted = plan(List.of(join(repeat("a", 200), repeat("b", 10))),
            Map.of("a", new MapArtSupplyPlanner.Stock(0, 0, 64, true, true), "b", stock(0, 0)), 1);
        equal(1, exhausted.extraSlots().get("b"), "obtainable material is not crowded out by empty supply");

        var unavailable = plan(List.of(repeat("a", 100), repeat("b", 10)),
            Map.of("a", new MapArtSupplyPlanner.Stock(0, 0, 64, false, false), "b", stock(0, 0)), 1);
        equal(0, unavailable.desired().get("a"), "unregistered color cannot be supplied");
        equal(10, unavailable.desired().get("b"), "continue with obtainable later material");

        var full = plan(List.of(repeat("a", 200)), Map.of("a", stock(40, 64)), 0);
        equal(0, full.extraSlots().values().stream().mapToInt(Integer::intValue).sum(), "full bag allocates no slots");
        check(full.desired().get("a") <= 64, "full bag cannot promise 200 materials");

        for (var result : List.of(sufficient, nearFirst, capacityLimited, fiveColors,
            absentColorFirst, rounding, exhausted, unavailable, full)) {
            for (var entry : result.desired().entrySet()) {
                check(entry.getValue() <= result.demand().get(entry.getKey()), "quota never exceeds projection demand");
            }
        }
        System.out.println("MapArtSupplyPlanner: 9 scenarios passed.");
    }

    private static MapArtSupplyPlanner.Stock stock(int count, int capacity) {
        return new MapArtSupplyPlanner.Stock(count, capacity, 64, true, false);
    }

    private static MapArtSupplyPlanner.Plan<String> plan(List<List<String>> bands,
                                                        Map<String, MapArtSupplyPlanner.Stock> stock, int slots) {
        var plan = MapArtSupplyPlanner.plan(bands, stock, slots);
        check(plan.extraSlots().values().stream().mapToInt(Integer::intValue).sum() <= slots, "slot budget respected");
        return plan;
    }

    private static List<String> repeat(String item, int count) {
        return Collections.nCopies(count, item);
    }

    private static List<String> join(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static void equal(int expected, int actual, String message) {
        check(expected == actual, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
