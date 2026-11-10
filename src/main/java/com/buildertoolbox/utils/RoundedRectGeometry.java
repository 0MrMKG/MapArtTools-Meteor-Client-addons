package com.buildertoolbox.utils;

import java.util.ArrayList;
import java.util.List;

/** Non-overlapping integer spans, rendered by vanilla DrawContext without a shader or new pipeline. */
public final class RoundedRectGeometry {
    private RoundedRectGeometry() { }

    public record Span(int inset, int y, int width, int height) { }

    public static List<Span> spans(int width, int height, int radius, boolean top, boolean bottom) {
        if (width <= 0 || height <= 0) return List.of();
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        int topHeight = top ? r : 0, bottomHeight = bottom ? r : 0;
        List<Span> result = new ArrayList<>(r * 2 + 1);
        for (int y = 0; y < topHeight; y++) add(result, inset(r, y), y, width);
        if (height > topHeight + bottomHeight)
            result.add(new Span(0, topHeight, width, height - topHeight - bottomHeight));
        for (int y = height - bottomHeight; y < height; y++) add(result, inset(r, height - y - 1), y, width);
        return result;
    }

    private static int inset(int radius, int row) {
        double dy = radius - row - 0.5;
        return Math.max(0, (int) Math.ceil(radius - Math.sqrt(radius * radius - dy * dy) - 0.5));
    }

    private static void add(List<Span> spans, int inset, int y, int width) {
        if (!spans.isEmpty()) {
            Span previous = spans.getLast();
            if (previous.inset == inset && previous.y + previous.height == y) {
                spans.set(spans.size() - 1, new Span(inset, previous.y, width - 2 * inset, previous.height + 1));
                return;
            }
        }
        spans.add(new Span(inset, y, width - 2 * inset, 1));
    }
}
