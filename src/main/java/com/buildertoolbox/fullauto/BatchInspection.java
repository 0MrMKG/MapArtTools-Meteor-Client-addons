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
    public enum Cell { CORRECT, MISSING, WRONG }
    public record Point(int x, int z) { }
    private final List<Point> route;
    private final byte[] cells = new byte[SIZE * SIZE];
    private int observed, missing, wrong;

    public BatchInspection(int width, boolean reverse) {
        if (width < 1 || width > 5) throw new IllegalArgumentException("Inspection width must be between 1 and 5");
        Arrays.fill(cells, (byte) -1);
        Set<Integer> lanes = new HashSet<>();
        for (int lane = 0; lane * width < SIZE; lane++) lanes.add(lane);
        List<Point> points = new ArrayList<>();
        for (var sweep : MapArtBandGeometry.snakeSweeps(0, SIZE-1, 0, SIZE-1, width, lanes, 0, 0, reverse)) {
            points.add(new Point(sweep.firstX(), sweep.centerZ()));
            points.add(new Point(sweep.lastX(), sweep.centerZ()));
        }
        route = List.copyOf(points);
    }

    public List<Point> route() { return route; }
    public int observed() { return observed; }
    public int unknown() { return cells.length - observed; }
    public int missing() { return missing; }
    public int wrong() { return wrong; }
    public int errors() { return missing + wrong; }
    public boolean complete() { return unknown() == 0; }

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
