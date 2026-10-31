package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.List;

/** Durable snake cursor. World blocks, inventory clicks and held keys are deliberately not cached. */
public record BatchPrintCheckpoint(int width, boolean reverse, int cursor, int originX, int originZ,
                                   int resumeX, int resumeZ, int verificationPass, List<Step> route, boolean restocking) {
    public record Step(int x, int z, int lane, boolean entry) { }

    public BatchPrintCheckpoint(int width, boolean reverse, int cursor, int originX, int originZ,
                                int resumeX, int resumeZ, int verificationPass, List<Step> route) {
        this(width, reverse, cursor, originX, originZ, resumeX, resumeZ, verificationPass, route, false);
    }

    public BatchPrintCheckpoint {
        route = List.copyOf(route);
        if (width < 1 || width > 128 || route.isEmpty() || route.size() > 257 || cursor < 0 || cursor > route.size()
            || verificationPass < 0)
            throw new IllegalArgumentException("蛇形断点数据无效");
        for (Step step : route) if (step.lane() < 0 || step.lane() > 127) throw new IllegalArgumentException("蛇形断点条带无效");
    }

    public String encode() {
        StringBuilder text = new StringBuilder("2;").append(width).append(';').append(reverse ? 1 : 0).append(';')
            .append(cursor).append(';').append(originX).append(';').append(originZ).append(';')
            .append(resumeX).append(';').append(resumeZ).append(';').append(verificationPass).append(';');
        for (Step step : route) {
            if (text.charAt(text.length()-1) != ';') text.append('|');
            text.append(step.x()).append(',').append(step.z()).append(',').append(step.lane()).append(',').append(step.entry() ? 1 : 0);
        }
        return text.append(';').append(restocking ? 1 : 0).toString();
    }

    /** Keep earlier holes for final review, with a small overlap for unconfirmed clicks at the stop. */
    public boolean precedesResume(int lane, int x, int overlap) {
        if (cursor == route.size()) return true;
        int entry = -1;
        for (int i = 0; i < route.size(); i++) {
            if (route.get(i).lane() == lane) { entry = i; break; }
        }
        // Lanes omitted from the saved route were already passed over. They
        // still belong to the real-world review, not to a new first sweep.
        if (entry < 0) return true;
        if (entry > cursor) return false;
        Step current = route.get(cursor);
        if (current.lane() != lane) return true;
        if (current.entry() || entry + 1 >= route.size() || route.get(entry + 1).lane() != lane) return false;
        int direction = Integer.compare(route.get(entry + 1).x(), route.get(entry).x());
        return ((long) x - resumeX) * direction < -Math.max(0, overlap);
    }

    public static BatchPrintCheckpoint decode(String value) {
        String[] fields = value.split(";", -1);
        boolean legacy = fields.length == 10 && fields[0].equals("1");
        if (!legacy && !(fields.length == 11 && fields[0].equals("2"))) throw new IllegalArgumentException("蛇形断点格式无效");
        List<Step> route = new ArrayList<>();
        for (String raw : fields[9].split("\\|")) {
            String[] step = raw.split(",", -1);
            if (step.length != 4) throw new IllegalArgumentException("蛇形断点路点无效");
            route.add(new Step(Integer.parseInt(step[0]), Integer.parseInt(step[1]), Integer.parseInt(step[2]), bit(step[3])));
        }
        return new BatchPrintCheckpoint(Integer.parseInt(fields[1]), bit(fields[2]), Integer.parseInt(fields[3]),
            Integer.parseInt(fields[4]), Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
            Integer.parseInt(fields[8]), route, !legacy && bit(fields[10]));
    }

    private static boolean bit(String value) {
        if (!value.equals("0") && !value.equals("1")) throw new IllegalArgumentException("蛇形断点标志无效");
        return value.equals("1");
    }
}
