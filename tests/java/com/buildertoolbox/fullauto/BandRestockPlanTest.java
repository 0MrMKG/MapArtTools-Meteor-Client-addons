package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Simulate complete whole-stack loading trips, including every color order and full bags. */
public final class BandRestockPlanTest {
    public static void main(String[] args) {
        var competing = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("main",500),repeat("later",10)),
            Map.of("main",stock(0),"later",stock(0)),8);
        equal(500,competing.desired().get("main"),"eight slots can finish the active 500-cell band");
        equal(0,competing.desired().getOrDefault("later",0),"a return color must not steal that eighth slot");
        var enough = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("main",500),repeat("later",10)),
            Map.of("main",stock(0),"later",stock(0)),9);
        equal(10,enough.desired().get("later"),"spare space still prepares the return band");
        load(enough,Map.of("main",0,"later",0),9,List.of("later","main"));
        load(enough,Map.of("main",0,"later",0),9,List.of("main","later"));

        for (boolean reverse : List.of(false,true)) {
            String active=reverse?"far":"near", later=reverse?"near":"far";
            var plan=MapArtSupplyPlanner.planRoundTrip(List.of(repeat(active,80),repeat(later,40)),
                Map.of(active,stock(64),later,stock(0)),1);
            equal(80,plan.desired().get(active),"one freed slot completes the actual first band in either direction");
            equal(0,plan.desired().getOrDefault(later,0),"geometric travel order, not color or lane number, determines priority");
            load(plan,Map.of(active,64,later,0),1,List.of(later,active));
        }

        var fragmented=MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",50)),Map.of("a",stock(40)),0);
        equal(40,fragmented.desired().get("a"),"24 free merge positions cannot promise an incoming stack of 64");
        var source=Map.of("a",stock(0),"b",stock(0));
        var exact=MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",64),repeat("b",64)),source,3);
        var buffered=MapArtSupplyPlanner.withBuffer(exact,source,3,Map.of("a",200,"b",200),32);
        equal(96,buffered.desired().get("a"),"up to 32 useful spare items after both bands fit");
        equal(64,buffered.desired().get("b"),"buffer cannot consume the other band's real slot");
        load(buffered,Map.of("a",0,"b",0),3,List.of("b","a"));
        var finalPicture=MapArtSupplyPlanner.withBuffer(exact,source,3,Map.of("a",64,"b",64),32);
        equal(exact.desired(),finalPicture.desired(),"final materials are capped by the actual remaining picture");
        var alreadyHeld=Map.of("a",stock(40),"b",stock(0));
        var partial=MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",20),repeat("b",64)),alreadyHeld,2);
        var extra=MapArtSupplyPlanner.withBuffer(partial,alreadyHeld,2,Map.of("a",100,"b",200),32);
        equal(20,extra.desired().get("a"),"already covered colors do not get a new visit just for buffer stock");
        var crowded=MapArtSupplyPlanner.withBuffer(competing,Map.of("main",stock(0),"later",stock(0)),8,
            Map.of("main",1000,"later",100),32);
        equal(500,crowded.desired().get("main"),"no buffer when the return band has no free slot");

        Random random=new Random(28102026);
        for (int run=0;run<300;run++) {
            int colors=1+random.nextInt(12), free=random.nextInt(25);
            Map<String,Integer> held=new LinkedHashMap<>(), total=new HashMap<>(), activeCounts=new HashMap<>();
            Map<String,MapArtSupplyPlanner.Stock> stocks=new HashMap<>();
            List<String> outward=new ArrayList<>(), back=new ArrayList<>();
            for (int i=0;i<colors;i++) {
                String item="c"+i;
                int count=random.nextInt(121), a=1+random.nextInt(120), b=random.nextInt(80);
                held.put(item,count);stocks.put(item,stock(count));activeCounts.put(item,a);
                outward.addAll(repeat(item,a));back.addAll(repeat(item,b));total.put(item,a+b+random.nextInt(65));
            }
            free=Math.min(free,Math.max(0,34-held.values().stream().mapToInt(count->(count+63)/64).sum()));
            var base=MapArtSupplyPlanner.planRoundTrip(List.of(outward,back),stocks,free);
            int needed=activeCounts.entrySet().stream().mapToInt(e->slotsFor(e.getValue(),stocks.get(e.getKey()))).sum();
            if (needed<=free) for(var entry:activeCounts.entrySet())
                equal(entry.getValue(),Math.min(entry.getValue(),base.desired().getOrDefault(entry.getKey(),0)),
                    "whenever the whole active band fits, future colors cannot leave it short");
            var plan=MapArtSupplyPlanner.withBuffer(base,stocks,free,total,32);
            for(var entry:plan.desired().entrySet())check(entry.getValue()<=total.get(entry.getKey()),"quota stays within the picture");
            for(int order=0;order<4;order++) {
                List<String> visits=new ArrayList<>(held.keySet());Collections.shuffle(visits,random);
                load(plan,held,free,visits);
            }
        }
        System.out.println("BandRestockPlan: active-band completion, reverse order, whole-stack capacity, bounded buffers and 1200 shuffled loading trips passed.");
    }

    private static void load(MapArtSupplyPlanner.Plan<String> plan,Map<String,Integer> initial,int empty,List<String> order) {
        SupplyTrip<String,String> trip=new SupplyTrip<>();trip.freezeGoals(plan.desired());
        Map<String,Integer> held=new LinkedHashMap<>(initial);
        for(String item:order) {
            int goal=trip.goals().getOrDefault(item,0);
            while(held.get(item)<goal) {
                Map<String,MapArtSupplyPlanner.Stock> stocks=new HashMap<>();held.forEach((key,count)->stocks.put(key,stock(count)));
                int reserved=trip.reservedForOthers(item,stocks), before=held.get(item);
                int room=stock(before).capacity()-before+Math.max(0,empty-reserved)*64;
                check(room>=64,"every planned take fits while reserving the remaining fixed quotas: "+item+" "+trip.goals());
                int after=before+64;
                empty-=(stock(after).capacity()-stock(before).capacity())/64;
                check(empty>=0,"no overbooking of inventory slots");held.put(item,after);
            }
            check(trip.remaining(item,held.get(item),Integer.MAX_VALUE)==0,"a stocked color has no second-source visit");
        }
        for(var entry:trip.goals().entrySet())check(held.get(entry.getKey())>=entry.getValue(),"all promised stock loaded in one journey");
    }
    private static int slotsFor(int required,MapArtSupplyPlanner.Stock stock) {
        int take=((Math.max(0,required-stock.count())+63)/64)*64;
        return (Math.max(0,take-(stock.capacity()-stock.count()))+63)/64;
    }
    private static MapArtSupplyPlanner.Stock stock(int count){return new MapArtSupplyPlanner.Stock(count,((count+63)/64)*64,64,true,false);}
    private static List<String> repeat(String item,int count){return Collections.nCopies(count,item);}
    private static void equal(Object expected,Object actual,String message){check(expected.equals(actual),message+": "+actual+" != "+expected);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
