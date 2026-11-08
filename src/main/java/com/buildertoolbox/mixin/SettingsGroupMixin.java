package com.buildertoolbox.mixin;

import com.buildertoolbox.utils.ChineseSettings;
import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Translate only registered addon group captions, leaving serialized names intact. */
@Mixin(value = DefaultSettingsWidgetFactory.class, remap = false)
public abstract class SettingsGroupMixin {
    @Redirect(method = "group", at = @At(value = "FIELD",
        target = "Lmeteordevelopment/meteorclient/settings/SettingGroup;name:Ljava/lang/String;"))
    private String builderToolbox$groupTitle(SettingGroup group) {
        return ChineseSettings.groupTitle(group);
    }

    @Inject(method = "reset", at = @At("TAIL"))
    private void builderToolbox$resetTooltip(WContainer container, Setting<?> setting, Runnable action, CallbackInfo ci) {
        if (setting.module == null || setting.module.category != com.buildertoolbox.BuilderToolboxAddon.CATEGORY) return;
        for (var cell : container.cells) {
            if ("Reset".equals(cell.widget().tooltip)) cell.widget().tooltip = "重置";
        }
    }
}
