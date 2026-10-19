package com.buildertoolbox.utils;

import java.util.List;
import java.util.function.BiPredicate;

/** Plans one merge; the adapter must compare full item components, not just IDs. */
public final class InventoryMergePlanner {
    private InventoryMergePlanner() { }

    public record Stack<T>(int slot, T item, int count, int limit) { }
    public record Merge(int source, int destination, int moved, int sourceAfter, int destinationAfter) { }

    public static <T> Merge next(List<Stack<T>> stacks, BiPredicate<T, T> sameItem) {
        Merge fallback = null;
        for (Stack<T> destination : stacks) {
            if (destination.count() <= 0 || destination.count() >= destination.limit()) continue;
            for (int index = stacks.size() - 1; index >= 0; index--) {
                Stack<T> source = stacks.get(index);
                if (source.slot() == destination.slot() || source.count() <= 0
                    || source.count() >= source.limit() || !sameItem.test(source.item(), destination.item())) continue;
                int moved = Math.min(source.count(), destination.limit() - destination.count());
                Merge merge = new Merge(source.slot(), destination.slot(), moved,
                    source.count() - moved, destination.count() + moved);
                if (merge.sourceAfter() == 0) return merge;
                if (fallback == null) fallback = merge;
            }
        }
        return fallback;
    }
}
