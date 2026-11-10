package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class OverlayFrameTest {
    public static void main(String[] args) throws Exception {
        var boxes = new ArrayList<>(List.of("registered chest"));
        var snapshot = new OverlayFrame<>(boxes, 10.25, 64, -3.5);
        boxes.clear();
        check(snapshot.boxes().equals(List.of("registered chest")), "drawing must not share the extraction list");
        check(snapshot.cameraX() == 10.25 && snapshot.cameraY() == 64 && snapshot.cameraZ() == -3.5,
            "camera position travels with the same extracted frame");
        try { snapshot.boxes().clear(); throw new AssertionError("mutable render snapshot"); }
        catch (UnsupportedOperationException expected) { }
        check(OverlayFrame.empty().boxes().isEmpty(), "startup and disconnect have a safe empty frame");

        String source = Files.readString(Path.of("src/main/java/com/buildertoolbox/client/StandaloneClient.java"));
        String draw = source.substring(source.indexOf("WorldRenderEvents.BEFORE_DEBUG_RENDER.register"),
            source.indexOf("public static boolean key("));
        check(!draw.contains("worldState()") && draw.contains("var snapshot = frame;")
            && draw.indexOf("snapshot.boxes().isEmpty()") < draw.indexOf("getBuffer("),
            "drawing never reads the nullable worldState, even on the first world frame");
        check(source.contains("GLFW.GLFW_KEY_RIGHT_CONTROL") && source.contains("mc.world == null || mc.player == null")
            && !source.contains("TitleScreen") && !source.contains("key==344"),
            "the panel is opened only in game with Right Ctrl");
        check(!Files.exists(Path.of("src/main/java/com/buildertoolbox/mixin/ToolboxMenuMixin.java"))
            && !Files.readString(Path.of("src/main/resources/builder_toolbox.mixins.json")).contains("ToolboxMenuMixin"),
            "no buttons are injected into the title or pause menus");
        System.out.println("OverlayFrame: immutable extraction, empty startup, nullable draw-state guard and game-only Right Ctrl entry passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
