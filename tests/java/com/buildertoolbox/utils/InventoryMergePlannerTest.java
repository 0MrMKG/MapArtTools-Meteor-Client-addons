package com.buildertoolbox.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class InventoryMergePlannerTest {
    public static void main(String[] args) {
        var simple = compact(List.of(stack(0, "red", 20, 64), stack(9, "red", 30, 64)));
        check(nonempty(simple) == 1, "two partial stacks free a slot");
        var overflow = compact(List.of(stack(0, "red", 40, 64), stack(9, "red", 40, 64), stack(10, "red", 40, 64)));
        check(nonempty(overflow) == 2, "return overflow then free the third slot");
        compact(List.of(stack(0, "snowball", 12, 16), stack(9, "snowball", 12, 16), stack(10, "snowball", 8, 16)));
        var components = List.of(stack(0, "red/custom-name-A", 20, 64), stack(9, "red/custom-name-B", 20, 64));
        check(InventoryMergePlanner.next(components, Objects::equals) == null, "different components stay separate");
        var tools = List.of(stack(0, "tool", 1, 1), stack(9, "tool", 1, 1));
        check(InventoryMergePlanner.next(tools, Objects::equals) == null, "unstackable items untouched");
        var full = List.of(stack(0, "red", 64, 64), stack(9, "red", 64, 64));
        check(InventoryMergePlanner.next(full, Objects::equals) == null, "full stacks do not shuffle");
        Random random = new Random(30);
        for (int run = 0; run < 500; run++) {
            List<InventoryMergePlanner.Stack<String>> inventory = new ArrayList<>();
            for (int slot = 0; slot < 36; slot++) inventory.add(stack(slot, "color-" + random.nextInt(6), random.nextInt(65), 64));
            compact(inventory);
        }
        System.out.println("InventoryMergePlanner: edge cases and 500 randomized inventories passed.");
    }

    private static List<InventoryMergePlanner.Stack<String>> compact(List<InventoryMergePlanner.Stack<String>> initial) {
        List<InventoryMergePlanner.Stack<String>> stacks = new ArrayList<>(initial);
        Map<String, Integer> totals = totals(stacks);
        int operations = 0;
        while (true) {
            var merge = InventoryMergePlanner.next(stacks, Objects::equals);
            if (merge == null) break;
            check(++operations <= 36, "bounded convergence");
            int before = nonempty(stacks);
            for (int index = 0; index < stacks.size(); index++) {
                var old = stacks.get(index);
                int count = old.slot() == merge.source() ? merge.sourceAfter()
                    : old.slot() == merge.destination() ? merge.destinationAfter() : old.count();
                check(count >= 0 && count <= old.limit(), "valid stack size");
                stacks.set(index, stack(old.slot(), old.item(), count, old.limit()));
            }
            check(totals.equals(totals(stacks)), "no items lost, duplicated or changed");
            check(nonempty(stacks) <= before, "never consumes an extra empty slot");
        }
        Map<String, Integer> used = new HashMap<>();
        Map<String, Integer> limits = new HashMap<>();
        for (var stack : stacks) {
            if (stack.count() > 0) used.merge(stack.item(), 1, Integer::sum);
            limits.put(stack.item(), stack.limit());
        }
        for (var entry : totals.entrySet()) {
            int limit = limits.get(entry.getKey());
            check(used.getOrDefault(entry.getKey(), 0) == (entry.getValue() + limit - 1) / limit, "minimum required slots");
        }
        return stacks;
    }

    private static InventoryMergePlanner.Stack<String> stack(int slot, String item, int count, int limit) {
        return new InventoryMergePlanner.Stack<>(slot, item, count, limit);
    }

    private static int nonempty(List<InventoryMergePlanner.Stack<String>> stacks) {
        return (int) stacks.stream().filter(stack -> stack.count() > 0).count();
    }

    private static Map<String, Integer> totals(List<InventoryMergePlanner.Stack<String>> stacks) {
        Map<String, Integer> result = new HashMap<>();
        for (var stack : stacks) result.merge(stack.item(), stack.count(), Integer::sum);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
