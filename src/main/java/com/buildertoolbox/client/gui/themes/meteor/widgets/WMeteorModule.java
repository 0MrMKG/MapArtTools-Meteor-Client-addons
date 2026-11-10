/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.themes.meteor.MeteorGuiTheme;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.utils.AlignmentX;
import com.buildertoolbox.client.gui.widgets.pressable.WPressable;
import com.buildertoolbox.client.systems.modules.Module;
import net.minecraft.util.math.MathHelper;

import static com.buildertoolbox.client.ClientContext.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

public class WMeteorModule extends WPressable implements MeteorWidget {
    private final Module module;
    private final String title;

    private double titleWidth;

    private double animationProgress1;

    private double animationProgress2;

    public WMeteorModule(Module module, String title) {
        this.module = module;
        this.title = title;
        this.tooltip = module.description;

        if (module.isActive()) {
            animationProgress1 = 1;
            animationProgress2 = 1;
        } else {
            animationProgress1 = 0;
            animationProgress2 = 0;
        }
    }

    @Override
    public double pad() {
        return theme.scale(4);
    }

    @Override
    protected void onCalculateSize() {
        double pad = pad();

        if (titleWidth == 0) titleWidth = theme.textWidth(title);

        width = pad + 8 + titleWidth + pad + 8;
        height = pad + theme.textHeight() + pad + 2;
    }

    @Override
    protected void onPressed(int button) {
        if (button == GLFW_MOUSE_BUTTON_LEFT) module.toggle();
        else if (button == GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(module));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MeteorGuiTheme theme = theme();
        double pad = pad();

        animationProgress1 = MeteorGuiTheme.approach(animationProgress1, mouseOver ? 1 : 0, delta, 14);
        animationProgress2 = MeteorGuiTheme.approach(animationProgress2, module.isActive() ? 1 : 0, delta, 12);
        double highlight = Math.min(1, animationProgress2 * 0.75 + animationProgress1 * 0.35);
        if (highlight > 0) renderer.rounded(x + 1, y + 1, width - 2, height - 2,
            MeteorGuiTheme.CONTROL_RADIUS, new com.buildertoolbox.client.utils.render.color.Color(166,227,161,(int)(55*highlight)));
        if (animationProgress2 > 0) renderer.rounded(x + 4, y + 4, 2, height - 8, 1,
            MeteorGuiTheme.blend(theme.sectionColor.get(),theme.accentColor.get(),animationProgress2));

        double x = this.x + pad + 8;
        double w = width - pad * 2 - 16;

        if (theme.moduleAlignment.get() == AlignmentX.Center) {
            x += w / 2 - titleWidth / 2;
        }
        else if (theme.moduleAlignment.get() == AlignmentX.Right) {
            x += w - titleWidth;
        }

        renderer.text(title, x, y + (height-theme.textHeight())/2,
            MeteorGuiTheme.blend(theme.textColor.get(),theme.accentColor.get(),animationProgress2), false);
    }
}
