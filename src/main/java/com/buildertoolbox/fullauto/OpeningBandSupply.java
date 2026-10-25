package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.List;

/** The first two distinct bands in actual travel order, with a capacity-triggered retry. */
public final class OpeningBandSupply {
    private List<Integer> lanes = List.of();
    private boolean initialized;
    private boolean preparationPending;
    private boolean capacityDeferred;
    private int observedFreeSlots;

    public void start(List<Integer> route) {
        if (initialized) return;
        List<Integer> first = new ArrayList<>(2);
        for (int lane : route) {
            if (lane < 0 || first.contains(lane)) continue;
            first.add(lane);
            if (first.size() == 2) break;
        }
        lanes = List.copyOf(first);
        initialized = true;
        preparationPending = !lanes.isEmpty();
    }

    public List<Integer> lanes() { return lanes; }
    public boolean preparationPending() { return preparationPending; }
    public void prepared() { preparationPending = false; }
    public boolean contains(int lane) { return lanes.contains(lane); }

    public void deferForCapacity(int freeSlots) {
        capacityDeferred = true;
        observedFreeSlots = Math.max(0, freeSlots);
    }

    /** A stable full bag must not restart the same failed trip every tick. */
    public boolean shouldRevisit(int freeSlots, boolean newlyUsableMaterial) {
        if (!capacityDeferred) return false;
        int current = Math.max(0, freeSlots);
        // Keep the event pending if the first check cannot yet access a matching supply box.
        observedFreeSlots = Math.min(observedFreeSlots,current);
        return current > observedFreeSlots || newlyUsableMaterial;
    }

    public void resolved() { capacityDeferred = false; }
    public boolean capacityDeferred() { return capacityDeferred; }

    public void reset() {
        lanes = List.of();
        initialized = preparationPending = capacityDeferred = false;
        observedFreeSlots = 0;
    }
}
