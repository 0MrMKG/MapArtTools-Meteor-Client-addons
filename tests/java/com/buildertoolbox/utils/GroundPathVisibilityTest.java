package com.buildertoolbox.utils;

public final class GroundPathVisibilityTest {
    public static void main(String[] args) {
        check(clear(0.5, 0.5, 20.5, 13.5, (x, z) -> 0), "open floor allows a straight arbitrary diagonal");
        check(clear(-10.5, -7.5, 20.5, 13.5, (x, z) -> 0), "negative coordinates");
        check(!clear(0.5, 0.5, 20.5, 13.5, (x, z) -> x == 10 ? Double.NaN : 0), "wall blocks shortcut");
        check(!clear(0.5, 0.5, 2.5, 2.5, (x, z) -> x == 1 && z == 1 ? Double.NaN : 0), "hole blocks diagonal");
        check(!clear(0.5, 0.5, 1.5, 1.5, (x, z) -> x == 1 && z == 0 ? Double.NaN : 0), "no corner cutting");
        check(!clear(0.5, 0.5, 1.5, 1.5, (x, z) -> x == 0 && z == 1 ? Double.NaN : 0), "both corner sides checked");
        check(clear(0.5, 0.5, 20.5, 13.5, (x, z) -> (x + z) % 2 == 0 ? 0 : 0.0625), "carpet edges remain traversable");
        check(!clear(0.5, 0.5, 20.5, 13.5, (x, z) -> x < 10 ? 0 : 1), "cannot climb a full block");
        check(!clear(0.5, 0.5, 20.5, 13.5, (x, z) -> x < 10 ? 0 : -1), "cannot step down a full block");
        check(clear(0.5, 0.5, 0.5, 20.5, (x, z) -> x == 0 ? 0 : Double.NaN), "one-block straight corridor fits");
        check(!clear(0.1, 0.5, 0.1, 20.5, (x, z) -> x == 0 ? 0 : Double.NaN), "footprint prevents edge clipping");
        check(!GroundPathVisibility.canWalk(0.5, 0.5, 100.5, 100.5, 0.3, 0.6, 10, (x, z) -> 0), "bounded visibility work");
        System.out.println("GroundPathVisibility: 12 diagonal travel safety checks passed.");
    }

    private static boolean clear(double x, double z, double endX, double endZ, GroundPathVisibility.Surface surface) {
        return GroundPathVisibility.canWalk(x, z, endX, endZ, 0.3, 0.6, 4096, surface);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
