package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Actual geometry order, inventory quotas and deferred-capacity recovery without Minecraft. */
public final class OpeningBandSupplyTest {
    public static void main(String[] args) {
        for (boolean reverse : List.of(false,true)) for (int startZ : List.of(0,127)) {
            var route=MapArtBandGeometry.snakeSweeps(0,127,0,127,5,Set.of(0,1,2,3,4,5),0,startZ,reverse);
            List<Integer> points=new ArrayList<>();
            for(var sweep:route){points.add(sweep.lane());points.add(sweep.lane());}
            var opening=new OpeningBandSupply();opening.start(points);
            check(opening.lanes().equals(List.of(route.get(0).lane(),route.get(1).lane())),"first distinct travel bands for either direction/start side");
            check(opening.preparationPending(),"startup requires preparation even with some held color");
            opening.prepared();
            opening.start(List.of(100,101));
            check(!opening.preparationPending()&&opening.lanes().equals(List.of(route.get(0).lane(),route.get(1).lane())),"later route rebuild does not apply startup treatment repeatedly");
        }
        var normal=new OpeningBandSupply();normal.start(List.of(0,0,1,1,2,2));
        var reverse=new OpeningBandSupply();reverse.start(List.of(25,25,24,24,23,23));
        check(normal.lanes().equals(List.of(0,1))&&reverse.lanes().equals(List.of(25,24)),"reverse must guard far-end bands, not numerical 0 and 1");
        check(!normal.shouldRevisit(5,true),"ordinary inventory changes cannot trigger opening detours");
        normal.prepared();normal.deferForCapacity(0);
        check(!normal.shouldRevisit(0,false)&&!normal.shouldRevisit(0,false),"full bag does not loop failed supply checks");
        check(normal.shouldRevisit(1,false),"consuming one stack prioritizes deferred opening shortages");
        check(normal.shouldRevisit(1,false),"a temporarily unavailable box cannot consume the newly free-slot event");
        normal.resolved();
        check(!normal.shouldRevisit(1,false),"confirmed retry does not repeat each tick");
        normal.deferForCapacity(0);
        check(normal.shouldRevisit(0,true),"matching material delivered without a new empty slot also enables repair");
        normal.reset();normal.start(List.of(9,9));
        check(normal.lanes().equals(List.of(9))&&normal.preparationPending()&&!normal.capacityDeferred(),"new picture and single remaining band reset cleanly");
        var empty=new OpeningBandSupply();empty.start(List.of());
        check(empty.lanes().isEmpty()&&!empty.preparationPending(),"already complete canvas needs no startup trip");

        var demand=List.of(Collections.nCopies(80,"white"),Collections.nCopies(40,"red"));
        var full=MapArtSupplyPlanner.planRoundTrip(demand,Map.of("white",stock(64,64),"red",stock(0,0)),0);
        check(full.desired().get("red")==0,"two reserved map slots cannot be counted as carpet capacity");
        var freed=MapArtSupplyPlanner.planRoundTrip(demand,Map.of("white",stock(64,64),"red",stock(0,0)),1);
        check(freed.desired().get("red")==40,"the newly free slot serves the absent opening color");
        check(freed.extraSlots().getOrDefault("white",0)==0,"existing opening color cannot crowd out missing return color");
        check(freed.desired().get("white")==64,"capacity-limited preloading preserves usable held stock");
        System.out.println("OpeningBandSupply: forward/reverse geometry, one-time preparation, full-bag deferral, free-slot recovery and return-color reservation passed.");
    }
    private static MapArtSupplyPlanner.Stock stock(int count,int capacity){return new MapArtSupplyPlanner.Stock(count,capacity,64,true,false);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
