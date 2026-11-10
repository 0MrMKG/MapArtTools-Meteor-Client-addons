/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.themes.meteor.widgets;

import com.buildertoolbox.client.gui.renderer.GuiRenderer;
import com.buildertoolbox.client.gui.themes.meteor.MeteorGuiTheme;
import com.buildertoolbox.client.gui.themes.meteor.MeteorWidget;
import com.buildertoolbox.client.gui.widgets.WWidget;
import com.buildertoolbox.client.gui.widgets.containers.WWindow;

public class WMeteorWindow extends WWindow implements MeteorWidget {
    public WMeteorWindow(WWidget icon,String title) { super(icon,title); }
    @Override protected WHeader header(WWidget icon) { return new WMeteorHeader(icon); }
    @Override protected void onRender(GuiRenderer renderer,double mouseX,double mouseY,double delta) {
        double visibleHeight=expanded||animProgress>0?height:header.height;
        renderer.shadow(x,y,width,visibleHeight,MeteorGuiTheme.WINDOW_RADIUS,theme().shadowColor.get());
        renderer.roundedOutline(x,y,width,visibleHeight,MeteorGuiTheme.WINDOW_RADIUS,
            theme().windowColor.get(),theme().outlineColor.get());
    }
    private class WMeteorHeader extends WHeader {
        public WMeteorHeader(WWidget icon) { super(icon); }
        @Override protected void onRender(GuiRenderer renderer,double mouseX,double mouseY,double delta) {
            boolean collapsed=!expanded&&animProgress==0;
            renderer.rounded(x+1,y+1,width-2,height-1,MeteorGuiTheme.WINDOW_RADIUS-1,
                theme().headerColor.get(),true,collapsed);
            renderer.rounded(x+6,y+5,2,height-10,1,theme().accentColor.get());
            if(!collapsed)renderer.quad(x+6,y+height-1,width-12,1,theme().separatorCenter.get());
        }
    }
}
