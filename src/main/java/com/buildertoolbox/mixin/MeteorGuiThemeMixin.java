package com.buildertoolbox.mixin;

import com.buildertoolbox.utils.UnicodeFallbackTextRenderer;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MeteorGuiTheme.class, remap = false)
public abstract class MeteorGuiThemeMixin {
    @Inject(method = "textRenderer", at = @At("RETURN"), cancellable = true)
    private void builderToolbox$unicodeFallback(CallbackInfoReturnable<TextRenderer> cir) {
        cir.setReturnValue(UnicodeFallbackTextRenderer.wrap(cir.getReturnValue()));
    }
}
