package com.buildertoolbox.utils;

import java.util.List;
import java.util.Set;

/** Verifies that exclusion never mistakes similarly named non-dyed blocks for a dye color. */
public final class MapArtColorFilterTest {
    public static void main(String[] args) {
        check(MapArtColorFilter.COLORS.size() == 16 && Set.copyOf(MapArtColorFilter.COLORS).size() == 16,
            "all sixteen distinct dye colors");
        for (String color : MapArtColorFilter.COLORS) {
            for (String family : List.of("carpet", "wool", "concrete", "concrete_powder", "terracotta",
                "glazed_terracotta", "stained_glass", "stained_glass_pane", "shulker_box", "bed",
                "banner", "wall_banner", "candle", "candle_cake")) {
                String path = color + "_" + family;
                check(color.equals(MapArtColorFilter.colorOf("minecraft", path)), "identify " + path);
                check(MapArtColorFilter.excludes(true, List.of(color), "minecraft", path), "exclude " + path);
                check(!MapArtColorFilter.excludes(false, List.of(color), "minecraft", path), "master off: " + path);
            }
        }
        for (String path : List.of("redstone_block", "blackstone", "blue_ice", "white_tulip", "red_sand",
            "gray_fake_carpet", "glass", "terracotta", "moss_carpet", "pale_moss_carpet")) {
            check(!MapArtColorFilter.excludes(true, MapArtColorFilter.COLORS, "minecraft", path),
                "do not mistake unrelated block for a dyed block: " + path);
        }
        check(!MapArtColorFilter.excludes(true, List.of("gray"), "minecraft", "light_gray_carpet"),
            "gray and light gray are independent");
        check(!MapArtColorFilter.excludes(true, List.of("blue"), "minecraft", "light_blue_carpet"),
            "blue and light blue are independent");
        check(!MapArtColorFilter.excludes(true, List.of("red"), "example", "red_carpet"),
            "unknown mod block names are not assumed to be vanilla dye colors");
        check(!MapArtColorFilter.excludes(true, List.of(), "minecraft", "red_carpet"),
            "unchecking a color includes it again");
        System.out.println("MapArtColorFilterTest passed: 16 colors, 224 dyed IDs, master switch and false positives.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
