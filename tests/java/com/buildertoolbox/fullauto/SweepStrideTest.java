package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pure geometry/stride simulation plus worker wiring guards; not live-game physics. */
public final class SweepStrideTest {
    public static void main(String[] args) throws Exception {
        SweepStride stride = new SweepStride();
        check(stride.remaining(0,0,0)==0,"no batching before walking starts");
        stride.begin(0,0,10);
        stride.begin(0.3,0,11);
        check(Math.abs(stride.remaining(0.3,0,11)-7.7)<1e-9,"each movement tick must not restart the reach-limited stride");
        check(stride.remaining(-8,0,12)==0,"reverse movement has the same fallback distance bound");
        check(stride.remaining(2.1,0,27)>0,"neither two blocks nor sixteen ticks force an early stop");
        check(stride.remaining(0,0,70)==0,"blocked motion cannot defer placement indefinitely");
        stride.reset();check(stride.remaining(0,0,12)==0,"releasing movement discards the stride");
        check(SweepStride.wouldLeaveReach(12,13,3.6),"outer row about to leave reach stops movement");
        check(!SweepStride.wouldLeaveReach(12.9,12.8,3.6),"approaching a new row can continue");
        check(!SweepStride.wouldLeaveReach(16,17,3.6),"an already unreachable cell is not a new loss of reach");
        for (int direction : List.of(-1,1)) {
            for (double speed : new double[] {0.1, 0.22, 0.3}) {
                for (double eyeHeight : new double[] {1.62, 1.6825}) {
                    int baseline=simulate(2.0,direction,speed,eyeHeight), grouped=simulate(SweepStride.LENGTH,direction,speed,eyeHeight);
                    check(grouped<=baseline,"reach-limited travel must not add stop cycles on floor or carpet");
                    if (speed==0.1 && eyeHeight==1.62) {
                        check(grouped<baseline,"longer safe steps reduce stop cycles in both sweep directions");
                        System.out.println("SweepStride geometry direction " + direction + ": stops " + baseline + " -> " + grouped);
                    }
                }
            }
        }
        String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        String helper=section(source,"private boolean canContinueSweepStride(","private void tickSnakeTurn()");
        check(helper.contains("!dispatchedPlacements.isEmpty()") && helper.contains("printer.hasQueuedProjectionPlacement()")
            && helper.contains("!mc.player.isOnGround()") && helper.contains("!canWalkDirectlyTo(next)"),
            "batch movement remains grounded, path checked and gated by confirmed clicks");
        check(helper.contains("SweepStride.wouldLeaveReach") && helper.contains("!isSatisfied(target)"),
            "actual unfinished edge cells interrupt short steps");
        check(helper.contains("navigateTo(goal)") && helper.contains("sweepStride.begin("),
            "an idle band entry can begin the same safe forward stride");
        String worker=section(source,"private void tickSnakeWork()","private boolean canContinueSweepStride(");
        check(worker.contains("nearby == null) sweepCollecting = false")
            && worker.contains("sweepCollecting || !canContinueSweepStride(activeLane)")
            && worker.contains("sweepCollecting = true"),
            "a stopped worker drains reachable cells before priming another stride");
        check(section(source,"private void releaseMovement()","private static void setKeyPressed(").contains("sweepStride.reset()"),
            "stops, restocking and recovery discard short-step state");
        check(source.contains("horizontalSpeedSquared > 0.025 * 0.025") && source.contains("MAX_PENDING_PLACEMENTS = 3"),
            "throughput change does not relax click stability or expand the receipt pipeline");
        System.out.println("SweepStride: forward/reverse coverage, fewer stop cycles, reach margins, timeout and safety wiring passed.");
    }

    private record Cell(double x,double z) { }

    private static int simulate(double strideLength,int direction,double speed,double eyeHeight) {
        List<Cell> cells=new ArrayList<>();
        for(int x=0;x<32;x++) for(int z=-2;z<=2;z++) cells.add(new Cell(x+0.5,z+0.5));
        double x=direction>0?-3:35;
        SweepStride stride=new SweepStride();
        boolean moving=false, collecting=false;int stops=0;
        for(long tick=0;tick<2000&&!cells.isEmpty();tick++) {
            double px=x;
            List<Cell> reachable=cells.stream().filter(c->distance(px,c,eyeHeight)<=3.6*3.6
                // Keep the walking corridor clear until the player has passed.
                && (c.z!=0.5 || (c.x-px)*direction < -0.8)).toList();
            if(reachable.isEmpty()) collecting=false;
            if(!collecting) stride.begin(x,0.5,tick);
            double remaining=Math.max(0,stride.remaining(x,0.5,tick)-(SweepStride.LENGTH-strideLength));
            boolean advance=!collecting && remaining>0;
            if(advance) for(Cell cell:cells) {
                double projectedStep = Math.min(remaining, speed*2) + 0.3;
                if(SweepStride.wouldLeaveReach(distance(x,cell,eyeHeight),distance(x+direction*projectedStep,cell,eyeHeight),3.6)) {
                    advance=false;break;
                }
            }
            if(!reachable.isEmpty()&&!advance) {
                if(moving) stops++;
                moving=false;collecting=true;stride.reset();cells.remove(reachable.getFirst());
            } else {
                stride.begin(x,0.5,tick);moving=true;x+=direction*speed;
            }
        }
        check(cells.isEmpty(),"all five rows covered, including both boundary rows and trailing center cells");
        return stops;
    }
    private static double distance(double x,Cell c,double eyeHeight){return (x-c.x)*(x-c.x)+c.z*c.z-c.z+0.25+eyeHeight*eyeHeight;}
    private static String section(String code,String from,String to){int a=code.indexOf(from),b=code.indexOf(to,a+from.length());check(a>=0&&b>a,"source section");return code.substring(a,b);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
