package com.buildertoolbox.client;
import java.lang.reflect.Method; import java.util.*; import com.buildertoolbox.client.events.EventHandler;
public final class ClientRuntime {
 public static final Bus EVENT_BUS=new Bus();
 public static final class Bus {
  private final Map<Object,List<Method>> handlers=new LinkedHashMap<>();
  public void subscribe(Object obj){if(handlers.containsKey(obj))return; List<Method> list=new ArrayList<>(); for(Class<?> c=obj.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.isAnnotationPresent(EventHandler.class)&&m.getParameterCount()==1){m.setAccessible(true);list.add(m);} handlers.put(obj,list);}
  public void unsubscribe(Object obj){handlers.remove(obj);}
  public <T> T post(T event){for(var entry:new ArrayList<>(handlers.entrySet())){if(!handlers.containsKey(entry.getKey()))continue; for(Method m:entry.getValue())if(m.getParameterTypes()[0].isInstance(event))try{m.invoke(entry.getKey(),event);}catch(ReflectiveOperationException e){throw new IllegalStateException("Automation event failed: "+m,e.getCause());}}return event;}
 }
}