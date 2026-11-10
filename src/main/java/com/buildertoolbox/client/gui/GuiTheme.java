package com.buildertoolbox.client.gui;
import java.util.*;
import com.buildertoolbox.client.gui.widgets.*;
import com.buildertoolbox.client.gui.widgets.containers.*;
import com.buildertoolbox.client.gui.widgets.input.*;
import com.buildertoolbox.client.gui.widgets.pressable.*;
import com.buildertoolbox.client.gui.themes.meteor.widgets.*;
import com.buildertoolbox.client.gui.themes.meteor.widgets.input.*;
import com.buildertoolbox.client.gui.themes.meteor.widgets.pressable.*;
import com.buildertoolbox.client.gui.utils.*;
import com.buildertoolbox.client.systems.modules.Module;
public class GuiTheme { public boolean disableHoverColor;
 public final Map<String,WindowConfig> windows=new HashMap<>();
 public <T extends WWidget>T init(T widget){widget.theme=this;widget.ensureInit();return widget;}
 public WVerticalList verticalList(){return init(new WVerticalList());} public WHorizontalList horizontalList(){return init(new WHorizontalList());}
 public WSection section(String text,boolean expanded){return init(new WMeteorSection(text,expanded,null));}
 public WWindow window(String text){return init(new WMeteorWindow(null,text));} public WView view(){return init(new WMeteorView());}
 public WLabel label(String text){return init(new WMeteorMultiLabel(text,false,330));} public WLabel label(String text,boolean title){return init(new WMeteorLabel(text,title));}
 public WButton button(String text){return init(new WMeteorButton(text,null));} public WCheckbox checkbox(boolean checked){return init(new WMeteorCheckbox(checked));}
 public WMinus minus(){return init(new WMeteorMinus());} public WPlus plus(){return init(new WMeteorPlus());} public WButton button(com.buildertoolbox.client.gui.renderer.packer.GuiTexture texture){return init(new WMeteorButton(null,texture));} public WHorizontalSeparator horizontalSeparator(){return horizontalSeparator(null);}
 public WTriangle triangle(){return init(new WMeteorTriangle());} public WHorizontalSeparator horizontalSeparator(String text){return init(new WMeteorHorizontalSeparator(text));}
 public WTable table(){return init(new WTable());} public WTextBox textBox(String text){return textBox(text,(s,c)->true);} public WTextBox textBox(String text,CharFilter filter){return init(new WMeteorTextBox(text,null,filter,null));}
 public WSlider slider(double value,double min,double max){return init(new WMeteorSlider(value,min,max));}
 public WIntEdit intEdit(int value,int min,int max,int sliderMin,int sliderMax,boolean noSlider){return init(new WIntEdit(value,min,max,sliderMin,sliderMax,noSlider));}
 public WDoubleEdit doubleEdit(double value,double min,double max,double sliderMin,double sliderMax,boolean noSlider){return init(new WDoubleEdit(value,min,max,sliderMin,sliderMax,2,noSlider));}
 public <T> WDropdown<T> dropdown(T[] values,T value){return init(new WMeteorDropdown<>(values,value));}
 public double scale(double value){return value;} public double pad(){return 4;} public double textHeight(){return 9;} public double textHeight(boolean title){return textHeight();}
 public double textWidth(String text){return textWidth(text,text.length(),false);} public double textWidth(String text,int length,boolean title){return net.minecraft.client.MinecraftClient.getInstance().textRenderer.getWidth(text.substring(0,Math.min(length,text.length())));}
 public WindowConfig getWindowConfig(String id){return windows.computeIfAbsent(id,k->new WindowConfig());}
 public net.minecraft.client.gui.screen.Screen moduleScreen(Module module){return new com.buildertoolbox.client.gui.screens.ModuleScreen(module);}
}