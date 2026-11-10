package com.buildertoolbox.client.gui.themes.meteor;

import com.buildertoolbox.client.gui.GuiTheme;
import com.buildertoolbox.client.gui.utils.AlignmentX;
import com.buildertoolbox.client.utils.render.color.Color;

/** Catppuccin Mocha surfaces with the existing green accent and native CJK-capable text renderer. */
public final class MeteorGuiTheme extends GuiTheme {
    public static final double WINDOW_RADIUS = 6;
    public static final double CONTROL_RADIUS = 3;

    public static final class Value<T> {
        private final T value;
        public Value(T value) { this.value = value; }
        public T get() { return value; }
    }

    public static final class States {
        private final Color normal, hover, pressed;
        public States(Color normal, Color hover, Color pressed) {
            this.normal = normal; this.hover = hover; this.pressed = pressed;
        }
        public Color get() { return normal; }
        public Color get(boolean down, boolean over, boolean ignored) { return get(down, over); }
        public Color get(boolean down, boolean over) { return down ? pressed : over ? hover : normal; }
    }

    private static Color rgb(int hex) { return new Color(hex >> 16 & 255, hex >> 8 & 255, hex & 255, 255); }
    private static Value<Color> color(int hex) { return new Value<>(rgb(hex)); }

    public final Value<Color> accentColor = color(0xa6e3a1);
    public final Value<Color> checkboxColor = accentColor;
    public final Value<Color> textColor = color(0xcdd6f4), titleTextColor = accentColor;
    public final Value<Color> mutedText = color(0xa6adc8), windowColor = color(0x181825);
    public final Value<Color> sectionColor = color(0x1e1e2e), headerColor = color(0x242436);
    public final Value<Color> shadowColor = new Value<>(new Color(17, 17, 27, 65));
    public final Value<Color> moduleBackground = new Value<>(new Color(166, 227, 161, 44));
    public final Value<Color> placeholderColor = color(0x7f849c);
    public final Value<Color> textHighlightColor = new Value<>(new Color(166, 227, 161, 65));
    public final Value<Color> plusColor = accentColor, minusColor = color(0xf38ba8);
    public final Value<Color> separatorText = mutedText, separatorCenter = color(0x45475a);
    public final Value<Color> separatorEdges = color(0x313244), disableHoverColor = textColor;
    public final States backgroundColor = new States(rgb(0x313244), rgb(0x45475a), rgb(0x585b70));
    public final States outlineColor = new States(rgb(0x45475a), rgb(0x89b88a), rgb(0xa6e3a1));
    public final States scrollbarColor = new States(rgb(0x585b70), rgb(0x7f849c), rgb(0xa6e3a1));
    public final States sliderLeft = new States(rgb(0xa6e3a1), rgb(0xa6e3a1), rgb(0x94e2d5));
    public final States sliderHandle = sliderLeft;
    public final States sliderRight = new States(rgb(0x45475a), rgb(0x585b70), rgb(0x585b70));
    public final Value<AlignmentX> moduleAlignment = new Value<>(AlignmentX.Left);

    public static double approach(double value, double target, double delta, double speed) {
        double result = value + (target - value) * (1 - Math.exp(-Math.max(0, delta) * speed));
        return Math.abs(result - target) < 0.001 ? target : result;
    }

    public static Color blend(Color from, Color to, double amount) {
        double t = Math.clamp(amount, 0, 1);
        return new Color((int) Math.round(from.r + (to.r - from.r) * t),
            (int) Math.round(from.g + (to.g - from.g) * t),
            (int) Math.round(from.b + (to.b - from.b) * t),
            (int) Math.round(from.a + (to.a - from.a) * t));
    }
}
