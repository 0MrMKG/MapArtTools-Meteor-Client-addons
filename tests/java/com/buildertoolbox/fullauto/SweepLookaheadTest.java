package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real lookahead policy with synthetic paths; collisions are supplied by the worker. */
public final class SweepLookaheadTest {
    public static void main(String[] args) throws Exception {
        for (int direction : new int[] {-1, 1}) {
            int[] xs = new int[12], zs = new int[12];
            for (int i = 0; i < xs.length; i++) xs[i] = direction * i;
            int next = choose(0.5, 0.5, direction * 11, xs, zs, 0, -1);
            check(next == 4, "forward/reverse four-block lookahead");
            check(choose(0.5 + direction * 0.25, 0.5, direction * 11, xs, zs, 0, -1) == 4,
                "passing a grid center must not cause a stop or backward turn");
            check(choose(0.5, 0.5, direction * 11, xs, zs, 0, 2) == 1,
                "never skip across an unsafe intermediate segment");
            zs[2] = 1;
            check(choose(0.5, 0.5, direction * 11, xs, zs, 0, -1) == 1,
                "never shortcut a detour even if it later rejoins the centerline");
            zs[2] = 0;
            xs[2] = xs[1] - direction;
            check(choose(0.5, 0.5, direction * 11, xs, zs, 0, -1) == 1,
                "never shortcut a reversing path");
        }
        int[] xs = {0, 1, 2}, zs = {0, 0, 0};
        check(choose(0.5, 0.8, 2, xs, zs, 0, -1) == 0, "off-center entry retains normal alignment");
        check(choose(0.5, 0.5, 2, xs, zs, 3, -1) == 3, "exhausted path stays exhausted");
        check(choose(0.5, 0.5, 2, xs, zs, 0, -1) == 2, "short band stops at its endpoint");
        String code = Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        int begin = code.indexOf("private void advanceStraightSweepPath(");
        String helper = code.substring(begin, code.indexOf("private boolean shouldSprintToSupply(", begin));
        check(helper.contains("returningFromRestock") && helper.contains("snakeEntryWaypoints.contains")
            && helper.contains("finishingLane >= 0") && helper.contains("navigationNudge != null")
            && helper.contains("isStandUsable(path.get(i)) && canWalkDirectlyTo(path.get(i))"),
            "only straight work routes use checked lookahead; recovery and turns keep their own navigation");
        System.out.println("SweepLookahead: both directions, passed centers, obstacles, turns, reversed paths and endpoints passed.");
    }
    private static int choose(double x, double z, int goal, int[] xs, int[] zs, int start, int blocked) {
        return SweepStride.lookahead(x,z,goal,0,start,xs.length,i -> xs[i],i -> zs[i],i -> i != blocked);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
