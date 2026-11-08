package com.buildertoolbox;

import com.mojang.logging.LogUtils;
import com.buildertoolbox.modules.LitematicaMapArtModule;
import com.buildertoolbox.modules.FullAutoMapArtModule;
import com.buildertoolbox.fullauto.MapArtModeGuard;
import com.buildertoolbox.modules.ChestOrganizerModule;
import com.buildertoolbox.modules.AutoCraftModule;
import java.lang.reflect.Field;
import java.util.Comparator;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/** Entry point for the Builder Toolbox Meteor addon. */
public final class BuilderToolboxAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Builder Toolbox");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Builder Toolbox");
        LitematicaMapArtModule once = new LitematicaMapArtModule();
        setMenuTitle(once, "Map Art Maker（Once） [单次地图画制作]");
        Modules.get().add(once);
        FullAutoMapArtModule fullAuto = new FullAutoMapArtModule();
        setMenuTitle(fullAuto, "Map Art Maker（Full Mode） [全自动地图画制作]");
        Modules.get().add(fullAuto);
        ChestOrganizerModule organizer = new ChestOrganizerModule();
        setMenuTitle(organizer, "Chest Organizer [箱子整理]");
        Modules.get().add(organizer);
        AutoCraftModule craft = new AutoCraftModule();
        setMenuTitle(craft, "Auto Craft [自动合成]");
        Modules.get().add(craft);
        applyMenuOrder(Modules.get());
        MapArtModeGuard.install();
    }

    /** Meteor alphabetizes groups at startup; keep only our category in its requested order. */
    public static void applyMenuOrder(Modules modules) {
        modules.getGroup(CATEGORY).sort(Comparator.comparingInt(BuilderToolboxAddon::menuOrder));
    }

    private static int menuOrder(Module module) {
        if (module instanceof LitematicaMapArtModule) return 0;
        if (module instanceof FullAutoMapArtModule) return 1;
        if (module instanceof ChestOrganizerModule) return 2;
        if (module instanceof AutoCraftModule) return 3;
        return Integer.MAX_VALUE;
    }

    private static void setMenuTitle(Module module, String title) {
        com.buildertoolbox.utils.ChineseSettings.apply(module);
        // Meteor persists modules by `name`, not `title`. Keep the old name
        // (and its saved settings/keybinds) while changing only the UI label.
        try {
            Field field = Module.class.getDeclaredField("title");
            field.setAccessible(true);
            field.set(module, title);
        } catch (ReflectiveOperationException exception) {
            LOG.warn("Could not set menu title for {}", module.name, exception);
        }
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.buildertoolbox";
    }
}
