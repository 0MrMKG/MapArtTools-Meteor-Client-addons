package com.buildertoolbox.client.gui.renderer;
import java.util.*;import net.minecraft.client.MinecraftClient;import net.minecraft.client.gui.DrawContext;import net.minecraft.text.Text;import com.buildertoolbox.client.gui.widgets.WWidget;import com.buildertoolbox.client.gui.renderer.packer.GuiTexture;import com.buildertoolbox.client.utils.render.color.Color;
/** DrawContext adapter for the retained Meteor widget rendering calls. */
public final class GuiRenderer {
 public static final GuiTexture TRIANGLE=new GuiTexture("triangle"),RESET=new GuiTexture("reset"),CIRCLE=new GuiTexture("circle");
 private final DrawContext context;private final Deque<int[]> scissors=new ArrayDeque<>();private final List<Runnable> after=new ArrayList<>();private double alpha=1;private String tip;private int mouseX,mouseY;
 public GuiRenderer(DrawContext c){context=c;}public void beginRender(Object...ignored){}public void endRender(){}public void render(){var copy=List.copyOf(after);after.clear();copy.forEach(Runnable::run);if(tip!=null){var font=MinecraftClient.getInstance().textRenderer;var lines=font.wrapLines(Text.literal(tip),Math.max(40,Math.min(320,com.buildertoolbox.client.utils.Utils.getWindowWidth()-20)));int w=lines.stream().mapToInt(font::getWidth).max().orElse(0)+8,h=lines.size()*9+8;int x=Math.max(0,Math.min(mouseX+10,com.buildertoolbox.client.utils.Utils.getWindowWidth()-w)),y=Math.max(0,Math.min(mouseY+10,com.buildertoolbox.client.utils.Utils.getWindowHeight()-h));context.fill(x,y,x+w,y+h,0xf0141414);for(int i=0;i<lines.size();i++)context.drawText(font,lines.get(i),x+4,y+4+i*9,0xffffffff,false);}}
 public void mouse(int x,int y){mouseX=x;mouseY=y;}public void setAlpha(double a){alpha=a;}public void absolutePost(Runnable action){after.add(action);} public void tooltip(String text){tip=text;}
 private int packed(Color c){return (int)(c.a*alpha)<<24|c.r<<16|c.g<<8|c.b;}
 public void quad(double x,double y,double w,double h,Color color){context.fill((int)x,(int)y,(int)Math.ceil(x+w),(int)Math.ceil(y+h),packed(color));}
 public void quad(double x,double y,double w,double h,Color a,Color b){quad(x,y,w,h,a,a,b,b);}
 public void quad(WWidget widget,Color color){quad(widget.x,widget.y,widget.width,widget.height,color);}
 public void quad(double x,double y,double w,double h,Color a,Color b,Color c,Color d){context.fillGradient((int)x,(int)y,(int)(x+w),(int)(y+h),packed(a),packed(c));}
 public void quad(double x,double y,double w,double h,GuiTexture texture,Color color){context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,texture.id(),(int)x,(int)y,0,0,(int)w,(int)h,texture.width(),texture.height(),texture.width(),texture.height(),packed(color));}
 public void rotatedQuad(double x,double y,double w,double h,double rotation,GuiTexture texture,Color color){var matrix=context.getMatrices();matrix.pushMatrix();matrix.translate((float)(x+w/2),(float)(y+h/2));matrix.rotate((float)Math.toRadians(rotation));quad(-w/2,-h/2,w,h,texture,color);matrix.popMatrix();}
 public double text(String text,double x,double y,Color color,boolean title){context.drawText(MinecraftClient.getInstance().textRenderer,text,(int)x,(int)y,packed(color),false);return MinecraftClient.getInstance().textRenderer.getWidth(text);}
 public void scissorStart(double x,double y,double w,double h){scissors.push(new int[]{(int)x,(int)y,(int)(x+w),(int)(y+h)});context.enableScissor((int)x,(int)y,(int)(x+w),(int)(y+h));}
 public void scissorEnd(){if(!scissors.isEmpty()){scissors.pop();context.disableScissor();}}
}