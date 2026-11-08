package com.buildertoolbox.mixin;

import com.buildertoolbox.BuilderToolboxAddon;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = ModuleScreen.class, remap = false)
public abstract class ModuleScreenMixin {
    @Shadow @Final private Module module;

    @ModifyConstant(method = "initWidgets", constant = {
        @Constant(stringValue = "From: "), @Constant(stringValue = "Bind"),
        @Constant(stringValue = "Bind: "), @Constant(stringValue = "Reset"),
        @Constant(stringValue = "Toggle on bind release: "), @Constant(stringValue = "Chat Feedback: "),
        @Constant(stringValue = "Active: "), @Constant(stringValue = "Copy config"),
        @Constant(stringValue = "Paste config")
    })
    private String builderToolbox$translateControls(String original) {
        if (module.category != BuilderToolboxAddon.CATEGORY) return original;
        return switch (original) {
            case "From: " -> "所属插件：";
            case "Bind" -> "快捷键设置";
            case "Bind: " -> "快捷键：";
            case "Reset" -> "重置";
            case "Toggle on bind release: " -> "松开快捷键时切换：";
            case "Chat Feedback: " -> "聊天反馈：";
            case "Active: " -> "启用功能：";
            case "Copy config" -> "复制配置";
            case "Paste config" -> "粘贴配置";
            default -> original;
        };
    }
}
