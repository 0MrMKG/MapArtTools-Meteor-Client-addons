package com.buildertoolbox.mixin;

import com.buildertoolbox.BuilderToolboxAddon;
import com.buildertoolbox.gui.ToolboxGuiTheme;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.screens.ModuleScreen;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scope the native theme to this addon's module settings without touching other modules. */
@Mixin(value=GuiTheme.class,remap=false)
public abstract class ToolboxModuleThemeMixin {
    @Inject(method="moduleScreen",at=@At("HEAD"),cancellable=true)
    private void builderToolbox$moduleTheme(Module module,CallbackInfoReturnable<WidgetScreen> cir) {
        GuiTheme current=(GuiTheme)(Object)this;
        if(module.category==BuilderToolboxAddon.CATEGORY && !(current instanceof ToolboxGuiTheme))
            cir.setReturnValue(new ModuleScreen(ToolboxGuiTheme.get(current),module));
    }
}
