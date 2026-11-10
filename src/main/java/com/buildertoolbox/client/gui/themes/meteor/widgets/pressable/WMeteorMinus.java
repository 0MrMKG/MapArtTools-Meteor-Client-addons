/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets.pressable;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.widgets.pressable.WMinus;

public class WMeteorMinus extends WMinus implements MeteorWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double pad = pad();
        double s = theme.scale(3);

        renderBackground(renderer, this, pressed, mouseOver);
        renderer.quad(x + pad, y + height / 2 - s / 2, width - pad * 2, s, theme().minusColor.get());
    }
}
