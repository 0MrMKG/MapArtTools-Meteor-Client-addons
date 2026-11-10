package com.buildertoolbox.client;

import com.buildertoolbox.client.events.game.GameLeftEvent;
import com.buildertoolbox.client.events.meteor.KeyEvent;
import com.buildertoolbox.client.events.render.Render3DEvent;
import com.buildertoolbox.client.events.world.TickEvent;
import com.buildertoolbox.client.gui.WidgetScreen;
import com.buildertoolbox.client.gui.screens.ToolboxScreen;
import com.buildertoolbox.client.systems.modules.Modules;
import com.buildertoolbox.client.utils.player.Rotations;
import com.buildertoolbox.fullauto.OverlayFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.shape.VoxelShapes;
import org.lwjgl.glfw.GLFW;

public final class StandaloneClient {
    private static volatile OverlayFrame<Render3DEvent.Box> frame = OverlayFrame.empty();
    private static int ticks;

    private StandaloneClient() { }

    public static void install() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> ClientRuntime.EVENT_BUS.post(new TickEvent.Pre()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            ClientRuntime.EVENT_BUS.post(new TickEvent.Post());
            if (++ticks % 200 == 0) Config.save();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            frame = OverlayFrame.empty();
            ClientRuntime.EVENT_BUS.post(new GameLeftEvent());
            for (var module : Modules.get().getAll()) module.disable();
            Rotations.clear();
            Config.save();
        });
        WorldRenderEvents.END_EXTRACTION.register(context -> {
            // Snapshot world-dependent data here. Some draw callbacks expose
            // no worldState, including the first frames after joining a world.
            var mc = MinecraftClient.getInstance();
            var camera = context.gameRenderer().getCamera();
            if (mc.world == null || mc.player == null || camera == null || !camera.isReady()) {
                frame = OverlayFrame.empty();
                return;
            }
            var boxes = ClientRuntime.EVENT_BUS.post(new Render3DEvent()).renderer.boxes;
            var position = camera.getCameraPos();
            frame = new OverlayFrame<>(boxes, position.x, position.y, position.z);
        });
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            var snapshot = frame;
            if (snapshot.boxes().isEmpty() || context.matrices() == null || context.consumers() == null) return;
            var vertices = context.consumers().getBuffer(RenderLayers.lines());
            for (var box : snapshot.boxes()) {
                var p = box.pos();
                VertexRendering.drawOutline(context.matrices(), vertices, VoxelShapes.fullCube(),
                    p.getX() - snapshot.cameraX(), p.getY() - snapshot.cameraY(), p.getZ() - snapshot.cameraZ(),
                    box.color(), 1.0f);
            }
        });
    }

    public static boolean key(int key, int action) {
        var event = ClientRuntime.EVENT_BUS.post(new KeyEvent(key, action));
        if (event.isCancelled()) return true;
        var mc = MinecraftClient.getInstance();
        if (key != GLFW.GLFW_KEY_RIGHT_CONTROL || action != GLFW.GLFW_PRESS || mc.world == null || mc.player == null) return false;
        if (mc.currentScreen instanceof WidgetScreen) {
            mc.setScreen(null);
            return true;
        }
        if (mc.currentScreen == null) {
            mc.setScreen(new ToolboxScreen());
            return true;
        }
        return false;
    }
}
