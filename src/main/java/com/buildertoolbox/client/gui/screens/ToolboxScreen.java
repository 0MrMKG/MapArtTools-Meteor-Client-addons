package com.buildertoolbox.client.gui.screens;
import com.buildertoolbox.client.gui.WidgetScreen;
import com.buildertoolbox.client.systems.modules.Modules;
import com.buildertoolbox.client.gui.themes.meteor.widgets.WMeteorModule;
public final class ToolboxScreen extends WidgetScreen {
 public ToolboxScreen(){super("Builder Toolbox");}
 @Override protected boolean categoryPanel(){return true;}
 @Override protected void initWidgets(){var window=theme.window("Builder Toolbox");window.id="builder-toolbox";window.setExpanded(theme.getWindowConfig(window.id).expanded);window.spacing=0;window.view.spacing=2;window.view.maxHeight=com.buildertoolbox.client.utils.Utils.getWindowHeight()-70;add(window);for(var module:Modules.get().getAll())window.add(new WMeteorModule(module,module.title)).expandX();}
}
