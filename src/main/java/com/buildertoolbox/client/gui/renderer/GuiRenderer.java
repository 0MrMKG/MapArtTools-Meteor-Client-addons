package com.buildertoolbox.client.gui.renderer;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import com.buildertoolbox.client.gui.widgets.WWidget;
import com.buildertoolbox.client.gui.renderer.packer.GuiTexture;
import com.buildertoolbox.client.gui.themes.meteor.MeteorGuiTheme;
import com.buildertoolbox.client.gui.GuiThemes;
import com.buildertoolbox.client.utils.render.color.Color;
import com.buildertoolbox.utils.RoundedRectGeometry;

/** Vanilla DrawContext adapter; rounded surfaces also obey the widget tree's nested clipping. */
public final class GuiRenderer {
    public static final GuiTexture TRIANGLE=new GuiTexture("triangle"), RESET=new GuiTexture("reset"), CIRCLE=new GuiTexture("circle");
    private final DrawContext context;
    private final Deque<int[]> scissors=new ArrayDeque<>();
    private final List<Runnable> after=new ArrayList<>();
    private double alpha=1;
    private String tip;
    private int mouseX,mouseY;
    public GuiRenderer(DrawContext context) { this.context=context; }
    public void beginRender(Object... ignored) { }
    public void endRender() { }
    public void render() {
        var copy=List.copyOf(after); after.clear(); copy.forEach(Runnable::run);
        if(tip==null) return;
        var theme=(MeteorGuiTheme)GuiThemes.get();
        var font=MinecraftClient.getInstance().textRenderer;
        int viewportWidth=com.buildertoolbox.client.utils.Utils.getWindowWidth();
        int viewportHeight=com.buildertoolbox.client.utils.Utils.getWindowHeight();
        var lines=font.wrapLines(Text.literal(tip),Math.max(40,Math.min(300,viewportWidth-24)));
        int w=lines.stream().mapToInt(font::getWidth).max().orElse(0)+12,h=lines.size()*11+10;
        int x=Math.max(2,Math.min(mouseX+10,viewportWidth-w-2));
        int y=Math.max(2,Math.min(mouseY+10,viewportHeight-h-2));
        shadow(x,y,w,h,4,theme.shadowColor.get());
        roundedOutline(x,y,w,h,4,theme.windowColor.get(),theme.outlineColor.get());
        for(int i=0;i<lines.size();i++)context.drawText(font,lines.get(i),x+6,y+5+i*11,packed(theme.textColor.get()),false);
    }
    public void mouse(int x,int y) { mouseX=x;mouseY=y; }
    public void setAlpha(double value) { alpha=Math.clamp(value,0,1); }
    public void absolutePost(Runnable action) { after.add(action); }
    public void tooltip(String text) { tip=text; }
    private int packed(Color c) { return (int)(c.a*alpha)<<24|c.r<<16|c.g<<8|c.b; }
    public void quad(double x,double y,double w,double h,Color color) {
        if(w<=0||h<=0)return;
        context.fill((int)x,(int)y,(int)Math.ceil(x+w),(int)Math.ceil(y+h),packed(color));
    }
    public void quad(double x,double y,double w,double h,Color a,Color b) { quad(x,y,w,h,a,a,b,b); }
    public void quad(WWidget widget,Color color) { quad(widget.x,widget.y,widget.width,widget.height,color); }
    public void quad(double x,double y,double w,double h,Color a,Color b,Color c,Color d) {
        if(w<=0||h<=0)return;
        context.fillGradient((int)x,(int)y,(int)(x+w),(int)(y+h),packed(a),packed(c));
    }
    public void rounded(double x,double y,double w,double h,double radius,Color color) {
        rounded(x,y,w,h,radius,color,true,true);
    }
    public void rounded(double x,double y,double w,double h,double radius,Color color,boolean top,boolean bottom) {
        if(w<=0||h<=0||color.a==0)return;
        int left=(int)Math.round(x),upper=(int)Math.round(y);
        int width=(int)Math.round(x+w)-left,height=(int)Math.round(y+h)-upper;
        int packed=packed(color);
        for(var span:RoundedRectGeometry.spans(width,height,(int)Math.round(radius),top,bottom))
            context.fill(left+span.inset(),upper+span.y(),left+span.inset()+span.width(),upper+span.y()+span.height(),packed);
    }
    public void roundedOutline(double x,double y,double w,double h,double radius,Color fill,Color border) {
        rounded(x,y,w,h,radius,border);
        rounded(x+1,y+1,w-2,h-2,Math.max(0,radius-1),fill);
    }
    public void shadow(double x,double y,double w,double h,double radius,Color color) {
        for(int spread=3;spread>=1;spread--)
            rounded(x-spread,y-spread+1,w+spread*2,h+spread*2,radius+spread,
                new Color(color.r,color.g,color.b,Math.max(1,color.a/3)));
    }
    public void quad(double x,double y,double w,double h,GuiTexture texture,Color color) {
        if(w<=0||h<=0)return;
        context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,texture.id(),(int)x,(int)y,0,0,(int)w,(int)h,
            texture.width(),texture.height(),texture.width(),texture.height(),packed(color));
    }
    public void rotatedQuad(double x,double y,double w,double h,double rotation,GuiTexture texture,Color color) {
        var matrix=context.getMatrices();matrix.pushMatrix();matrix.translate((float)(x+w/2),(float)(y+h/2));
        matrix.rotate((float)Math.toRadians(rotation));quad(-w/2,-h/2,w,h,texture,color);matrix.popMatrix();
    }
    public double text(String text,double x,double y,Color color,boolean title) {
        context.drawText(MinecraftClient.getInstance().textRenderer,text,(int)x,(int)y,packed(color),false);
        return MinecraftClient.getInstance().textRenderer.getWidth(text);
    }
    public void scissorStart(double x,double y,double w,double h) {
        scissors.push(new int[]{(int)x,(int)y,(int)(x+w),(int)(y+h)});
        context.enableScissor((int)x,(int)y,(int)(x+w),(int)(y+h));
    }
    public void scissorEnd() { if(!scissors.isEmpty()){scissors.pop();context.disableScissor();} }
}
