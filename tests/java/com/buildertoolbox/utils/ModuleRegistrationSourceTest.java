package com.buildertoolbox.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Source registration/lifecycle guard; does not emulate Meteor activation or live rotation callbacks. */
public final class ModuleRegistrationSourceTest {
    public static void main(String[] args) throws Exception {
        String root = "src/main/java/com/buildertoolbox/";
        String addon = read(root + "BuilderToolboxAddon.java");
        String mapArt = read(root + "modules/LitematicaMapArtModule.java");
        String fullAuto = read(root + "modules/FullAutoMapArtModule.java");
        String engine = read(root + "modules/LitematicaPrinterModule.java");
        String registration = method(addon, "public void onInitialize()");
        long count = Pattern.compile("Modules\\.get\\(\\)\\.add\\(").matcher(registration).results().count();
        check(count == 4, "exactly four public modules must be registered after removing underfoot scaffold");
        int once = registration.indexOf("Modules.get().add(once)");
        int full = registration.indexOf("Modules.get().add(fullAuto)");
        int organizer = registration.indexOf("Modules.get().add(organizer)");
        int craft = registration.indexOf("Modules.get().add(craft)");
        check(once >= 0 && once < full && full < organizer && organizer < craft,
            "menu registration order must be Once, Full Mode, Chest Organizer, Auto Craft");
        check(registration.contains("applyMenuOrder(Modules.get())"), "initial registration must apply the explicit category order");
        check(!Files.exists(Path.of(root + "modules/UnderfootScaffoldModule.java"))
            && !addon.contains("UnderfootScaffoldModule"), "deleted scaffold must have no source or registration");
        String ordering = method(addon, "public static void applyMenuOrder(Modules modules)").replaceAll("\\s+", "");
        check(ordering.contains("modules.getGroup(CATEGORY).sort(Comparator.comparingInt(BuilderToolboxAddon::menuOrder))")
            && !ordering.contains("getAll(") && !ordering.contains("getGroups("),
            "explicit ordering must sort only the Builder Toolbox category");
        String ranks = method(addon, "private static int menuOrder(Module module)").replaceAll("\\s+", "");
        String[] classes = {"LitematicaMapArtModule", "FullAutoMapArtModule", "ChestOrganizerModule", "AutoCraftModule"};
        for (int rank=0;rank<classes.length;rank++)
            check(ranks.contains("if(moduleinstanceof" + classes[rank] + ")return" + rank + ";"),
                "wrong fixed menu rank for " + classes[rank]);
        check(ranks.contains("returnInteger.MAX_VALUE;"), "unknown addon modules must sort after the four fixed entries");
        String mixin = read(root + "mixin/ModulesMixin.java").replaceAll("\\s+", "");
        check(mixin.contains("@Mixin(value=Modules.class,remap=false)")
            && mixin.contains("@Inject(method=\"sortModules\",at=@At(\"TAIL\"))")
            && mixin.contains("BuilderToolboxAddon.applyMenuOrder((Modules)(Object)this)"),
            "Meteor alphabetical sorting must be followed by the addon order at the Modules mixin TAIL");
        String mixins = read("src/main/resources/builder_toolbox.mixins.json");
        check(mixins.contains("\"package\": \"com.buildertoolbox.mixin\"")
            && Pattern.compile("\"client\"\\s*:\\s*\\[[^]]*\"ModulesMixin\"", Pattern.DOTALL).matcher(mixins).find(),
            "the Modules ordering mixin must be enabled in the client mixin resource");
        check(!registration.contains("new LitematicaPrinterModule") && !registration.contains("add(printer)")
            && !registration.contains("Schematic Printer"), "the standalone schematic printer must not remain a menu entry");
        check(registration.contains("\"Map Art Maker（Once） [单次地图画制作]\"")
            && registration.contains("\"Map Art Maker（Full Mode） [全自动地图画制作]\"") && registration.contains("setMenuTitle("),
            "both map-art entries must use their requested display titles");
        check(mapArt.contains("super(BuilderToolboxAddon.CATEGORY, \"Map Art Maker\"")
            && fullAuto.contains("\"map-artmaker-all-auto\""), "renaming the menu must preserve saved internal module names");
        String normalized = mapArt.replaceAll("\\s+", "");
        check(normalized.contains("privatefinalLitematicaPrinterModuleprinter=newLitematicaPrinterModule();"),
            "ordinary map art must own a private unregistered placement engine");
        check(!mapArt.contains("Modules.get()") && !mapArt.contains("printer.isActive()")
            && !mapArt.contains("printer.toggle()") && !mapArt.contains("printer.onActivate()")
            && !mapArt.contains("subscribe(printer)"), "the hidden engine must never depend on registry lookup, activation or manual event subscription");
        check(method(mapArt, "public void onActivate()").contains("resetRuntime()"),
            "activation must reset ordinary worker and engine runtime");
        check(method(mapArt, "private void resetRuntime()").contains("printer.resetExternalPlacements()")
            && method(mapArt, "public void onDeactivate()").contains("printer.resetExternalPlacements()"),
            "activation reset and deactivation must invalidate all external rotation tokens");
        String reset = method(engine, "public void resetExternalPlacements()");
        check(reset.contains("queuedProjectionPlacements.clear()") && reset.contains("resetRuntime()")
            && reset.indexOf("queuedProjectionPlacements.clear()") < reset.indexOf("resetRuntime()"),
            "engine lifecycle hook must clear queued tokens before resetting other runtime state");
        check(engine.contains("if (queuedProjectionPlacements.get(immutablePos) != token) return;"),
            "cleared external tokens must prevent delayed callbacks from placing after stop");
        System.out.println("ModuleRegistrationSource: four menus, compatible display titles, private placement engine and stop/start token cleanup passed (source checks only).");
    }

    private static String read(String file) throws Exception { return Files.readString(Path.of(file), StandardCharsets.UTF_8); }
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "missing method: " + signature);
        int opening = source.indexOf('{', start);
        check(opening >= 0, "missing method body: " + signature);
        int depth = 1;
        for (int index=opening+1;index<source.length();index++) {
            if (source.charAt(index)=='{') depth++;
            else if (source.charAt(index)=='}' && --depth==0) return source.substring(opening,index+1);
        }
        throw new AssertionError("unterminated method: " + signature);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
