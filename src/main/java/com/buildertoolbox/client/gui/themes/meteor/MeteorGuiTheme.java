package com.buildertoolbox.client.gui.themes.meteor;
import com.buildertoolbox.client.gui.GuiTheme;import com.buildertoolbox.client.gui.utils.AlignmentX;import com.buildertoolbox.client.utils.render.color.Color;
/** Standalone theme values; actual Meteor widgets render these colors. */
public final class MeteorGuiTheme extends GuiTheme {
 public static final class Value<T>{private final T value;public Value(T value){this.value=value;}public T get(){return value;}}
 public static final class States {private final Color normal,hover,pressed;public States(Color n,Color h,Color p){normal=n;hover=h;pressed=p;}public Color get(){return normal;}public Color get(boolean down,boolean over,boolean ignored){return get(down,over);} public Color get(boolean down,boolean over){return down?pressed:over?hover:normal;}}
 private static Value<Color> color(int r,int g,int b,int a){return new Value<>(new Color(r,g,b,a));}
 public final Value<Color> accentColor=color(35,125,70,255),checkboxColor=color(60,190,100,255),textColor=color(255,255,255,255),titleTextColor=color(255,255,255,255),moduleBackground=color(50,50,50,150),placeholderColor=color(170,170,170,150),textHighlightColor=color(45,170,85,110),plusColor=color(50,255,50,255),minusColor=color(255,50,50,255),separatorText=color(255,255,255,255),separatorCenter=color(255,255,255,255),separatorEdges=color(255,255,255,0),disableHoverColor=color(255,255,255,255);
 public final States backgroundColor=new States(new Color(20,20,20,200),new Color(30,30,30,200),new Color(40,40,40,200)),outlineColor=new States(new Color(0,0,0,255),new Color(30,30,30,255),new Color(60,60,60,255)),scrollbarColor=new States(new Color(45,170,85,255),new Color(65,195,110,255),new Color(30,145,70,255)),sliderLeft=scrollbarColor,sliderHandle=scrollbarColor,sliderRight=outlineColor;
 public final Value<AlignmentX> moduleAlignment=new Value<>(AlignmentX.Center);
}