package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.List;

public final class MovingPlacementWindowTest {
    private record Cell(double x, double z) { }
    public static void main(String[] args) {
        check(MovingPlacementWindow.remainingTravel(-2,2,1.68,3.6)
            < MovingPlacementWindow.remainingTravel(-2,0,1.68,3.6), "outer rows expire before center rows");
        check(!MovingPlacementWindow.mustHold(2,0,1.68,3.6,0.6), "approaching work does not force a stop");
        check(MovingPlacementWindow.mustHold(-2.8,0,1.68,3.6,0.6), "trailing work gets braking room");
        for (int width : new int[]{3,4,5}) for (int direction : new int[]{-1,1})
            for (int latency : new int[]{1,3,8}) {
                int parallel=simulate(width,direction,latency,true);
                int serial=simulate(width,direction,latency,false);
                check(parallel<serial,"overlapped placement saves simulated ticks without dropping coverage");
            }
        System.out.println("MovingPlacementWindow: bidirectional coverage, edge priority and bounded in-flight latency simulations passed.");
    }
    private static int simulate(int width,int direction,int latency,boolean streaming) {
        List<Cell> remaining=new ArrayList<>();
        for(int x=0;x<64;x++)for(int z=0;z<width;z++)remaining.add(new Cell(x+0.5,z-(width-1)*0.5));
        List<Cell> pending=new ArrayList<>();
        List<Integer> due=new ArrayList<>();
        double player=direction==1?0.5:63.5;
        int movingClicks=0, confirmed=0, previous=remaining.size(), elapsed=0;
        for(int tick=0;tick<20000 && !remaining.isEmpty();tick++) {
            for(int i=due.size()-1;i>=0;i--) if(due.get(i)<=tick) {
                remaining.remove(pending.remove(i));due.remove(i);confirmed++;
            }
            elapsed=tick+1;
            boolean hold=false;
            if (!streaming) for (Cell c:remaining) {
                double dx=c.x-player;
                if(dx*dx+c.z*c.z+1.68*1.68<=3.6*3.6)hold=true;
            }
            for(Cell c:remaining) if(MovingPlacementWindow.mustHold(direction*(c.x-player),c.z,1.68,3.6,0.59))hold=true;
            double next=player+direction*0.215;
            boolean moving=!hold && next>=0.5 && next<=63.5;
            if(moving)player=next;
            if(pending.size()<3) {
                Cell best=null;double deadline=Double.POSITIVE_INFINITY;
                for(Cell c:remaining) {
                    if(pending.contains(c))continue;
                    double along=direction*(c.x-player);
                    if(along*along+c.z*c.z+1.68*1.68>3.6*3.6)continue;
                    double left=MovingPlacementWindow.remainingTravel(along,c.z,1.68,3.6);
                    if(left<deadline){deadline=left;best=c;}
                }
                if(best!=null){pending.add(best);due.add(tick+latency);if(moving)movingClicks++;}
            }
            check(pending.size()<=3,"pipeline stays bounded");
            check(remaining.size()<=previous,"attempting a cell does not add completion");
            previous=remaining.size();
        }
        check(remaining.isEmpty() && confirmed==64*width,"no cell is lost while walking in direction "+direction);
        if(streaming) check(movingClicks>0,"ordinary movement and placement overlap");
        return elapsed;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
