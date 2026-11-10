package com.buildertoolbox.client.gui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.minecraft.text.Text;
import com.buildertoolbox.client.utils.Utils;
import com.buildertoolbox.client.gui.widgets.*;
import com.buildertoolbox.client.gui.widgets.containers.*;
import com.buildertoolbox.client.gui.utils.Cell;
import com.buildertoolbox.client.gui.renderer.GuiRenderer;
/** Vanilla screen/input adapter around Meteor's original widget tree. */
public abstract class WidgetScreen extends Screen {
 protected final GuiTheme theme=GuiThemes.get(); private Root root; private double lastX,lastY; private long lastFrame;
 protected WidgetScreen(String title){super(Text.literal(title));}
 protected abstract void initWidgets();protected boolean categoryPanel(){return false;}
 protected <T extends WWidget> Cell<T> add(T widget){return root.add(widget);}
 public void reload(){root.clear();initWidgets();root.invalidate();}
 @Override protected void init(){root=new Root();root.theme=theme;initWidgets();}
 @Override public void render(DrawContext context,int mx,int my,float frame){
  if(root.dirty){root.calculateSize();root.width=Math.min(root.width,Utils.getWindowWidth()-12);root.x=categoryPanel()?16:Math.max(6,(Utils.getWindowWidth()-root.width)/2);root.y=categoryPanel()?40:Math.max(8,(Utils.getWindowHeight()-root.height)/2);root.calculateWidgetPositions();root.dirty=false;}
  double zoom=1/GuiViewport.factor(client.getWindow().getScaleFactor());double px=mx*zoom,py=my*zoom;root.mouseMoved(px,py,lastX,lastY);lastX=px;lastY=py;
  context.getMatrices().pushMatrix();context.getMatrices().scale((float)(1/zoom));
  long now=System.nanoTime();double delta=lastFrame==0?0.05:Math.min(0.1,(now-lastFrame)/1e9);lastFrame=now;
  GuiRenderer renderer=new GuiRenderer(context);renderer.mouse((int)px,(int)py);root.render(renderer,px,py,delta);renderer.render();context.getMatrices().popMatrix();
 }
 @Override public boolean mouseClicked(Click click,boolean doubled){Click pixel=scaled(click);root.mouseMoved(pixel.x(),pixel.y(),lastX,lastY);return root.mouseClicked(pixel,doubled)||super.mouseClicked(click,doubled);}
 @Override public boolean mouseReleased(Click click){return root.mouseReleased(scaled(click))||super.mouseReleased(click);}
 @Override public void mouseMoved(double x,double y){double zoom=1/GuiViewport.factor(client.getWindow().getScaleFactor());root.mouseMoved(x*zoom,y*zoom,lastX,lastY);lastX=x*zoom;lastY=y*zoom;}
 @Override public boolean mouseScrolled(double x,double y,double h,double v){return root.mouseScrolled(v);}
 @Override public boolean keyPressed(KeyInput input){return root.keyPressed(input)||super.keyPressed(input);}
 @Override public boolean charTyped(CharInput input){return root.charTyped(input)||super.charTyped(input);}
 private Click scaled(Click click){double zoom=1/GuiViewport.factor(client.getWindow().getScaleFactor());return new Click(click.x()*zoom,click.y()*zoom,click.buttonInfo());}
 @Override public void renderBackground(DrawContext context,int mouseX,int mouseY,float delta){}
 @Override public boolean shouldPause(){return false;}
 @Override public void removed(){root.setFocused(false);com.buildertoolbox.client.Config.save();}
 private static final class Root extends WVerticalList implements WRoot {boolean dirty=true;@Override public void invalidate(){dirty=true;}}
}