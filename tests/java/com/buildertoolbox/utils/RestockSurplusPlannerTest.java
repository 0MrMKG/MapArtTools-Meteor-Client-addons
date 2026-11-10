package com.buildertoolbox.utils;

import java.util.*;

public final class RestockSurplusPlannerTest {
    public static void main(String[] args) {
        var active = new ArrayList<String>();
        active.addAll(Collections.nCopies(130, "blue"));
        active.addAll(Collections.nCopies(64, "red"));
        var bands = List.of(active, Collections.nCopies(300, "green"));
        var sizes = Map.of("blue", 64, "red", 64, "green", 64);
        var quota = RestockSurplusPlanner.keepTargets(bands, sizes, 4);
        check(quota.get("blue") == 130 && quota.get("red") == 64
            && quota.get("green") == 0, "active band owns the available slots before the return band");
        quota = RestockSurplusPlanner.keepTargets(bands, sizes, 6);
        check(quota.get("green") == 128, "return band uses only spare space");
        check(RestockSurplusPlanner.keepTargets(List.of(Collections.nCopies(16384,"blue")), sizes, 34).get("blue") == 2176,
            "Full Mode leaves two of the 36 slots for maps and glass");
        var inventory = List.of(stack(0,"blue",5),stack(1,"blue",64),stack(2,"blue",64),
            stack(3,"red",64),stack(4,"pink",64),stack(5,"food",16));
        var discarded = RestockSurplusPlanner.surplusSlots(inventory, Map.of("blue",70,"red",1), Set.of("blue","red","pink"));
        check(discarded.equals(List.of(0,4)), "whole surplus stacks go; largest useful stacks and personal items stay");
        check(RestockSurplusPlanner.surplusSlots(inventory, Map.of("blue",200,"red",200), Set.of("blue","red")).isEmpty(),
            "a shortage never discards needed partial stacks");
        var reversed = RestockSurplusPlanner.keepTargets(List.of(Collections.nCopies(200,"green"),active),sizes,4);
        check(reversed.get("green")==200 && reversed.getOrDefault("blue",0)==0, "actual reversed route determines active-band priority");

        Random random = new Random(812781);
        for(int trial=0;trial<1000;trial++) {
            List<RestockSurplusPlanner.Stack<String>> bag = new ArrayList<>();
            Map<String,Integer> before = new HashMap<>(), demand = new HashMap<>();
            Set<String> materials = Set.of("blue","red","green");
            for(String item:materials) demand.put(item,random.nextInt(600));
            for(int slot=0;slot<36;slot++) {
                String item=List.of("blue","red","green","food","map").get(random.nextInt(5));
                int count=1+random.nextInt(64);
                bag.add(stack(slot,item,count));before.merge(item,count,Integer::sum);
            }
            var original=List.copyOf(bag);
            var removed=RestockSurplusPlanner.surplusSlots(bag,demand,materials);
            Map<String,Integer> after=new HashMap<>();
            for(var stack:bag) {
                if(removed.contains(stack.slot())) check(materials.contains(stack.item()),"never drop food, tools or map supplies");
                else after.merge(stack.item(),stack.count(),Integer::sum);
            }
            for(String item:materials) check(after.getOrDefault(item,0)>=Math.min(demand.get(item),before.getOrDefault(item,0)),
                "retain quota or all available stock when short");
            check(bag.equals(original),"planning does not mutate inventory");
        }
        System.out.println("RestockSurplusPlanner: active/return priorities, reserved map slots, whole stacks, personal-item isolation and 1000 randomized conservation checks passed.");
    }

    private static RestockSurplusPlanner.Stack<String> stack(int slot,String item,int count) {
        return new RestockSurplusPlanner.Stack<>(slot,item,count);
    }
    private static void check(boolean value,String message) {
        if(!value) throw new AssertionError(message);
    }
}
