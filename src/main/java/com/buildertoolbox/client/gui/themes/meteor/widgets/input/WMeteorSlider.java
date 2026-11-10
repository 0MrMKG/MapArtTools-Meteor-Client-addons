/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets.input;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.widgets.input.WSlider;

public class WMeteorSlider extends WSlider implements MeteorWidget {
    public WMeteorSlider(double value,double min,double max) { super(value,min,max); }
    @Override protected double handleSize() { return theme.textHeight()*1.3; }
    @Override protected void onRender(GuiRenderer renderer,double mouseX,double mouseY,double delta) {
        double handle=handleSize(),trackX=x+handle/2,trackY=y+height/2-1.5;
        double usable=Math.max(0,width-handle),value=Math.clamp(valueWidth(),0,usable);
        renderer.rounded(trackX,trackY,usable,3,1.5,theme().sliderRight.get());
        renderer.rounded(trackX,trackY,value,3,1.5,theme().sliderLeft.get());
        double cx=x+value+handle/2,knobWidth=dragging?2:4;
        renderer.rounded(cx-knobWidth/2,y,knobWidth,height,2,theme().sliderHandle.get(dragging,handleMouseOver));
    }
}
