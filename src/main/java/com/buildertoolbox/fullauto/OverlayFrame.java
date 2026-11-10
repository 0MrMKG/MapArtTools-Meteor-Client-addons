package com.buildertoolbox.fullauto;

import java.util.List;

/** Immutable extraction result, usable even when the drawing context has no world state. */
public record OverlayFrame<T>(List<T> boxes, double cameraX, double cameraY, double cameraZ) {
    public OverlayFrame {
        boxes = List.copyOf(boxes);
    }

    public static <T> OverlayFrame<T> empty() {
        return new OverlayFrame<>(List.of(), 0, 0, 0);
    }
}
