/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets.pressable;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.themes.meteor.MeteorGuiTheme;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.widgets.pressable.WCheckbox;

public class WMeteorCheckbox extends WCheckbox implements MeteorWidget {
    private double progress;
    public WMeteorCheckbox(boolean checked) { super(checked);progress=checked?1:0; }
    @Override protected void onRender(GuiRenderer renderer,double mouseX,double mouseY,double delta) {
        MeteorGuiTheme theme=theme();
        progress=MeteorGuiTheme.approach(progress,checked?1:0,delta,16);
        renderBackground(renderer,this,pressed,mouseOver);
        if(progress<=0)return;
        renderer.rounded(x+1,y+1,width-2,height-2,MeteorGuiTheme.CONTROL_RADIUS-1,
            MeteorGuiTheme.blend(theme.backgroundColor.get(pressed,mouseOver),theme.accentColor.get(),progress));
        // A geometric check avoids missing Unicode glyphs and needs no external icon font.
        if(progress>0.45) {
            var ink=theme.windowColor.get();
            double cx=x+width/2,cy=y+height/2;
            for(int i=0;i<3;i++)renderer.quad(cx-4+i,cy+i-1,1,2,ink);
            for(int i=0;i<5;i++)renderer.quad(cx-2+i,cy+1-i,1,2,ink);
        }
    }
}
