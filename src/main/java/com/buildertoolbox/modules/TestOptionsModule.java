package com.buildertoolbox.modules;

import com.buildertoolbox.BuilderToolboxAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;

/** Provides the initial selectable options for Builder Toolbox. */
public final class TestOptionsModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> test1 = sgGeneral.add(new BoolSetting.Builder()
        .name("test1")
        .description("Enable the test1 option.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> test2 = sgGeneral.add(new BoolSetting.Builder()
        .name("test2")
        .description("Enable the test2 option.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> test3 = sgGeneral.add(new BoolSetting.Builder()
        .name("test3")
        .description("Enable the test3 option.")
        .defaultValue(false)
        .build()
    );

    public TestOptionsModule() {
        super(BuilderToolboxAddon.CATEGORY, "test-options", "Contains the test1, test2, and test3 options.");
    }
}
