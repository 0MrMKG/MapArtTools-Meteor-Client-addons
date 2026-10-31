package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exact player footprint at one-block canvas edges and route retry wiring. */
public final class GroundPathEdgeTest {
    public static void main(String[] args) throws Exception {
        GroundPathVisibility.Surface strip = (x,z) -> x == 0 ? 0 : Double.NaN;
        check(walk(0.36,0.5,0.36,20.5,strip),
            "an off-center player still fully inside a one-block edge strip has a valid route");
        check(walk(0.36,0.5,0.5,20.5,strip),
            "edge departure towards the center remains connected");
        check(!walk(0.29,0.5,0.5,20.5,strip),
            "the player body actually extending past the edge is rejected");
        GroundPathVisibility.Surface negative = (x,z) -> x == -1 ? 0 : Double.NaN;
        check(walk(-0.64,-10.5,-0.5,10.5,negative),
            "negative-coordinate edge uses the same exact footprint");
        check(!walk(0.5,0.5,1.5,1.5,(x,z)->x==1&&z==0?Double.NaN:0),
            "diagonal corner cutting remains blocked");
        check(!walk(0.5,0.5,2.5,2.5,(x,z)->x==1&&z==1?Double.NaN:0),
            "a missing floor cell still blocks the diagonal");
        check(walk(0.5,0.5,20.5,0.5,(x,z)->x<0?Double.NaN:(x%2==0?0:0.0625)),
            "small carpet heights do not make an edge corridor unreachable");
        check(!walk(0.5,0.5,20.5,0.5,(x,z)->x<0?Double.NaN:(x<10?0:1)),
            "full-height steps remain impassable");
        check(!GroundPathVisibility.canWalk(0.5,0.5,100.5,100.5,0.3,0.6,10,(x,z)->0),
            "route checks remain bounded by the search limit");

        String worker=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        int first=worker.indexOf("if (!planPath(goal)) {");
        int clear=worker.indexOf("avoidedRouteCells.clear();",first);
        int retry=worker.indexOf("if (!planPath(goal))",clear);
        check(first>=0&&clear>first&&retry>clear,
            "a failed edge route retries after dropping temporary avoided cells");
        String snake=section(worker,"private void tickSnakeWork()", "private void tickSnakeTurn()");
        check(snake.contains("navigateTo(sweepEndpointStand == null ? waypoint : sweepEndpointStand)")
            && snake.contains("sweepEndpointStand = findSafeEdgeStand(waypoint, 2)"),
            "an occupied or unreachable end cell can use an adjacent safe stand");
        String entry=section(worker,"private void tickSnakeTurn()", "private void skipSnakeLane(");
        check(entry.contains("boundaryEntryStand=findSafeEdgeStand(entry,2)"),
            "edge entrance gets the same adjacent-stand recovery");
        String alternative=section(worker,"private BlockPos findSafeEdgeStand(", "private BlockPos findPlacementStandNear(");
        check(alternative.contains("candidate.equals(focus)") && alternative.contains("!isStandUsable(candidate)")
            && alternative.contains("!canWalkDirectlyTo(candidate)"),
            "fallback never chooses the failed cell or unsafe/unconnected floor");
        System.out.println("GroundPathEdge: exact edge footprint, obstacle safety, bounded scan and edge replan passed.");
    }
    private static String section(String text,String start,String end){int a=text.indexOf(start),b=text.indexOf(end,a+start.length());check(a>=0&&b>a,"source section");return text.substring(a,b);}
    private static boolean walk(double x,double z,double endX,double endZ,GroundPathVisibility.Surface surface) {
        return GroundPathVisibility.canWalk(x,z,endX,endZ,0.3,0.6,4096,surface);
    }
    private static void check(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
}
