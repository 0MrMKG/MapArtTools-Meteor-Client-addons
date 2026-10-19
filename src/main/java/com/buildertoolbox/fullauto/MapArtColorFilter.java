package com.buildertoolbox.fullauto;

import java.util.Collection;
import java.util.List;

/** Exact vanilla dyed-block IDs, not approximate map RGB values or name substrings. */
public final class MapArtColorFilter {
    public static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
        "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<String> FAMILIES = List.of("carpet", "wool", "concrete", "concrete_powder",
        "terracotta", "glazed_terracotta", "stained_glass", "stained_glass_pane", "shulker_box",
        "bed", "banner", "wall_banner", "candle", "candle_cake");

    private MapArtColorFilter() { }

    public static String colorOf(String namespace, String path) {
        if (!"minecraft".equals(namespace)) return null;
        for (String color : COLORS) {
            if (!path.startsWith(color + "_")) continue;
            if (FAMILIES.contains(path.substring(color.length() + 1))) return color;
        }
        return null;
    }

    public static boolean excludes(boolean enabled, Collection<String> excluded, String namespace, String path) {
        if (!enabled) return false;
        String color = colorOf(namespace, path);
        return color != null && excluded.contains(color);
    }
}
