package com.buildertoolbox.mixin;

import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.renderer.text.VanillaTextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MeteorGuiTheme.class, remap = false)
public abstract class MeteorGuiThemeMixin {
    @Inject(method="window",at=@At("HEAD"),cancellable=true)
    private void builderToolbox$categoryWindow(meteordevelopment.meteorclient.gui.widgets.WWidget icon,String title,
            CallbackInfoReturnable<meteordevelopment.meteorclient.gui.widgets.containers.WWindow> cir) {
        if("Builder Toolbox".equals(title) && !((Object)this instanceof com.buildertoolbox.gui.ToolboxGuiTheme))
            cir.setReturnValue(com.buildertoolbox.gui.ToolboxGuiTheme.get((MeteorGuiTheme)(Object)this).window(icon,title));
    }

    @Inject(method="module",at=@At("HEAD"),cancellable=true)
    private void builderToolbox$moduleStyle(meteordevelopment.meteorclient.systems.modules.Module module,String title,
            CallbackInfoReturnable<meteordevelopment.meteorclient.gui.widgets.WWidget> cir) {
        if(module.category==com.buildertoolbox.BuilderToolboxAddon.CATEGORY && !((Object)this instanceof com.buildertoolbox.gui.ToolboxGuiTheme))
            cir.setReturnValue(com.buildertoolbox.gui.ToolboxGuiTheme.get((MeteorGuiTheme)(Object)this).module(module,title));
    }

    @Inject(method = "textRenderer", at = @At("HEAD"), cancellable = true)
    private void builderToolbox$unicodeFont(CallbackInfoReturnable<TextRenderer> cir) {
        // Scale glyph vertices before Minecraft prepares/culls them. Applying
        // scale only at the final GPU flush leaves glyphs in unscaled screen
        // coordinates during clipping, dropping prefixes in nested panels.
        VanillaTextRenderer.INSTANCE.scaleIndividually = true;
        cir.setReturnValue(VanillaTextRenderer.INSTANCE);
    }
}
