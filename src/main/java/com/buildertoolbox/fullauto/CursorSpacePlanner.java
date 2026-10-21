package com.buildertoolbox.fullauto;

import java.util.Map;
import java.util.Objects;

/** Chooses a hotbar stack that can actually make room for a held cursor stack. */
public final class CursorSpacePlanner {
    private CursorSpacePlanner() { }

    public record Stock(int smallestStackCount, boolean compatibleWithCursor) {
        public Stock {
            if (smallestStackCount < 1 || smallestStackCount > 99)
                throw new IllegalArgumentException("Stack count must be in 1..99");
        }
    }

    /**
     * A compatible stack needs one placement to accept cursor items. Otherwise its
     * complete smallest stack must fit into known, available missing positions.
     * Ties retain map iteration order so callers can supply a stable hotbar order.
     */
    public static <K> K chooseMaterial(Map<K, Stock> hotbar, Map<K, Integer> availableMissing) {
        Objects.requireNonNull(hotbar, "hotbar");
        Objects.requireNonNull(availableMissing, "availableMissing");
        K result = null;
        int bestPlacements = Integer.MAX_VALUE;
        boolean bestCompatible = false;
        for (var entry : hotbar.entrySet()) {
            Stock stock = Objects.requireNonNull(entry.getValue(), "stock");
            int placements = stock.compatibleWithCursor() ? 1 : stock.smallestStackCount();
            if (availableMissing.getOrDefault(entry.getKey(), 0) < placements) continue;
            if (result == null || stock.compatibleWithCursor() && !bestCompatible
                || stock.compatibleWithCursor() == bestCompatible && placements < bestPlacements) {
                result = entry.getKey();
                bestPlacements = placements;
                bestCompatible = stock.compatibleWithCursor();
            }
        }
        return result;
    }

    /** Never consume more than one stack plus three failed placement attempts. */
    public static int attemptLimit(int maxStack) {
        if (maxStack < 1 || maxStack > 99) throw new IllegalArgumentException("Maximum stack size must be in 1..99");
        return maxStack + 3;
    }
}
