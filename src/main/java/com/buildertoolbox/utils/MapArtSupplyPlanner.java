package com.buildertoolbox.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allocates real empty inventory slots in the order the map will be printed. */
public final class MapArtSupplyPlanner {
    private MapArtSupplyPlanner() { }

    public record Stock(int count, int capacity, int stackSize, boolean replenishable, boolean deferred) { }

    public record Plan<T>(Map<T, Integer> demand, Map<T, Integer> desired, Map<T, Integer> extraSlots) {
        public int reservedForOthers(T selected) {
            return extraSlots.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(selected))
                .mapToInt(Map.Entry::getValue).sum();
        }
    }

    public static <T> Plan<T> plan(List<List<T>> bands, Map<T, Stock> inventory, int emptySlots) {
        Map<T, Integer> demand = new LinkedHashMap<>();
        Map<T, Integer> slots = new LinkedHashMap<>();
        int free = Math.max(0, emptySlots);
        for (List<T> band : bands) {
            Map<T, Integer> bandDemand = new LinkedHashMap<>();
            for (T item : band) bandDemand.merge(item, 1, Integer::sum);

            // Reserve the first stack for each needed color before giving
            // additional stacks to a large patch of a single color.
            List<T> reservationOrder = new ArrayList<>(bandDemand.keySet());
            reservationOrder.sort((left, right) -> {
                Stock a = inventory.get(left);
                Stock b = inventory.get(right);
                int deferred = Boolean.compare(a.deferred(), b.deferred());
                return deferred != 0 ? deferred : Boolean.compare(a.count() > 0, b.count() > 0);
            });
            for (T item : reservationOrder) {
                Stock stock = inventory.get(item);
                int required = demand.getOrDefault(item, 0) + bandDemand.get(item);
                if (!stock.replenishable() || required <= stock.count()) continue;
                int firstStackSlots = extraSlotsNeeded(stock.count() + 1, stock);
                int allocated = slots.getOrDefault(item, 0);
                if (allocated < firstStackSlots && free > 0) {
                    slots.put(item, allocated + 1);
                    free--;
                }
            }
            for (T item : band) {
                int required = demand.merge(item, 1, Integer::sum);
                Stock stock = inventory.get(item);
                if (!stock.replenishable() || stock.deferred()) continue;
                int allocated = slots.getOrDefault(item, 0);
                int needed = extraSlotsNeeded(required, stock);
                int added = Math.min(free, Math.max(0, needed - allocated));
                slots.put(item, allocated + added);
                free -= added;
            }

            // Future bands can use spare space only after this band's
            // obtainable materials have enough room, including full stacks.
            boolean full = demand.entrySet().stream().anyMatch(entry -> {
                Stock stock = inventory.get(entry.getKey());
                return stock.replenishable() && !stock.deferred()
                    && extraSlotsNeeded(entry.getValue(), stock) > slots.getOrDefault(entry.getKey(), 0);
            });
            if (full) break;
        }
        Map<T, Integer> desired = new LinkedHashMap<>();
        for (Map.Entry<T, Integer> entry : demand.entrySet()) {
            Stock stock = inventory.get(entry.getKey());
            int capacity = stock.replenishable()
                ? stock.capacity() + slots.getOrDefault(entry.getKey(), 0) * stock.stackSize()
                : stock.count();
            desired.put(entry.getKey(), Math.min(entry.getValue(), capacity));
        }
        return new Plan<>(demand, desired, slots);
    }

    private static int extraSlotsNeeded(int required, Stock stock) {
        int deficit = Math.max(0, required - stock.count());
        int transfer = ((deficit + stock.stackSize() - 1) / stock.stackSize()) * stock.stackSize();
        int extraCapacity = Math.max(0, stock.count() + transfer - stock.capacity());
        return (extraCapacity + stock.stackSize() - 1) / stock.stackSize();
    }
}
