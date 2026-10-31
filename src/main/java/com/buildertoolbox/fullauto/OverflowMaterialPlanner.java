package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Select whole surplus stacks, retaining enough of every item needed by the upcoming work. */
public final class OverflowMaterialPlanner {
    private OverflowMaterialPlanner() { }
    public record Stack<I>(int slot, I item, int count) { }

    public static <I> List<Integer> surplusSlots(List<Stack<I>> inventory, Map<I, Integer> demand, Set<I> materials) {
        Map<I, List<Stack<I>>> groups = new LinkedHashMap<>();
        for (Stack<I> stack : inventory) {
            if (stack.count() > 0 && materials.contains(stack.item()))
                groups.computeIfAbsent(stack.item(), ignored -> new ArrayList<>()).add(stack);
        }
        List<Stack<I>> surplus = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var stacks = entry.getValue();
            stacks.sort(Comparator.comparingInt((Stack<I> stack) -> stack.count()).reversed().thenComparingInt(Stack::slot));
            long required = Math.max(0, demand.getOrDefault(entry.getKey(), 0));
            long retained = 0;
            for (Stack<I> stack : stacks) {
                if (retained < required) retained += stack.count();
                else surplus.add(stack);
            }
        }
        // Small stacks free a slot cheaply when a destination has limited room.
        surplus.sort(Comparator.comparingInt((Stack<I> stack) -> stack.count()).thenComparingInt(Stack::slot));
        return surplus.stream().map(Stack::slot).toList();
    }

    public static boolean cleanupDue(int completedTrips, int interval, boolean blockedRestock, boolean openingCleanup) {
        return openingCleanup || blockedRestock || completedTrips >= Math.max(1, interval);
    }
}
