package com.buildertoolbox.client.gui;
/** Pixel-based UI dimensions, independent of Minecraft's GUI scale. */
public final class GuiViewport {
 public static final double SCALE=1.5;
 private GuiViewport(){}
 public static int size(int framebufferSize){return Math.max(1,(int)(framebufferSize/SCALE));}
 public static double factor(int gameScale){return SCALE/Math.max(1,gameScale);}
 public static double pointer(double coordinate,int gameScale){return coordinate/factor(gameScale);}
 public static int viewHeight(int framebufferHeight){return Math.max(30,Math.min(360,size(framebufferHeight)-70));}
}
