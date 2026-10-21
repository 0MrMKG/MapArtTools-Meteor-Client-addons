package com.buildertoolbox.fullauto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Physical inspection route and per-cell observations, independent of placement caches. */
public final class BatchInspection {
    public static final int SIZE = 128;
    public static final int RADIUS = 8;
    public static final int SCAN_WIDTH = 32;
    public static final int MAX_SCAN_CELLS = 1024;
    public static final long MAX_SCAN_NANOS = 1_500_000L;
    public enum Cell { CORRECT, MISSING, WRONG }
    public record Point(int x, int z) { }
    private final List<Point> route;
    private final int width;
    private final boolean reverse;
    private final int routeStart;
    private final int originX, originZ;
    private final byte[] cells = new byte[SIZE * SIZE];
    private int observed, missing, wrong;

    public BatchInspection(int width, boolean reverse) {
        this(width, reverse, 0, 0);
    }

    public BatchInspection(int width, boolean reverse, int originX, int originZ) {
        this(width, reverse, originX, originZ, 0);
    }

    private BatchInspection(int width, boolean reverse, int originX, int originZ, int routeStart) {
        if (width < 1 || width > 64) throw new IllegalArgumentException("Inspection width must be between 1 and 64");
        this.width = width;
        this.reverse = reverse;
        this.originX = originX;
        this.originZ = originZ;
        Arrays.fill(cells, (byte) -1);
        Set<Integer> lanes = new HashSet<>();
        for (int lane = 0; lane * width < SIZE; lane++) lanes.add(lane);
        List<Point> points = new ArrayList<>();
        for (var sweep : MapArtBandGeometry.snakeSweeps(0, SIZE-1, 0, SIZE-1, width, lanes, originX, originZ, reverse)) {
            points.add(new Point(sweep.firstX(), sweep.centerZ()));
            points.add(new Point(sweep.lastX(), sweep.centerZ()));
        }
        if (routeStart < 0 || routeStart >= points.size()) throw new IllegalArgumentException("Inspection route start is invalid");
        this.routeStart = routeStart;
        List<Point> ordered = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) ordered.add(points.get((i + routeStart) % points.size()));
        // A saved next point may be the far end of an unfinished band. Close
        // the rotated lap so that this band's full segment is inspected too.
        if (routeStart != 0) ordered.add(ordered.getFirst());
        route = List.copyOf(ordered);
    }

    public List<Point> route() { return route; }
    public int observed() { return observed; }
    public int unknown() { return cells.length - observed; }
    public int missing() { return missing; }
    public int wrong() { return wrong; }
    public int errors() { return missing + wrong; }
    public boolean complete() { return unknown() == 0; }

    public boolean canAccept(long scannedRevision, long currentRevision, boolean placementPending, int allowedMissing) {
        return scannedRevision >= 0 && scannedRevision == currentRevision && !placementPending
            && BatchWorkflowRules.reviewAction(1, missing, unknown(), allowedMissing) == BatchWorkflowRules.ReviewAction.ACCEPT;
    }

    public Cell cell(int x, int z) {
        byte value = cells[z * SIZE + x];
        return value < 0 ? null : Cell.values()[value];
    }

    /** The whole canvas is perceived; walking is only needed to load unread bands. */
    public boolean needsVisit(Point point) {
        int firstZ = point.z() / width * width;
        for (int z = firstZ; z < Math.min(SIZE, firstZ + width); z++)
            for (int x = 0; x < SIZE; x++) if (!wasObserved(x, z)) return true;
        return false;
    }

    /** Keep broad snake-band order, and repair nearby holes within that band first. */
    public Point nextMissing(int playerX, int playerZ, java.util.function.Predicate<Point> eligible) {
        boolean[] visited = new boolean[SIZE];
        for (Point waypoint : route) {
            int firstZ = waypoint.z() / width * width;
            if (visited[firstZ]) continue;
            visited[firstZ] = true;
            Point best = null;
            long bestDistance = Long.MAX_VALUE;
            for (int z = firstZ; z < Math.min(SIZE, firstZ + width); z++) for (int x = 0; x < SIZE; x++) {
                if (cells[z * SIZE + x] != Cell.MISSING.ordinal()) continue;
                Point candidate = new Point(x, z);
                if (!eligible.test(candidate)) continue;
                long dx = x - (long) playerX, dz = z - (long) playerZ;
                long distance = dx * dx + dz * dz;
                if (distance < bestDistance) { best = candidate; bestDistance = distance; }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Replaceable water/plants still leave a required block missing; solid wrong blocks are separate. */
    public static Cell classify(boolean excluded,boolean expectedAir,boolean matches,
                                boolean actualAir,boolean actualReplaceable) {
        if(excluded||matches)return Cell.CORRECT;
        return !expectedAir&&(actualAir||actualReplaceable)?Cell.MISSING:Cell.WRONG;
    }

    public record Checkpoint(int width, boolean reverse, int waypoint, int resumeX, int resumeZ, String cells,
                             int routeStart, int originX, int originZ) {
        public Checkpoint(int width, boolean reverse, int waypoint, int resumeX, int resumeZ, String cells) {
            this(width, reverse, waypoint, resumeX, resumeZ, cells, 0, 0, 0);
        }
        public Checkpoint(int width, boolean reverse, int waypoint, int resumeX, int resumeZ, String cells, int routeStart) {
            this(width, reverse, waypoint, resumeX, resumeZ, cells, routeStart, 0, 0);
        }
        public Checkpoint {
            if (width < 1 || width > 64 || waypoint < 0 || waypoint > 2 * ((SIZE + width - 1) / width) + (routeStart == 0 ? 0 : 1)
                || routeStart < 0 || routeStart >= 2 * ((SIZE + width - 1) / width))
                throw new IllegalArgumentException("复查断点路点无效");
            byte[] values = java.util.Base64.getDecoder().decode(cells);
            if (values.length != SIZE * SIZE) throw new IllegalArgumentException("复查矩阵尺寸无效");
            for (byte value : values) if (value < -1 || value > 2) throw new IllegalArgumentException("复查矩阵状态无效");
        }
        public String encode() {
            return "3;" + width + ";" + (reverse ? 1 : 0) + ";" + waypoint + ";" + resumeX + ";" + resumeZ + ";" + cells + ";" + routeStart + ";" + originX + ";" + originZ;
        }
        public static Checkpoint decode(String text) {
            String[] parts = text.split(";", -1);
            boolean legacy = parts.length == 7 && parts[0].equals("1");
            boolean version2 = parts.length == 8 && parts[0].equals("2");
            boolean version3 = parts.length == 10 && parts[0].equals("3");
            if (!(legacy || version2 || version3) || !(parts[2].equals("0") || parts[2].equals("1")))
                throw new IllegalArgumentException("复查断点格式无效");
            return new Checkpoint(Integer.parseInt(parts[1]), parts[2].equals("1"), Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), parts[6], legacy ? 0 : Integer.parseInt(parts[7]),
                version3 ? Integer.parseInt(parts[8]) : 0, version3 ? Integer.parseInt(parts[9]) : 0);
        }
    }

    public Checkpoint checkpoint(int waypoint, int resumeX, int resumeZ) {
        return new Checkpoint(width, reverse, waypoint, resumeX, resumeZ, java.util.Base64.getEncoder().encodeToString(cells), routeStart, originX, originZ);
    }

    public static BatchInspection restore(Checkpoint checkpoint) {
        BatchInspection result = new BatchInspection(checkpoint.width(), checkpoint.reverse(), checkpoint.originX(), checkpoint.originZ(), checkpoint.routeStart());
        byte[] values = java.util.Base64.getDecoder().decode(checkpoint.cells());
        for (int i = 0; i < values.length; i++) if (values[i] >= 0)
            result.observe(i % SIZE, i / SIZE, Cell.values()[values[i]]);
        return result;
    }

    /** Resume walking at the saved point, but never trust observations across an offline interval. */
    public static BatchInspection restartAfterOffline(Checkpoint checkpoint) {
        int points = 2 * ((SIZE + checkpoint.width() - 1) / checkpoint.width());
        return new BatchInspection(checkpoint.width(), checkpoint.reverse(), checkpoint.originX(), checkpoint.originZ(),
            (checkpoint.routeStart() + Math.min(points, checkpoint.waypoint())) % points);
    }

    public boolean wasObserved(int x, int z) { return cells[z * SIZE + x] >= 0; }

    public void invalidate(int x, int z) {
        int index = z * SIZE + x;
        byte previous = cells[index];
        if (previous < 0) return;
        observed--;
        if (previous == Cell.MISSING.ordinal()) missing--;
        if (previous == Cell.WRONG.ordinal()) wrong--;
        cells[index] = -1;
    }

    public void observe(int x, int z, Cell result) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) throw new IndexOutOfBoundsException();
        int index = z * SIZE + x;
        byte previous = cells[index];
        if (previous < 0) observed++;
        else if (previous == Cell.MISSING.ordinal()) missing--;
        else if (previous == Cell.WRONG.ordinal()) wrong--;
        cells[index] = (byte) result.ordinal();
        if (result == Cell.MISSING) missing++;
        else if (result == Cell.WRONG) wrong++;
    }
}
