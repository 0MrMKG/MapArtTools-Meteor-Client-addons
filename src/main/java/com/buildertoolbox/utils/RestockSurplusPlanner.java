package com.buildertoolbox.utils;

import java.util.*;

/** Slot quotas for the active band before the return band, independent of current clutter. */
public final class RestockSurplusPlanner {
    private RestockSurplusPlanner() { }
    public record Stack<I>(int slot, I item, int count) { }

    public static <I> Map<I,Integer> keepTargets(List<List<I>> bands, Map<I,Integer> stackSizes, int slots) {
        Map<I,Integer> allocated = new LinkedHashMap<>(), demand = new LinkedHashMap<>();
        int free = Math.max(0, slots);
        for (int bandIndex=0; bandIndex<Math.min(2,bands.size()); bandIndex++) {
            List<I> band=bands.get(bandIndex);
            Map<I,Integer> counts=new LinkedHashMap<>();
            for (I item:band) counts.merge(item,1,Integer::sum);
            for (I item:counts.keySet()) if (!allocated.containsKey(item) && free>0) {
                allocated.put(item,1);free--;
            }
            for (I item:band) {
                int required=demand.merge(item,1,Integer::sum), size=Math.max(1,stackSizes.getOrDefault(item,64));
                int need=(required+size-1)/size, have=allocated.getOrDefault(item,0);
                int add=Math.min(free,Math.max(0,need-have));
                if(add>0){allocated.put(item,have+add);free-=add;}
            }
            if (demand.entrySet().stream().anyMatch(e -> e.getValue() > (long)allocated.getOrDefault(e.getKey(),0)*Math.max(1,stackSizes.getOrDefault(e.getKey(),64)))) break;
        }
        Map<I,Integer> result=new LinkedHashMap<>();
        demand.forEach((item,count)->result.put(item,Math.min(count,allocated.getOrDefault(item,0)*Math.max(1,stackSizes.getOrDefault(item,64)))));
        // Never discard the last held stack of an active-band color, even in
        // the impossible case where personal items leave fewer slots than colors.
        if (!bands.isEmpty()) {
            Map<I,Integer> activeCounts = new LinkedHashMap<>();
            for (I item : bands.getFirst()) activeCounts.merge(item, 1, Integer::sum);
            activeCounts.forEach((item,count) -> result.merge(item,
                Math.min(count, Math.max(1, stackSizes.getOrDefault(item,64))), Math::max));
        }
        return result;
    }

    public static <I> List<Integer> surplusSlots(List<Stack<I>> inventory, Map<I,Integer> keep, Set<I> eligible) {
        Map<I,List<Stack<I>>> groups=new LinkedHashMap<>();
        for(var stack:inventory)if(stack.count()>0&&eligible.contains(stack.item()))groups.computeIfAbsent(stack.item(),k->new ArrayList<>()).add(stack);
        List<Integer> result=new ArrayList<>();
        for(var entry:groups.entrySet()){
            entry.getValue().sort(Comparator.comparingInt((Stack<I> s)->s.count()).reversed().thenComparingInt(Stack::slot));
            int held=0,required=keep.getOrDefault(entry.getKey(),0);
            for(var stack:entry.getValue())if(held<required)held+=stack.count();else result.add(stack.slot());
        }
        return result;
    }
}
