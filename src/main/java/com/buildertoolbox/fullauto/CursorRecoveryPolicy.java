package com.buildertoolbox.fullauto;

/** Pure decisions for cursor recovery; no slots may exchange unrelated stacks. */
public final class CursorRecoveryPolicy {
    private CursorRecoveryPolicy() { }
    public static boolean playerDestination(int inventoryIndex, boolean playerHandler) {
        return inventoryIndex >= 0 && inventoryIndex < 36 || playerHandler && inventoryIndex == 40;
    }
    public static boolean progressed(boolean handlerChanged, boolean sameItemAndComponents, int previousCount, int currentCount) {
        return handlerChanged || !sameItemAndComponents || currentCount < previousCount;
    }
    public static boolean canParkWholeHotbarStack(boolean ownedStorage, boolean acceptedMaterial,
                                                  boolean destinationEmpty, int sourceCount, int destinationCapacity) {
        return ownedStorage && acceptedMaterial && destinationEmpty
            && sourceCount > 0 && destinationCapacity >= sourceCount;
    }
}
