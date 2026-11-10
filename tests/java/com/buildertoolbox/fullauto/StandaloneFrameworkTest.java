package com.buildertoolbox.fullauto;
import com.buildertoolbox.client.ClientRuntime;
import com.buildertoolbox.client.events.EventHandler;
import com.buildertoolbox.client.settings.IntSetting;
public final class StandaloneFrameworkTest {
 private static final class Listener {int calls; @EventHandler private void event(String value){calls++;ClientRuntime.EVENT_BUS.unsubscribe(this);}}
 public static void main(String[] args){
  var listener=new Listener();ClientRuntime.EVENT_BUS.subscribe(listener);ClientRuntime.EVENT_BUS.subscribe(listener);ClientRuntime.EVENT_BUS.post("tick");ClientRuntime.EVENT_BUS.post("tick");check(listener.calls==1,"private handlers, deduplication and unsubscription during dispatch");
  int[] calls={0};var setting=new IntSetting.Builder().name("Test").defaultValue(3).range(1,5).onChanged(v->calls[0]++).build();check(!setting.set(0)&&setting.get()==3,"invalid values cannot reach task logic");check(setting.set(4)&&calls[0]==1,"valid edit delivers callback");setting.set(4);check(calls[0]==1,"same value cannot recurse through change handlers");setting.reset();check(setting.get()==3&&calls[0]==2,"reset uses bounded default and delivers one callback");
  System.out.println("StandaloneFramework: event ownership, stop cleanup and bounded settings passed.");
 }
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
