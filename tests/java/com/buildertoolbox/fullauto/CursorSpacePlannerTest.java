package com.buildertoolbox.fullauto;

import java.util.LinkedHashMap;
import java.util.Map;

/** A cursor recovery material must be able to create a real inventory slot. */
public final class CursorSpacePlannerTest {
    public static void main(String[] args) {
        var red64 = Map.of("red", new CursorSpacePlanner.Stock(64, false));
        equal(null, CursorSpacePlanner.chooseMaterial(red64, Map.of("red", 3)), "three holes cannot empty a full stack");
        equal(null, CursorSpacePlanner.chooseMaterial(red64, Map.of("red", 63)), "63 holes cannot empty a full stack");
        equal("red", CursorSpacePlanner.chooseMaterial(red64, Map.of("red", 64)), "64 known holes can empty 64");
        equal("red", CursorSpacePlanner.chooseMaterial(red64, Map.of("red", 100)), "more than one stack of holes is eligible");
        equal("red", CursorSpacePlanner.chooseMaterial(Map.of("red", new CursorSpacePlanner.Stock(64, true)), Map.of("red", 1)),
            "one compatible placement creates merge capacity");
        equal(null, CursorSpacePlanner.chooseMaterial(Map.of("red", new CursorSpacePlanner.Stock(64, true)), Map.of()),
            "compatible still requires an actual missing position");
        Map<String, CursorSpacePlanner.Stock> colors = new LinkedHashMap<>();
        colors.put("red", new CursorSpacePlanner.Stock(64, false));
        colors.put("blue", new CursorSpacePlanner.Stock(12, false));
        colors.put("green", new CursorSpacePlanner.Stock(3, false));
        equal("green", CursorSpacePlanner.chooseMaterial(colors, Map.of("red", 64, "blue", 12, "green", 3)),
            "choose the smallest fully clearable stack");
        equal("blue", CursorSpacePlanner.chooseMaterial(colors, Map.of("red", 64, "blue", 12, "green", 2)),
            "a small stack with insufficient demand cannot create space");
        colors.put("yellow", new CursorSpacePlanner.Stock(64, true));
        equal("yellow", CursorSpacePlanner.chooseMaterial(colors, Map.of("red", 64, "blue", 12, "green", 3, "yellow", 1)),
            "a compatible merge is preferred to clearing an unrelated stack");
        equal(null, CursorSpacePlanner.chooseMaterial(Map.of(), Map.of("red", 64)), "no inventory");
        equal(null, CursorSpacePlanner.chooseMaterial(colors, Map.of()), "no demand");
        equal(null, CursorSpacePlanner.chooseMaterial(red64, Map.of("red", -1)), "negative demand cannot make room");
        equal(67, CursorSpacePlanner.attemptLimit(64), "64 placements plus three failures");
        equal(4, CursorSpacePlanner.attemptLimit(1), "single item stack");
        equal(102, CursorSpacePlanner.attemptLimit(99), "maximum supported Minecraft stack");
        reject(() -> CursorSpacePlanner.attemptLimit(0));
        reject(() -> CursorSpacePlanner.attemptLimit(100));
        reject(() -> new CursorSpacePlanner.Stock(0, false));
        reject(() -> new CursorSpacePlanner.Stock(100, true));
        System.out.println("CursorSpacePlanner: full-stack room creation, compatible merges, demand and bounded attempts checks passed.");
    }

    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid stack count rejection");
    }
    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
