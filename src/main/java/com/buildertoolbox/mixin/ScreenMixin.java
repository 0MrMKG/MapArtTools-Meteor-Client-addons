package com.buildertoolbox.mixin;

import com.buildertoolbox.modules.LitematicaMapArtModule;
import com.buildertoolbox.modules.FullAutoMapArtModule;
import com.buildertoolbox.client.systems.modules.Modules;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "shouldPause", at = @At("HEAD"), cancellable = true)
    private void builderToolbox$keepMapArtRunning(CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof GameMenuScreen)) return;
        Modules modules = Modules.get();
        if (modules == null) return;
        LitematicaMapArtModule mapArt = modules.get(LitematicaMapArtModule.class);
        if (mapArt != null && mapArt.runsWithGameMenu()) {
            cir.setReturnValue(false);
            return;
        }
        FullAutoMapArtModule fullAuto = modules.get(FullAutoMapArtModule.class);
        if (fullAuto != null && fullAuto.runsWithGameMenu()) cir.setReturnValue(false);
    }
}
