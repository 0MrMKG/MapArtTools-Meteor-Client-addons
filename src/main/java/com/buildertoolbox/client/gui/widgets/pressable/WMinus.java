/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.widgets.pressable;

public abstract class WMinus extends WPressable {
    @Override
    protected void onCalculateSize() {
        double pad = pad();
        double s = theme.textHeight();

        width = pad + s + pad;
        height = pad + s + pad;
    }
}
