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
    @Inject(method = "textRenderer", at = @At("HEAD"), cancellable = true)
    private void builderToolbox$unicodeFont(CallbackInfoReturnable<TextRenderer> cir) {
        // GuiRenderer begins/ends one renderer for the entire scissored batch.
        // Use that same Unicode renderer for both layout and every text draw,
        // including nested settings, tooltips and input fields.
        cir.setReturnValue(VanillaTextRenderer.INSTANCE);
    }
}
