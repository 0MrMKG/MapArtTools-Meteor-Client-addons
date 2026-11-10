package com.buildertoolbox.client.gui.renderer.packer;
import net.minecraft.util.Identifier;
public record GuiTexture(String name) {
 public Identifier id(){return Identifier.of("builder_toolbox","textures/gui/"+name+".png");}
 public int width(){return switch(name){case "circle" -> 64;case "triangle" -> 64;case "reset" -> 64;default->16;};}
 public int height(){return switch(name){case "circle" -> 64;case "triangle" -> 64;case "reset" -> 64;default->16;};}
}