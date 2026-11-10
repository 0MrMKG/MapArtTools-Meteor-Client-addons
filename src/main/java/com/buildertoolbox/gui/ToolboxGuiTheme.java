package com.buildertoolbox.gui;

import com.buildertoolbox.fullauto.CanvasAxisDirection;
import com.buildertoolbox.utils.RoundedRectGeometry;
import meteordevelopment.meteorclient.gui.DefaultSettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorWidget;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.*;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.input.*;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.*;
import meteordevelopment.meteorclient.gui.utils.CharFilter;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.*;
import meteordevelopment.meteorclient.gui.widgets.input.*;
import meteordevelopment.meteorclient.gui.widgets.pressable.*;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/** A private native Meteor theme for this addon's screens; never replaces the user's global theme. */
public final class ToolboxGuiTheme extends MeteorGuiTheme {
    private static ToolboxGuiTheme instance;
    private static final Color WINDOW=rgb(0x181825), SURFACE=rgb(0x1e1e2e), HEADER=rgb(0x242436);
    private static final Color ACCENT=rgb(0xa6e3a1), TEXT=rgb(0xcdd6f4), OUTLINE=rgb(0x45475a);

    public static ToolboxGuiTheme get(GuiTheme original) {
        if(instance==null)instance=new ToolboxGuiTheme();
        if(original instanceof MeteorGuiTheme meteor && original!=instance)instance.scale.set(meteor.scale.get());
        return instance;
    }

    private ToolboxGuiTheme() {
        accentColor.set(color(0xa6e3a1));checkboxColor.set(color(0xa6e3a1));plusColor.set(color(0xa6e3a1));
        minusColor.set(color(0xf38ba8));favoriteColor.set(color(0xa6e3a1));
        textColor.set(color(0xcdd6f4));titleTextColor.set(color(0xa6e3a1));textSecondaryColor.set(color(0xa6adc8));
        placeholderColor.set(color(0x7f849c));textHighlightColor.set(new SettingColor(166,227,161,65));
        moduleBackground.set(new SettingColor(166,227,161,44));
        separatorText.set(color(0xa6adc8));separatorCenter.set(color(0x45475a));separatorEdges.set(color(0x313244));
        states(backgroundColor,0x313244,0x45475a,0x585b70);
        states(outlineColor,0x45475a,0x89b88a,0xa6e3a1);
        states(scrollbarColor,0x585b70,0x7f849c,0xa6e3a1);
        states(sliderHandle,0xa6e3a1,0xa6e3a1,0x94e2d5);
        sliderLeft.set(color(0xa6e3a1));sliderRight.set(color(0x45475a));
        settingsFactory=new ToolboxSettingsFactory(this);
    }
    private static Color rgb(int hex) {return color(hex);}
    private static SettingColor color(int hex) {return new SettingColor(hex>>16&255,hex>>8&255,hex&255,255);}
    private static void states(ThreeStateColorSetting state,int normal,int hover,int pressed) {
        state.get(false,false).set(color(normal));state.get(false,true).set(color(hover));state.get(true,false).set(color(pressed));
    }
    private static Color blend(Color a,Color b,double t) {
        t=Math.clamp(t,0,1);return new Color((int)Math.round(a.r+(b.r-a.r)*t),(int)Math.round(a.g+(b.g-a.g)*t),
            (int)Math.round(a.b+(b.b-a.b)*t),(int)Math.round(a.a+(b.a-a.a)*t));
    }
    private static double approach(double value,double target,double delta) {
        double next=value+(target-value)*(1-Math.exp(-Math.max(0,delta)*14));return Math.abs(next-target)<0.001?target:next;
    }
    private static void rounded(GuiRenderer r,double x,double y,double w,double h,double radius,Color color) {
        if(w<=0||h<=0)return;
        int left=(int)Math.round(x),top=(int)Math.round(y);
        int width=(int)Math.round(x+w)-left,height=(int)Math.round(y+h)-top;
        for(var span:RoundedRectGeometry.spans(width,height,(int)Math.round(radius),true,true))
            r.quad(left+span.inset(),top+span.y(),span.width(),span.height(),color);
    }
    private static void outlined(GuiRenderer r,WWidget widget,Color fill,Color border) {
        double radius=widget.theme.scale(3),line=widget.theme.scale(1);
        rounded(r,widget.x,widget.y,widget.width,widget.height,radius,border);
        rounded(r,widget.x+line,widget.y+line,widget.width-line*2,widget.height-line*2,Math.max(0,radius-line),fill);
    }
    private interface RoundedWidget extends MeteorWidget {
        @Override default void renderBackground(GuiRenderer r,WWidget widget,Color outline,Color background) {outlined(r,widget,background,outline);}
    }
    @Override public WWindow window(WWidget icon,String title) {return w(new ToolboxWindow(icon,title));}
    @Override protected WButton button(String text,GuiTexture texture) {return w(new Button(text,texture));}
    @Override public WCheckbox checkbox(boolean checked) {return w(new Checkbox(checked));}
    @Override public WSlider slider(double value,double min,double max) {return w(new Slider(value,min,max));}
    @Override public WTextBox textBox(String text,String placeholder,CharFilter filter,Class<? extends WTextBox.Renderer> renderer) {
        return w(new TextBox(text,placeholder,filter,renderer));
    }
    @Override public <T> WDropdown<T> dropdown(T[] values,T selected) {return w(new Dropdown<>(values,selected));}
    @Override public WSection section(String title,boolean expanded,WWidget headerWidget) {return w(new Section(title,expanded,headerWidget));}
    @Override public WView view() {return w(new View());}
    @Override public WWidget module(Module module,String title) {return w(new ToolboxModule(module,title));}

    public static final class ToolboxWindow extends WMeteorWindow {
        public ToolboxWindow(WWidget icon,String title) {super(icon,title);}
        @Override protected WHeader header(WWidget icon) {return new Header(icon);}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            double h=expanded||animProgress>0?height:header.height,radius=theme.scale(6);
            for(int spread=3;spread>=1;spread--) {
                double s=theme.scale(spread);
                rounded(r,x-s,y-s+theme.scale(1),width+s*2,h+s*2,radius+s,new Color(17,17,27,22));
            }
            rounded(r,x,y,width,h,radius,WINDOW);
        }
        private class Header extends WHeader {
            Header(WWidget icon) {super(icon);}
            @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
                rounded(r,x,y,width,height,theme.scale(5),HEADER);
                rounded(r,x+theme.scale(5),y+theme.scale(5),theme.scale(2),height-theme.scale(10),theme.scale(1),ACCENT);
            }
        }
    }
    public static final class ToolboxModule extends WMeteorModule {
        private final Module owner;private final String caption;private double hover,active;
        public ToolboxModule(Module module,String title) {super(module,title);owner=module;caption=title;active=module.isActive()?1:0;}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            hover=approach(hover,mouseOver?1:0,delta);active=approach(active,owner.isActive()?1:0,delta);
            double mix=Math.min(1,active*0.75+hover*0.35);
            if(mix>0)rounded(r,x,y,width,height,theme.scale(3),new Color(166,227,161,(int)(55*mix)));
            if(active>0)rounded(r,x+theme.scale(2),y+theme.scale(3),theme.scale(2),height-theme.scale(6),theme.scale(1),blend(WINDOW,ACCENT,active));
            r.text(caption,x+(width-theme.textWidth(caption))/2,y+(height-theme.textHeight())/2,blend(TEXT,ACCENT,active),false);
        }
    }
    private static final class Button extends WMeteorButton implements RoundedWidget {
        private double hover;
        Button(String text,GuiTexture texture) {super(text,texture);}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            hover=approach(hover,mouseOver?1:0,delta);
            var t=theme();outlined(r,this,blend(t.backgroundColor.get(),t.backgroundColor.get(pressed,true),pressed?1:hover),
                blend(t.outlineColor.get(),ACCENT,pressed?1:hover*0.7));
            if(text!=null)r.text(text,x+(width-textWidth)/2,y+pad(),t.textColor.get(),false);
            else r.quad(x+(width-theme.textHeight())/2,y+pad(),theme.textHeight(),theme.textHeight(),texture,t.textColor.get());
        }
    }
    private static final class Checkbox extends WMeteorCheckbox implements RoundedWidget {
        private double progress;
        Checkbox(boolean checked) {super(checked);progress=checked?1:0;}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            progress=approach(progress,checked?1:0,delta);
            outlined(r,this,blend(theme().backgroundColor.get(pressed,mouseOver),ACCENT,progress),theme().outlineColor.get(pressed,mouseOver));
            if(progress>0.45) {
                double s=theme.scale(1),cx=x+width/2,cy=y+height/2;
                for(int i=0;i<3;i++)r.quad(cx+(-4+i)*s,cy+(i-1)*s,s,2*s,WINDOW);
                for(int i=0;i<5;i++)r.quad(cx+(-2+i)*s,cy+(1-i)*s,s,2*s,WINDOW);
            }
        }
    }
    private static final class Slider extends WMeteorSlider {
        Slider(double value,double min,double max) {super(value,min,max);}
        @Override protected double handleSize() {return theme.textHeight()*1.3;}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            double handle=handleSize(),usable=Math.max(0,width-handle),value=Math.clamp(valueWidth(),0,usable);
            double h=theme.scale(3),cx=x+value+handle/2,knob=theme.scale(dragging?2:4);
            rounded(r,x+handle/2,y+height/2-h/2,usable,h,h/2,OUTLINE);
            rounded(r,x+handle/2,y+height/2-h/2,value,h,h/2,ACCENT);
            rounded(r,cx-knob/2,y,knob,height,knob/2,ACCENT);
        }
    }
    private static final class TextBox extends WMeteorTextBox implements RoundedWidget {
        TextBox(String text,String placeholder,CharFilter filter,Class<? extends WTextBox.Renderer> renderer) {super(text,placeholder,filter,renderer);}
        @Override public void renderBackground(GuiRenderer r,WWidget widget,boolean pressed,boolean over) {
            outlined(r,widget,theme().backgroundColor.get(false,over),theme().outlineColor.get(focused,over));
        }
    }
    private static final class Dropdown<T> extends WMeteorDropdown<T> implements RoundedWidget {Dropdown(T[] values,T value){super(values,value);}}
    private static final class View extends WMeteorView {
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {
            if(canScroll&&hasScrollBar)rounded(r,handleX(),handleY(),handleWidth(),handleHeight(),handleWidth()/2,theme().scrollbarColor.get(focused,handleMouseOver));
        }
    }
    private static final class Section extends WMeteorSection {
        Section(String title,boolean expanded,WWidget widget) {super(title,expanded,widget);}
        @Override protected void onRender(GuiRenderer r,double mx,double my,double delta) {outlined(r,this,SURFACE,rgb(0x313244));}
    }
    private static final class ToolboxSettingsFactory extends DefaultSettingsWidgetFactory {
        ToolboxSettingsFactory(GuiTheme theme) {
            super(theme);Factory original=factories.get(EnumSetting.class);
            factories.put(EnumSetting.class,(table,setting)->{
                if(!(setting.get() instanceof CanvasAxisDirection)){original.create(table,setting);return;}
                directionButton(table,setting);
            });
        }
        @SuppressWarnings("unchecked") private void directionButton(WTable table,Setting<?> raw) {
            Setting<CanvasAxisDirection> setting=(Setting<CanvasAxisDirection>)raw;
            String axis=setting.name.equals("Canvas X Direction")?"X":"Z";
            WButton button=table.add(theme.button(setting.get().buttonLabel(axis))).expandCellX().widget();
            button.tooltip=setting.description;
            button.action=()->{setting.set(setting.get().next());button.set(setting.get().buttonLabel(axis));};
            WButton reset=table.add(theme.button(GuiRenderer.RESET)).widget();reset.tooltip="重置为默认值";
            reset.action=()->{setting.reset();button.set(setting.get().buttonLabel(axis));};
        }
    }
}
