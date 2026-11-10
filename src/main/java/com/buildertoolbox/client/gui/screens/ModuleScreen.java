package com.buildertoolbox.client.gui.screens;
import com.buildertoolbox.client.gui.WidgetScreen;
import com.buildertoolbox.client.systems.modules.Module;
import com.buildertoolbox.client.settings.Setting;
import com.buildertoolbox.utils.ChineseSettings;
import com.buildertoolbox.client.gui.widgets.WWidget;
import com.buildertoolbox.client.gui.widgets.containers.WTable;
public final class ModuleScreen extends WidgetScreen {
 private final Module module;public ModuleScreen(Module module){super(module.title);this.module=module;}
 @Override protected void initWidgets(){var window=theme.window(module.title);window.id="module-"+module.name;window.setExpanded(theme.getWindowConfig(window.id).expanded);window.view.maxHeight=com.buildertoolbox.client.gui.GuiViewport.viewHeight(client.getWindow().getFramebufferHeight());add(window);
 for(var group:module.settings){var section=window.add(theme.section(ChineseSettings.groupTitle(group),true)).expandX().widget();var table=section.add(theme.table()).expandX().widget();for(var setting:group){if(!setting.isVisible())continue;var label=table.add(theme.label(setting.title)).widget();label.tooltip=setting.description;var editor=editor(setting);editor.tooltip=setting.description;if(setting.get() instanceof Boolean)table.add(editor);else table.add(editor).expandX();table.add(theme.button("↺")).widget().action=()->{setting.reset();reload();};table.row();}}
 WWidget custom=module.getWidget(theme);if(custom!=null)window.add(custom).expandX();
 var footer=window.add(theme.horizontalList()).expandX().widget();footer.add(theme.label("启用功能"));var active=footer.add(theme.checkbox(module.isActive())).widget();active.action=()->{if(active.checked!=module.isActive())module.toggle();active.checked=module.isActive();};
 }
 @SuppressWarnings({"rawtypes","unchecked"})private WWidget editor(Setting setting){Object value=setting.get();
 if(value instanceof java.util.List){var table=theme.table();com.buildertoolbox.client.gui.StringListEditor.fillTable(theme,table,setting);return table;}
 if(value instanceof Boolean b){var box=theme.checkbox(b);box.action=()->{setting.set(box.checked);if(client.currentScreen==this)reload();};return box;}
 if(value instanceof Integer n){var edit=theme.intEdit(n,(int)Math.max(Integer.MIN_VALUE,setting.min),(int)Math.min(Integer.MAX_VALUE,setting.max),(int)setting.sliderMin,(int)setting.sliderMax,!setting.slider);edit.small=true;edit.action=()->setting.set(edit.get());return edit;}
 if(value instanceof Double n){var edit=theme.doubleEdit(n,setting.min,setting.max,setting.sliderMin,setting.sliderMax,!setting.slider);edit.small=true;edit.action=()->setting.set(edit.get());return edit;}
 if(value instanceof Enum e){var dropdown=theme.dropdown(e.getDeclaringClass().getEnumConstants(),e);dropdown.action=()->setting.set(dropdown.get());return dropdown;}
 var box=theme.textBox(com.buildertoolbox.client.Config.display(setting));box.minWidth=170;box.action=()->{try{com.buildertoolbox.client.Config.edit(setting,box.get());}catch(RuntimeException ignored){}};return box;
 }
 @Override public void close(){client.setScreen(new ToolboxScreen());}
}
