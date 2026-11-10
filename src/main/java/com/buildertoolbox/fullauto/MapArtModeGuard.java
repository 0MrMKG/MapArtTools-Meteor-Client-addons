package com.buildertoolbox.fullauto;

import com.buildertoolbox.modules.FullAutoMapArtModule;
import com.buildertoolbox.modules.LitematicaMapArtModule;
import com.buildertoolbox.client.ClientRuntime;
import com.buildertoolbox.client.events.meteor.ActiveModulesChangedEvent;
import com.buildertoolbox.client.systems.modules.Modules;
import com.buildertoolbox.client.events.EventHandler;

/** Integration boundary only: the original module always keeps control. */
public final class MapArtModeGuard {
    private static final MapArtModeGuard INSTANCE = new MapArtModeGuard();
    private static boolean installed;
    private FullAutoMapArtModule running;

    private MapArtModeGuard() { }

    public static void install() {
        if (installed) return;
        ClientRuntime.EVENT_BUS.subscribe(INSTANCE);
        installed = true;
    }

    public static boolean begin(FullAutoMapArtModule owner) {
        var original = Modules.get().get(LitematicaMapArtModule.class);
        if (original != null && original.isActive()) return false;
        INSTANCE.running = owner;
        return true;
    }

    public static void end(FullAutoMapArtModule owner) {
        if (INSTANCE.running == owner) INSTANCE.running = null;
    }

    @EventHandler
    private void onActiveModulesChanged(ActiveModulesChangedEvent event) {
        FullAutoMapArtModule owner = running;
        if (owner == null || !owner.isActive()) return;
        var original = Modules.get().get(LitematicaMapArtModule.class);
        if (original == null || !original.isActive()) return;
        // Meteor posts this before the original's onActivate, so the fork
        // releases its keys/GUI pause override before the original captures
        // them. Never toggle the original or alter its settings or queues.
        owner.warning("原地图画模块已开启，全自动模式已先停止并释放控制。");
        owner.disable();
    }
}
