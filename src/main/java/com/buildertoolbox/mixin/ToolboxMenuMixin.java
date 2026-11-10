package com.buildertoolbox.mixin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin({TitleScreen.class,GameMenuScreen.class})
public abstract class ToolboxMenuMixin extends Screen {
 protected ToolboxMenuMixin(Text title){super(title);}
 @Inject(method="init",at=@At("TAIL"))private void tools$menu(CallbackInfo ci){
  addDrawableChild(ButtonWidget.builder(Text.literal("Map Art Tools"),button->client.setScreen(new com.buildertoolbox.client.gui.screens.ToolboxScreen())).dimensions(6,6,120,20).build());
 }
}
