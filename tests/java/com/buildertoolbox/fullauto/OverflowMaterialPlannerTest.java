package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Whole-stack retention, trip intervals and inventory conservation. */
public final class OverflowMaterialPlannerTest {
    public static void main(String[] args) {
        var stacks = List.of(stack(0, "blue", 64), stack(1, "blue", 64), stack(2, "blue", 5),
            stack(3, "red", 64), stack(4, "map", 1), stack(5, "food", 32));
        var surplus = OverflowMaterialPlanner.surplusSlots(stacks, Map.of("blue", 70), Set.of("blue", "red"));
        check(surplus.equals(List.of(2, 3)), "keep 128 blue for a demand of 70, store only whole surplus stacks, ignore maps/food");
        check(OverflowMaterialPlanner.surplusSlots(stacks, Map.of("blue", 200, "red", 100), Set.of("blue", "red")).isEmpty(),
            "a shortage never turns needed stock into surplus");
        check(OverflowMaterialPlanner.surplusSlots(List.of(stack(0,"blue",40), stack(1,"blue",40), stack(2,"blue",5)),
            Map.of("blue",39),Set.of("blue")).equals(List.of(2,1)),
            "retain a sufficient large stack instead of preserving multiple fragments");
        check(OverflowMaterialPlanner.surplusSlots(stacks, Map.of(), Set.of("red")).equals(List.of(3)),
            "a color unused by the upcoming work is surplus, but unrelated items stay untouched");

        check(!OverflowMaterialPlanner.cleanupDue(0,3,false,false), "normal work does not clean again just after opening cleanup");
        check(!OverflowMaterialPlanner.cleanupDue(2,3,false,false), "two completed journeys do not trigger a three-trip interval");
        check(OverflowMaterialPlanner.cleanupDue(3,3,false,false), "after trip three, cleanup precedes trip four");
        check(OverflowMaterialPlanner.cleanupDue(4,3,false,false), "a reduced setting or restored overdue counter still triggers");
        check(OverflowMaterialPlanner.cleanupDue(0,100,true,false), "blocked capacity may trigger early cleanup");
        check(OverflowMaterialPlanner.cleanupDue(0,100,false,true), "each picture starts with cleanup independent of trip count");
        check(OverflowMaterialPlanner.cleanupDue(1,1,false,false), "interval one cleans before every following trip");
        check(!OverflowMaterialPlanner.cleanupDue(0,0,false,false), "a malformed zero interval never creates an immediate loop");

        Random random = new Random(982631);
        for (int run = 0; run < 500; run++) {
            List<OverflowMaterialPlanner.Stack<String>> inventory = new ArrayList<>();
            Map<String,Integer> counts = new HashMap<>(), demand = new HashMap<>();
            Set<String> materials = new HashSet<>();
            for (int color=0;color<16;color++) { materials.add("c"+color); demand.put("c"+color,random.nextInt(300)); }
            for (int slot=0;slot<36;slot++) {
                String item=random.nextInt(5)==0?"tool":"c"+random.nextInt(16);
                int count=1+random.nextInt(64);
                inventory.add(stack(slot,item,count));counts.merge(item,count,Integer::sum);
            }
            List<OverflowMaterialPlanner.Stack<String>> before=List.copyOf(inventory);
            List<Integer> removed=OverflowMaterialPlanner.surplusSlots(inventory,demand,materials);
            check(new HashSet<>(removed).size()==removed.size(),"a source slot is never selected twice");
            Map<String,Integer> retained=new HashMap<>();
            for (var stack:inventory) {
                if (removed.contains(stack.slot())) check(materials.contains(stack.item()),"only declared materials may be stored");
                else retained.merge(stack.item(),stack.count(),Integer::sum);
            }
            for (String item:counts.keySet())
                check(retained.getOrDefault(item,0)>=Math.min(counts.get(item),demand.getOrDefault(item,0)),
                    "all available needed material remains in inventory");
            check(retained.getOrDefault("tool",0).equals(counts.getOrDefault("tool",0)),"personal tools are retained exactly");
            check(inventory.equals(before),"planning never rearranges or mutates inventory");
        }
        System.out.println("OverflowMaterialPlanner: whole-stack keep/dump rules, trip intervals, urgent room and 500 randomized inventory conservation checks passed.");
    }

    private static OverflowMaterialPlanner.Stack<String> stack(int slot,String item,int count) {
        return new OverflowMaterialPlanner.Stack<>(slot,item,count);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
