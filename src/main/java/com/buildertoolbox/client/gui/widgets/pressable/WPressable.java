/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 * Adapted for standalone Map Art Tools on 2026-11-10; see licenses/meteor-gui-sources.txt.
 */

package com.buildertoolbox.client.gui.widgets.pressable;

import com.buildertoolbox.client.gui.widgets.WWidget;
import net.minecraft.client.gui.Click;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

public abstract class WPressable extends WWidget {
    public Runnable action;

    protected boolean pressed;

    @Override
    public boolean onMouseClicked(Click click, boolean doubled) {
        if (mouseOver && (click.button() == GLFW_MOUSE_BUTTON_LEFT || click.button() == GLFW_MOUSE_BUTTON_RIGHT)) pressed = true;
        return pressed;
    }

    @Override
    public boolean onMouseReleased(Click click) {
        if (pressed) {
            onPressed(click.button());
            if (action != null) action.run();

            pressed = false;
        }

        return false;
    }

    protected void onPressed(int button) {}
}
