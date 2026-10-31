package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Checkpoint geometry and source integration guards; does not simulate live Minecraft movement. */
public final class DrawingResumeTest {
    public static void main(String[] args) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            for (int originX : List.of(-64, 63)) for (int originZ : List.of(-64, 63)) {
                var lanes = new HashSet<Integer>();
                for (int lane = 0; lane * 5 < 128; lane++) lanes.add(lane);
                var sweeps = MapArtBandGeometry.snakeSweeps(-64, 63, -64, 63, 5, lanes, originX, originZ, reverse);
                var route = new ArrayList<BatchPrintCheckpoint.Step>();
                for (var sweep : sweeps) {
                    route.add(new BatchPrintCheckpoint.Step(sweep.firstX(), sweep.centerZ(), sweep.lane(), true));
                    route.add(new BatchPrintCheckpoint.Step(sweep.lastX(), sweep.centerZ(), sweep.lane(), false));
                }
                for (int band = 0; band < sweeps.size(); band++) {
                    var active = sweeps.get(band);
                    int direction = Integer.compare(active.lastX(), active.firstX());
                    for (int stopX : List.of(-64, -20, 12, 63)) {
                        var checkpoint = new BatchPrintCheckpoint(5, reverse, band * 2 + 1, originX, originZ,
                            stopX, active.centerZ(), 0, route, true);
                        var restored = BatchPrintCheckpoint.decode(checkpoint.encode());
                        check(restored.equals(checkpoint), "cursor, direction, work position and supply state survive disk encoding");
                        for (int other = 0; other < sweeps.size(); other++) {
                            int lane = sweeps.get(other).lane();
                            for (int x = -64; x <= 63; x++) {
                                boolean expected = other < band || (other == band && (x - stopX) * direction < -4);
                                check(restored.precedesResume(lane, x, 4) == expected,
                                    "defer only earlier bands and the old half-band; preserve overlap and all upcoming work");
                            }
                        }
                        var entering = new BatchPrintCheckpoint(5, reverse, band * 2, originX, originZ,
                            stopX, active.centerZ(), 0, route);
                        for (int x = -64; x <= 63; x++)
                            check(!entering.precedesResume(active.lane(), x, 4), "a stop on the way to the entry cannot skip any of that band");
                    }
                }
                var finished = new BatchPrintCheckpoint(5, reverse, route.size(), originX, originZ, 0, 0, 0, route);
                for (int lane : lanes) check(finished.precedesResume(lane, 0, 4), "finished first sweep resumes final review, even if old holes exist");
            }
        }

        var sparseRoute = List.of(new BatchPrintCheckpoint.Step(-64, -52, 2, true),
            new BatchPrintCheckpoint.Step(63, -52, 2, false));
        var sparse = new BatchPrintCheckpoint(5, false, 1, -64, -64, 0, -52, 0, sparseRoute);
        check(sparse.precedesResume(0, 30, 4) && sparse.precedesResume(10, 30, 4),
            "holes in lanes omitted by the original route stay for final real-world review");
        check(!sparse.precedesResume(2, -4, 4) && sparse.precedesResume(2, -5, 4),
            "overlap includes the interrupted placement range, without traversing the old half-band");

        String module = Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        String restore = section(module, "public void batchResumeDrawing(", "public void batchRestoreReviewContext(");
        String compatible = restore.substring(restore.indexOf("snakeWaypoints.clear()"));
        check(compatible.contains("snakeWaypointIndex = checkpoint.cursor()")
            && compatible.contains("new BlockPos(checkpoint.resumeX(), walkY, checkpoint.resumeZ())")
            && !compatible.contains("buildSnakeWaypoints()") && !module.contains("ResumeSurvey"),
            "compatible resume goes to its saved cursor and position, without a survey or route rebuild");
        check(compatible.contains("resumeSupplyPending = checkpoint.restocking()") && compatible.contains("openingBands.reset()"),
            "restocking resumes only when saved; the original opening pair is not restarted");
        check(!compatible.contains("placementMatrix.put(") && !compatible.contains("remainingTargets.remove("),
            "deferring old holes must not mark them complete");
        String work = section(module, "private void tickWork()", "private void buildSnakeWaypoints()");
        check(work.indexOf("if (resumeSupplyPending)") < work.indexOf("if (returningFromRestock)")
            && work.indexOf("tickReturnFromRestock()") < work.indexOf("tickSnakeWork()"),
            "saved supply work precedes returning to the breakpoint, then the existing snake continues");
        String capture = section(module, "public BatchPrintCheckpoint batchDrawingCheckpoint()", "public void batchResumeDrawing(");
        check(capture.contains("restockReturnPos != null ? restockReturnPos") && capture.contains("resumeSupplyPending ||"),
            "stopping again during supply/return retains the work anchor and pending supply phase");
        String restock = section(module, "private void beginRestock(", "private WorkChoice findStockedTarget()");
        check(restock.contains("restockReturnPos != null ? restockReturnPos"), "restocking from a restart does not replace the anchor with the chest position");
        String review = section(module, "public void batchBeginReviewRepairs(", "public boolean batchRepairNearby()");
        check(review.contains("deferredResumeTargets.clear()"), "final review includes all deferred holes again");
        System.out.println("DrawingResume: forward/reverse/corner/mid-band/entry/completed/sparse checkpoints and supply/return/review integration guards passed.");
    }

    private static String section(String source, String from, String to) {
        int start = source.indexOf(from), end = source.indexOf(to, start + from.length());
        check(start >= 0 && end > start, "source section exists: " + from);
        return source.substring(start, end);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
