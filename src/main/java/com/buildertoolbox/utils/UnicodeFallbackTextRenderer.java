package com.buildertoolbox.utils;

import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

/** Keep Meteor's Latin font and use Minecraft's Unicode fonts for complete non-Latin labels. */
public final class UnicodeFallbackTextRenderer implements TextRenderer {
    private static UnicodeFallbackTextRenderer cached;
    private final TextRenderer original;
    private final TextRenderer fallback = VanillaTextRenderer.INSTANCE;
    private final double baseHeight;
    private boolean building;
    private boolean fallbackBuilding;
    private double activeHeight;

    private UnicodeFallbackTextRenderer(TextRenderer original) {
        this.original = original;
        baseHeight = original.getHeight(false);
    }

    public static TextRenderer wrap(TextRenderer renderer) {
        if (!(renderer instanceof CustomTextRenderer)) return renderer;
        if (cached == null || cached.original != renderer) cached = new UnicodeFallbackTextRenderer(renderer);
        return cached;
    }

    private static boolean needsFallback(String text, int length) {
        for (int i = 0; i < length; i++) {
            if (text.charAt(i) > 0xFF) return true;
        }
        return false;
    }

    private double pixelScale() {
        double height = building ? activeHeight : baseHeight;
        return height / MinecraftClient.getInstance().textRenderer.fontHeight;
    }

    @Override public double getWidth(String text, int length, boolean shadow) {
        if (!needsFallback(text, length)) return original.getWidth(text, length, shadow);
        String visible = length == text.length() ? text : text.substring(0, length);
        return (MinecraftClient.getInstance().textRenderer.getWidth(visible) + (shadow ? 1 : 0)) * pixelScale();
    }

    @Override public double getHeight(boolean shadow) {
        return (building ? activeHeight : baseHeight) + (shadow ? 1 : 0);
    }

    @Override public void setAlpha(double alpha) {
        original.setAlpha(alpha);
        fallback.setAlpha(alpha);
    }

    @Override public void begin(double scale, boolean scaleOnly, boolean big) {
        original.begin(scale, scaleOnly, big);
        activeHeight = big ? original.getHeight(false) : baseHeight * scale;
        building = true;
    }

    @Override public double render(String text, double x, double y, Color color, boolean shadow) {
        if (!needsFallback(text, text.length())) return original.render(text, x, y, color, shadow);
        boolean standalone = !building;
        if (standalone) begin();
        try {
            if (!fallbackBuilding) {
                // VanillaTextRenderer doubles the supplied scale. Match the
                // active Meteor font's line height at every GUI/title scale.
                fallback.begin(pixelScale() / 2, false, false);
                fallbackBuilding = true;
            }
            return fallback.render(text, x, y, color, shadow);
        } finally {
            if (standalone) end();
        }
    }

    @Override public boolean isBuilding() { return building; }

    @Override public void end() {
        if (!building) return;
        try {
            original.end();
        } finally {
            if (fallbackBuilding) fallback.end();
            fallbackBuilding = false;
            building = false;
        }
    }
}
