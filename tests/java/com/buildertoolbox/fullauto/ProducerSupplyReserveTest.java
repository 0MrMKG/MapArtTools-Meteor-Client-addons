package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class ProducerSupplyReserveTest {
    public static void main(String[] args) {
        for (int count : List.of(0,1,32,63,64)) {
            var take = choose(List.of(count),64);
            check(take.slot()<0 && take.reserveLimited(),"a seed-only or short box must not be drained");
        }
        var two=choose(List.of(64,64),64);
        check(two.slot()==0 && !two.reserveLimited(),"two stacks permit exactly one full-stack take");
        check(choose(List.of(0,64),64).reserveLimited(),"after that take the surviving stack is protected");
        var partial=choose(List.of(64,32),64);
        check(partial.slot()==1,"take the whole small stack rather than consume the full seed stack");
        var room=choose(List.of(64,32),16);
        check(room.slot()<0 && !room.reserveLimited() && room.minimumTake()==32,
            "lack of inventory space is distinct from lack of producer surplus");
        check(choose(List.of(50,50),64).reserveLimited(),"no splitting of 50+50 into a destructive take");
        check(choose(List.of(64,64),0).minimumTake()==64,"a full bag does not mark a stocked producer short");

        Random random=new Random(29102026);
        for (int run=0;run<2000;run++) {
            List<Integer> counts=new ArrayList<>();
            int size=1+random.nextInt(54);
            for(int slot=0;slot<size;slot++)counts.add(random.nextInt(65));
            int original=counts.stream().mapToInt(Integer::intValue).sum(), moved=0;
            while(true) {
                int capacity=1+random.nextInt(128);
                var take=choose(counts,capacity);
                if(take.slot()<0) {
                    take=choose(counts,Integer.MAX_VALUE);
                    if(take.slot()<0)break;
                    capacity=Integer.MAX_VALUE;
                }
                int amount=counts.get(take.slot());
                check(amount>0 && amount<=capacity,"a take fits inventory without splitting");
                check(take.total()-amount>=64,"every individual take preserves the seed quantity");
                moved+=amount;counts.set(take.slot(),0);
            }
            int left=counts.stream().mapToInt(Integer::intValue).sum();
            check(left+moved==original,"all items conserved");
            check(left>=Math.min(64,original),"no sequence of repeated takes can drain the producer");
        }

        var cooldown=new ProducerSupplyReserve<String>();
        cooldown.defer("near",100);
        check(cooldown.cooling("near",2499),"keep the source closed for the full two minutes");
        check(!cooldown.cooling("far",101),"another box of the same material remains usable");
        cooldown.defer("far",200);
        check(cooldown.nextRetry(201)==2500,"retry the earliest producer first");
        check(!cooldown.cooling("near",2500) && cooldown.cooling("far",2500),"one timer never waits for all other boxes");
        check(cooldown.nextRetry(2500)==2600,"next outstanding cooldown is independent");
        check(!cooldown.pending(2600),"expired cooldowns automatically clear");
        cooldown.defer("near",3000);cooldown.clear();
        check(!cooldown.pending(0),"a fresh runtime cannot inherit a stale tick counter");
        System.out.println("ProducerSupplyReserve: seed boundaries, fragmented stacks, 2000 repeated-withdrawal sequences and independent two-minute cooldowns passed.");
    }
    private static ProducerSupplyReserve.Take choose(List<Integer> counts,int capacity) {
        List<ProducerSupplyReserve.Stack> stacks=new ArrayList<>();
        for(int slot=0;slot<counts.size();slot++)stacks.add(new ProducerSupplyReserve.Stack(slot,counts.get(slot)));
        return ProducerSupplyReserve.choose(stacks,64,capacity);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
