package com.buildertoolbox.fullauto;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One finite supply trip: frozen quotas and visits that outlive individual color selections. */
public final class SupplyTrip<I, P> {
    public record Source<I, P>(I item, P position, double distanceSquared) { }
    private Map<I, Integer> goals;
    private final Set<P> visited = new HashSet<>();

    public void freezeGoals(Map<I, Integer> desired) {
        if (goals == null) goals = Collections.unmodifiableMap(new LinkedHashMap<>(desired));
    }
    public boolean planned() { return goals != null; }
    public Map<I, Integer> goals() { return goals == null ? Map.of() : goals; }

    /** Only the free slots still needed by the other frozen quotas are reserved. */
    public int reservedForOthers(I selected, Map<I, MapArtSupplyPlanner.Stock> inventory) {
        int reserved = 0;
        for (var entry : goals().entrySet()) {
            var stock = inventory.get(entry.getKey());
            if (!entry.getKey().equals(selected) && stock != null)
                reserved += MapArtSupplyPlanner.extraSlotsNeeded(entry.getValue(), stock);
        }
        return reserved;
    }
    public int limit(I item, int demand) {
        return goals == null ? demand : Math.min(demand, goals.getOrDefault(item, 0));
    }
    /** Live inventory satisfies a fixed trip quota even if a selected source is still en route. */
    public int remaining(I item, int held, int demand) {
        return Math.max(0, limit(item, Math.max(0, demand)) - Math.max(0, held));
    }
    public boolean visited(P position) { return visited.contains(position); }
    public void visit(P position) { visited.add(position); }
    public void forget(P position) { visited.remove(position); }

    /** Callers supply only still-needed colors with capacity and available sources. */
    public Source<I, P> nearest(Collection<Source<I, P>> sources) {
        Source<I, P> best = null;
        for (Source<I, P> source : sources) {
            if (visited(source.position()) || !Double.isFinite(source.distanceSquared()) || source.distanceSquared()<0) continue;
            if (best == null || source.distanceSquared() < best.distanceSquared()) best=source;
        }
        return best;
    }
    public void clear() { goals=null;visited.clear(); }
}
