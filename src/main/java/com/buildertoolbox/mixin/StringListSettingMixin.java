package com.buildertoolbox.mixin;

import com.buildertoolbox.BuilderToolboxAddon;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.StringListSetting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StringListSetting.class, remap = false)
public abstract class StringListSettingMixin {
    @Inject(method = "fillTable", at = @At("TAIL"))
    private static void builderToolbox$translateList(GuiTheme theme, WTable table, StringListSetting setting, CallbackInfo ci) {
        if (setting.module == null || setting.module.category != BuilderToolboxAddon.CATEGORY) return;
        for (var cell : table.cells) {
            if (cell.widget() instanceof WButton button && "Add".equals(button.getText())) button.set("添加");
            if ("Reset".equals(cell.widget().tooltip)) cell.widget().tooltip = "重置";
        }
    }
}
