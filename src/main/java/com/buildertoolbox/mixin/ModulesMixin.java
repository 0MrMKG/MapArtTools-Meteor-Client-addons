package com.buildertoolbox.mixin;

import com.buildertoolbox.BuilderToolboxAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reapply the addon order after Meteor's global alphabetical sort, without changing other categories. */
@Mixin(value = Modules.class, remap = false)
public abstract class ModulesMixin {
    @Inject(method = "sortModules", at = @At("TAIL"))
    private void builderToolbox$keepMenuOrder(CallbackInfo ci) {
        BuilderToolboxAddon.applyMenuOrder((Modules) (Object) this);
    }
}
