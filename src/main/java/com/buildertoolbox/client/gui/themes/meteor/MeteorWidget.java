/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.utils.BaseWidget;
import com.buildertoolbox.client.gui.widgets.WWidget;
import com.buildertoolbox.client.utils.render.color.Color;

public interface MeteorWidget extends BaseWidget {
    default MeteorGuiTheme theme() {
        return (MeteorGuiTheme) getTheme();
    }

    default void renderBackground(GuiRenderer renderer, WWidget widget, Color outlineColor, Color backgroundColor) {
        renderer.roundedOutline(widget.x, widget.y, widget.width, widget.height,
            MeteorGuiTheme.CONTROL_RADIUS, backgroundColor, outlineColor);
    }

    default void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        MeteorGuiTheme theme = theme();
        renderBackground(renderer, widget, theme.outlineColor.get(pressed, mouseOver), theme.backgroundColor.get(pressed, mouseOver));
    }
}
