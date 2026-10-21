package com.buildertoolbox.fullauto;

/** Ownership requires our open request and the server's matching container receipt. */
public final class ContainerOwnershipPolicy {
    private ContainerOwnershipPolicy() { }

    public static boolean mayClaim(boolean requested, boolean hasTarget, boolean playerHandler,
                                   boolean expectedType, int syncId, int fromSyncId, int receivedSyncId) {
        return requested && hasTarget && !playerHandler && expectedType
            && syncId != fromSyncId && receivedSyncId == syncId;
    }

    public static boolean mayClose(boolean owned, boolean cursorEmpty) {
        return owned && cursorEmpty;
    }
}
