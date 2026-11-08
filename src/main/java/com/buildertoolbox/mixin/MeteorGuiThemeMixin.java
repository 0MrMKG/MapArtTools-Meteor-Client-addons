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
        // Scale glyph vertices before Minecraft prepares/culls them. Applying
        // scale only at the final GPU flush leaves glyphs in unscaled screen
        // coordinates during clipping, dropping prefixes in nested panels.
        VanillaTextRenderer.INSTANCE.scaleIndividually = true;
        cir.setReturnValue(VanillaTextRenderer.INSTANCE);
    }
}
