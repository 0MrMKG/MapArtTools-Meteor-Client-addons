package com.buildertoolbox.utils;

public final class MapArtProgressTimerTest {
    private static long seconds(long value) { return value * 1_000_000_000L; }
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        MapArtProgressTimer timer = new MapArtProgressTimer();
        timer.start(0);
        timer.beginDrawing(0, 1000, 800);
        check(timer.estimate(seconds(10), 1000, 810).contains("估算中"), "warm-up is not a premature ETA");
        check(timer.estimate(seconds(20), 1000, 820).contains("3分0秒"), "existing 800 cells are not counted as new throughput");
        check(timer.estimate(seconds(40), 1000, 820).contains("6分0秒"), "supply/walk time increases ETA");
        check(timer.estimate(seconds(80), 1000, 820).contains("进度停滞"), "no false countdown during a long stall");
        check(timer.estimate(seconds(90), 1000, 830).contains("预计铺设剩余约"), "new progress restores estimate");
        check(timer.estimate(seconds(100), 1000, 790).contains("估算中"), "rollback resets rate without a negative ETA");
        check(timer.estimate(seconds(110), 900, 800).contains("估算中"), "changed filters reset demand baseline");
        check(timer.estimate(seconds(120), 900, 900).contains("复查"), "cached completion must not assert verified job completion");
        timer.clearDrawing();
        timer.beginDrawing(seconds(130), 100, 0);
        check(timer.elapsed(seconds(140)).contains("2分20秒"), "next painting does not reset session elapsed time");
        check(timer.estimate(seconds(150), 100, 20).contains("1分20秒"), "new painting has its own rate");
        check(MapArtProgressTimer.duration(3661).equals("1小时1分1秒"), "hour formatting");
        check(timer.estimate(seconds(160), 0, 0).contains("暂无"), "empty target list avoids divide by zero");
        timer.start(seconds(170));
        check(timer.elapsed(seconds(175)).contains("5秒"), "re-enabling starts a new explicitly labelled session");

        MapArtProgressTimer ordinary = new MapArtProgressTimer();
        var automatic = new com.buildertoolbox.fullauto.MapArtProgressTimer();
        ordinary.start(0); automatic.start(0);
        ordinary.beginDrawing(0, 1000, 0); automatic.beginDrawing(0, 1000, 0);
        for (int second = 10; second <= 400; second += 10) {
            int complete = Math.min(500, second * 2);
            check(ordinary.estimate(seconds(second), 1000, complete)
                .equals(automatic.estimate(seconds(second), 1000, complete)), "independent timers stay equivalent");
        }
        // Recent rate should adapt after a slow start instead of using the entire run forever.
        ordinary.beginDrawing(0, 1000, 0);
        for (int second = 10; second <= 200; second += 10) {
            ordinary.observe(seconds(second), 1000, Math.max(0, second - 100));
        }
        check(ordinary.estimate(seconds(220), 1000, 120).contains("14分40秒"), "two-minute window drops the initial stall");
        System.out.println("MapArtProgressTimer: elapsed time, resumed baseline, supplies, stalls, rollbacks, filters, new paintings and recent-rate parity passed.");
    }
}
