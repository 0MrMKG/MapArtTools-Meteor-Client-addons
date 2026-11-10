package com.buildertoolbox.fullauto;
import com.buildertoolbox.client.gui.GuiViewport;
public final class GuiViewportTest {
 public static void main(String[] args){
  for(int width:new int[]{640,1280,1920,3840})for(int gameScale=1;gameScale<=8;gameScale++){
   double logicalMouse=123.0/gameScale;
   double widgetMouse=GuiViewport.pointer(logicalMouse,gameScale);
   check(Math.abs(widgetMouse*GuiViewport.SCALE-123)<1e-8,"pointer and render transforms stay aligned across GUI scales");
   check(Math.abs(GuiViewport.size(width)*GuiViewport.SCALE-width)<GuiViewport.SCALE,"window layout follows pixel dimensions, not scaled screen width");
  }
  check(GuiViewport.viewHeight(1080)==GuiViewport.viewHeight(2160)
   && GuiViewport.viewHeight(2160)*GuiViewport.SCALE<=GuiViewport.MAX_VIEW_HEIGHT,"larger text must retain the physical settings viewport height limit");
  check(GuiViewport.viewHeight(100)>=30,"small windows keep a positive scroll viewport");
  System.out.println("GuiViewport: scale-independent size, compact settings and pointer round-trips passed.");
 }
 private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
