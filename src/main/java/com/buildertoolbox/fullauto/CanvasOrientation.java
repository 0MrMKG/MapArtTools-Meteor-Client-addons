package com.buildertoolbox.fullauto;

/** Reflect native schematic coordinates about the registered origin, then normalize to world bounds. */
public record CanvasOrientation(int xSign, int zSign) {
    public static final CanvasOrientation DEFAULT = new CanvasOrientation(1, 1);
    public static final int SIZE = 128;

    public CanvasOrientation {
        if (Math.abs((long)xSign) != 1 || Math.abs((long)zSign) != 1)
            throw new IllegalArgumentException("画布 X/Z 方向只能为 +1 或 -1");
    }

    public int minX(int origin, int localMin, int localMax) { return minimum(origin, localMin, localMax, xSign); }
    public int minZ(int origin, int localMin, int localMax) { return minimum(origin, localMin, localMax, zSign); }

    private static int minimum(int origin, int localMin, int localMax, int sign) {
        if (localMin > localMax) throw new IllegalArgumentException("蓝图区域范围无效");
        return Math.addExact(origin, Math.multiplyExact(sign, sign > 0 ? localMin : localMax));
    }

    /** Input indices are native normalized cells; output indices are normalized world cells. */
    public int index(int x, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) throw new IndexOutOfBoundsException("画布格子越界");
        return (zSign > 0 ? z : SIZE - 1 - z) * SIZE + (xSign > 0 ? x : SIZE - 1 - x);
    }

    public String signatureSuffix() {
        // Preserve existing ++ batch hashes and checkpoints exactly.
        return equals(DEFAULT) ? "" : "|canvas-axes=" + xSign + "," + zSign;
    }

    public String description() { return "X" + (xSign > 0 ? "+" : "-") + " Z" + (zSign > 0 ? "+" : "-"); }
}
