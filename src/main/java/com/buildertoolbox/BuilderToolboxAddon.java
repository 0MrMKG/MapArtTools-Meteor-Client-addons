package com.buildertoolbox;

import com.mojang.logging.LogUtils;
import com.buildertoolbox.modules.LitematicaPrinterModule;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

/** Entry point for the Builder Toolbox Meteor addon. */
public final class BuilderToolboxAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Builder Toolbox");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Builder Toolbox");
        Modules.get().add(new LitematicaPrinterModule());
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
