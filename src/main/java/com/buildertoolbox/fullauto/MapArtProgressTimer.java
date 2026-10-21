package com.buildertoolbox.fullauto;

/** Wall-clock session timing and recent verified-cell throughput; never counts click attempts. */
public final class MapArtProgressTimer {
    private record Sample(long time, int complete) {}
    private final java.util.ArrayDeque<Sample> samples = new java.util.ArrayDeque<>();
    private long sessionStart;
    private long lastGain;
    private int lastComplete;
    private int total = -1;
    private boolean started;

    public void start(long now) {
        sessionStart = now;
        started = true;
        clearDrawing();
    }

    public void clearDrawing() {
        total = -1;
        samples.clear();
    }

    public void beginDrawing(long now, int total, int complete) {
        this.total = total;
        lastComplete = complete;
        lastGain = now;
        samples.clear();
        samples.addLast(new Sample(now, complete));
    }

    public void observe(long now, int total, int complete) {
        if (this.total != total || complete < lastComplete) {
            beginDrawing(now, total, complete);
            return;
        }
        if (complete > lastComplete) lastGain = now;
        lastComplete = complete;
        Sample latest = samples.peekLast();
        if (latest == null || now - latest.time >= 1_000_000_000L) samples.addLast(new Sample(now, complete));
        // Keep the sample at or just before the two-minute boundary for a stable rate.
        while (samples.size() > 2) {
            var iterator = samples.iterator();
            iterator.next();
            if (now - iterator.next().time < 120_000_000_000L) break;
            samples.removeFirst();
        }
    }

    public String elapsed(long now) {
        return "本次已运行 " + duration(started ? Math.max(0, now - sessionStart) / 1_000_000_000L : 0);
    }

    public String estimate(long now, int total, int complete) {
        observe(now, total, complete);
        if (total <= 0) return "预计铺设剩余：暂无绘制目标";
        if (complete >= total) return "铺设矩阵已完成，最终结果以复查为准";
        if (now - lastGain >= 60_000_000_000L) return "预计铺设剩余：进度停滞，暂无法估算";
        Sample first = samples.peekFirst();
        double seconds = (now - first.time) / 1_000_000_000.0;
        int gain = complete - first.complete;
        if (seconds < 20 || gain < 8) return "预计铺设剩余：估算中";
        long remaining = (long) Math.ceil((total - complete) * seconds / gain);
        return "预计铺设剩余约 " + duration(remaining) + "（另需复查）";
    }

    public static String duration(long seconds) {
        seconds = Math.max(0, seconds);
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long rest = seconds % 60;
        if (hours > 0) return hours + "小时" + minutes + "分" + rest + "秒";
        if (minutes > 0) return minutes + "分" + rest + "秒";
        return rest + "秒";
    }
}
