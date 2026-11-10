/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets.pressable;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.renderer.packer.GuiTexture;
import com.buildertoolbox.client.gui.themes.meteor.MeteorGuiTheme;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.widgets.pressable.WButton;

public class WMeteorButton extends WButton implements MeteorWidget {
    private double hoverProgress;
    public WMeteorButton(String text, GuiTexture texture) {
        super(text, texture);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        MeteorGuiTheme theme = theme();
        double pad = pad();

        hoverProgress=MeteorGuiTheme.approach(hoverProgress,mouseOver?1:0,delta,14);
        renderBackground(renderer,this,
            MeteorGuiTheme.blend(theme.outlineColor.get(),theme.accentColor.get(),pressed?1:hoverProgress*0.7),
            MeteorGuiTheme.blend(theme.backgroundColor.get(),theme.backgroundColor.get(pressed,true),pressed?1:hoverProgress));

        if (text != null) {
            renderer.text(text, x + width / 2 - textWidth / 2, y + pad, theme.textColor.get(), false);
        }
        else {
            double ts = theme.textHeight();
            renderer.quad(x + width / 2 - ts / 2, y + pad, ts, ts, texture, theme.textColor.get());
        }
    }
}
