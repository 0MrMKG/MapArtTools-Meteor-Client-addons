package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Full-width route geometry and source wiring guards, not a live movement simulation. */
public final class ContinuousSnakeTest {
    public static void main(String[] args) throws Exception {
        for (int width : List.of(1,3,4,5)) {
            var lanes = new HashSet<Integer>();
            for (int lane=0; lane*width<128; lane++) lanes.add(lane);
            var forward = MapArtBandGeometry.snakeSweeps(-64,63,-64,63,width,lanes,-64,-64,false);
            var reverse = MapArtBandGeometry.snakeSweeps(-64,63,-64,63,width,lanes,-64,-64,true);
            check(forward.size()==lanes.size(), "one continuous sweep per physical band");
            for (int i=0; i<forward.size(); i++) {
                var sweep=forward.get(i);
                var opposite=reverse.get(reverse.size()-1-i);
                check(Math.abs(sweep.lastX()-sweep.firstX())==127, "edges and short tail bands traverse the full length");
                check(sweep.centerZ()>=-64+sweep.lane()*width
                    && sweep.centerZ()<=Math.min(63,-64+(sweep.lane()+1)*width-1), "centerline remains on actual band");
                check(sweep.lane()==opposite.lane() && sweep.firstX()==opposite.lastX()
                    && sweep.lastX()==opposite.firstX(), "Reverse follows the same full bands backwards");
                if (i>0) check(forward.get(i-1).lastX()==sweep.firstX(), "turns do not walk back across the painting");
                if (i>0 && (sweep.lane()+1)*width<=128)
                    check(sweep.centerZ()-forward.get(i-1).centerZ()==width,"full bands have exact width spacing, not width minus one");
            }
        }
        String root="src/main/java/com/buildertoolbox/";
        String module=Files.readString(Path.of(root+"modules/FullAutoMapArtModule.java"));
        String engine=Files.readString(Path.of(root+"fullauto/FullAutoPlacementEngine.java"));
        String controller=Files.readString(Path.of(root+"fullauto/MapArtBatchController.java"));
        String retry=section(controller,"private void beginRepairPass()","private void inspectDrawing()");
        check(!java.util.regex.Pattern.compile("(?<![\\w.])inspection\\s*=\\s*null").matcher(retry).find()
            && retry.contains("else if(inspection.unknown()>0) inspectionWaypoint=0"),
            "retry known holes preserves inspected cells and never restarts a completed coverage route");
        String work=section(module,"private void tickWork()", "private void buildSnakeWaypoints()");
        check(module.contains("MAX_PENDING_PLACEMENTS = 3") && work.contains("printer.hasQueuedProjectionPlacement()")
            && work.contains("dispatchedPlacements.size() >= MAX_PENDING_PLACEMENTS"), "bounded three-receipt pipeline still serializes actual rotation callbacks");
        String snake=section(module,"private void tickSnakeWork()", "private void tickSnakeTurn()");
        check(!module.contains("boundarySweep") && !module.contains("finishingBoundarySection"), "no periodic boundary stop/repair cursor");
        check(snake.indexOf("selectReachableMatrixTarget(activeLane)") < snake.indexOf("navigateTo(waypoint)"), "drain reachable work before walking");
        check(snake.indexOf("holdForPlacementConfirmation()") < snake.indexOf("navigateTo(waypoint)"), "movement waits for in-flight clicks");
        check(snake.indexOf("finishSnakeBand(activeLane)") > snake.indexOf("result == NavigationResult.ARRIVED"), "band reconciliation only at endpoint or failed navigation");
        String finish=section(module,"private boolean finishSnakeBand(int lane)","private void clearPreciseRepair()");
        check(finish.indexOf("selectReachableMatrixTarget(lane)") < finish.indexOf("for (ProjectionTarget target : targets)"), "endpoint fills reachable neighbors before requesting another repair stand");
        check(finish.contains("if (finishingLane != lane || bandFinishOrigin == null)")
            && finish.contains("!withinBandFinishArea(target.pos())")
            && finish.contains("pos.getX()-bandFinishOrigin.getX()"),"endpoint repair is anchored once and cannot creep backward across a band");
        String repair=section(module,"public boolean batchRepairNearby()","public int batchReviewWidth()");
        check(repair.indexOf("if (missingMaterial != null)") < repair.indexOf("try { placeTarget(best); }"),"review replenishes an obtainable missing color before moving past its holes");
        check(module.contains("if (reviewRestocking) reviewSupplyTried.removeIf(this::hasItem);"),"a successfully supplied review color may be replenished again when consumed");
        String satisfied=section(module,"private boolean isSatisfied(","private int completedMatrixCells()");
        check(satisfied.contains("batch.observedBlock(target.pos()).equals(target.state())")
            && satisfied.contains("mc.world.getBlockState(target.pos()).equals(target.state())"), "client prediction alone cannot complete a pending cell");
        check(engine.contains("validateExternalClick(plan, immutablePos, item, range)")
            && engine.contains("interactWithSupportSneak(plan, immutablePos, item, true, recoverCursor)"), "callback revalidation and workstation sneaking survive throughput changes");
        String turn=section(module,"private void tickSnakeTurn()","private void skipSnakeLane(");
        check(turn.contains("!hasReachedPathStep(entry)") && turn.contains("!walkingProgress.stalled(12)"),"entry aligns on the actual centerline with a bounded attempt");
        System.out.println("ContinuousSnake: full-length forward/reverse bands and pipeline/confirmation/endpoint source guards passed.");
    }
    private static String section(String source,String from,String to) {
        int start=source.indexOf(from), end=source.indexOf(to,start+from.length());
        check(start>=0&&end>start,"source section exists: "+from);
        return source.substring(start,end);
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
