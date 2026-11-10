/*
 * Adapted from Meteor Client StringListSetting.fillTable.
 * Copyright (c) Meteor Development. GPL-3.0-only.
 * Standalone setting adapter modified on 2026-11-10.
 */
package com.buildertoolbox.client.gui;
import java.util.ArrayList;
import java.util.List;
import com.buildertoolbox.client.settings.Setting;
import com.buildertoolbox.client.gui.widgets.containers.WTable;
import com.buildertoolbox.client.gui.renderer.GuiRenderer;
public final class StringListEditor {
 private StringListEditor(){}
 public static void fillTable(GuiTheme theme,WTable table,Setting<List<String>> setting){
  table.clear();ArrayList<String> strings=new ArrayList<>(setting.get());
  for(int i=0;i<strings.size();i++){
   int index=i;var text=table.add(theme.textBox(strings.get(i))).expandX().minWidth(140).widget();
   text.action=()->strings.set(index,text.get());
   text.actionOnUnfocused=()->setting.set(new ArrayList<>(strings));
   var delete=table.add(theme.minus()).widget();delete.action=()->{strings.remove(index);setting.set(new ArrayList<>(strings));fillTable(theme,table,setting);};table.row();
  }
  if(!strings.isEmpty()){table.add(theme.horizontalSeparator()).expandX();table.row();}
  var add=table.add(theme.plus()).widget();add.tooltip="添加一项";add.action=()->{strings.add("");setting.set(new ArrayList<>(strings));fillTable(theme,table,setting);};
  var reset=table.add(theme.button(GuiRenderer.RESET)).widget();reset.tooltip="恢复默认值";reset.action=()->{setting.reset();fillTable(theme,table,setting);};
 }
}
