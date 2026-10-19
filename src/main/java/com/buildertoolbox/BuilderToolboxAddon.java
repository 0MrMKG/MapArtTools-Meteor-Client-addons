package com.buildertoolbox;

import com.mojang.logging.LogUtils;
import com.buildertoolbox.modules.LitematicaPrinterModule;
import com.buildertoolbox.modules.LitematicaMapArtModule;
import com.buildertoolbox.modules.FullAutoMapArtModule;
import com.buildertoolbox.fullauto.MapArtModeGuard;
import com.buildertoolbox.modules.ChestOrganizerModule;
import com.buildertoolbox.modules.UnderfootScaffoldModule;
import com.buildertoolbox.modules.AutoCraftModule;
import java.lang.reflect.Field;
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
        LitematicaPrinterModule printer = new LitematicaPrinterModule();
        setMenuTitle(printer, "Schematic Printer");
        Modules.get().add(printer);
        Modules.get().add(new LitematicaMapArtModule());
        FullAutoMapArtModule fullAuto = new FullAutoMapArtModule();
        setMenuTitle(fullAuto, "map-artmaker-all-auto");
        Modules.get().add(fullAuto);
        Modules.get().add(new ChestOrganizerModule());
        Modules.get().add(new UnderfootScaffoldModule());
        Modules.get().add(new AutoCraftModule());
        MapArtModeGuard.install();
    }

    private static void setMenuTitle(Module module, String title) {
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
