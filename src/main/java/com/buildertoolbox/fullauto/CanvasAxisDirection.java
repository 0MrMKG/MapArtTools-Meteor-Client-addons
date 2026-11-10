package com.buildertoolbox.fullauto;

/** Enum names are stable config values; button labels are independent of serialization. */
public enum CanvasAxisDirection {
    POSITIVE(1), NEGATIVE(-1);

    private final int sign;
    CanvasAxisDirection(int sign) { this.sign = sign; }
    public int sign() { return sign; }
    public CanvasAxisDirection next() { return this == POSITIVE ? NEGATIVE : POSITIVE; }
    public String buttonLabel(String axis) { return axis + (sign > 0 ? " +" : " -"); }
}
