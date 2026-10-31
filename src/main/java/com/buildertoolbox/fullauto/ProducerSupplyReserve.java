package com.buildertoolbox.fullauto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keep producer seed stock in its container and allow it two minutes to refill. */
public final class ProducerSupplyReserve<P> {
    public static final long RECHECK_TICKS = 20L * 120;
    public record Stack(int slot, int count) { }
    public record Take(int slot, int total, int minimumTake) {
        public boolean reserveLimited() { return minimumTake == 0; }
    }
    private final Map<P, Long> retryAt = new HashMap<>();

    /** Input contains only storage slots matching the registered material ID. */
    public static Take choose(List<Stack> matching, int reserve, int capacity) {
        int total = matching.stream().mapToInt(stack -> Math.max(0, stack.count())).sum();
        int surplus = Math.max(0, total - Math.max(1, reserve));
        int minimum = Integer.MAX_VALUE, selected = -1;
        for (Stack stack : matching) {
            if (stack.count() <= 0 || stack.count() > surplus) continue;
            minimum = Math.min(minimum, stack.count());
            if (selected < 0 && stack.count() <= capacity) selected = stack.slot();
        }
        return new Take(selected, total, minimum == Integer.MAX_VALUE ? 0 : minimum);
    }

    public void defer(P container, long tick) { retryAt.put(container, tick + RECHECK_TICKS); }
    public boolean cooling(P container, long tick) {
        Long until = retryAt.get(container);
        if (until == null) return false;
        if (tick < until) return true;
        retryAt.remove(container);
        return false;
    }
    public boolean pending(long tick) { return nextRetry(tick) != Long.MAX_VALUE; }
    public long nextRetry(long tick) {
        retryAt.entrySet().removeIf(entry -> tick >= entry.getValue());
        return retryAt.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
    }
    public void clear() { retryAt.clear(); }
}
