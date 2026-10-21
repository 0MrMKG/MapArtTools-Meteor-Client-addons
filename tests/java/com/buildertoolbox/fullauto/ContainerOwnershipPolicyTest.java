package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression checks for the pending-open/cursor ownership cycle. */
public final class ContainerOwnershipPolicyTest {
    public static void main(String[] args) throws Exception {
        check(ContainerOwnershipPolicy.mayClaim(true,true,false,true,7,0,7),"our acknowledged open request is owned before cursor recovery");
        check(!ContainerOwnershipPolicy.mayClaim(false,true,false,true,7,0,7),"manual containers are never claimed");
        check(!ContainerOwnershipPolicy.mayClaim(true,false,false,true,7,0,7),"a target must be registered for the request");
        check(!ContainerOwnershipPolicy.mayClaim(true,true,true,true,0,0,0),"the player handler is not a newly opened container");
        check(!ContainerOwnershipPolicy.mayClaim(true,true,false,false,7,0,7),"an unrelated screen type is not the expected response");
        check(!ContainerOwnershipPolicy.mayClaim(true,true,false,true,7,7,7),"an old handler cannot satisfy a new open request");
        check(!ContainerOwnershipPolicy.mayClaim(true,true,false,true,7,0,8),"another handler's server receipt does not transfer ownership");
        check(ContainerOwnershipPolicy.mayClose(true,true),"owned screens may close with an empty cursor");
        check(!ContainerOwnershipPolicy.mayClose(true,false),"closing with held cursor may drop items and is forbidden");
        check(!ContainerOwnershipPolicy.mayClose(false,true),"manual screens stay under player control");

        Path root=Path.of("src/main/java/com/buildertoolbox/fullauto");
        String controller=Files.readString(root.resolve("MapArtBatchController.java"));
        int tick=controller.indexOf("public boolean tick()");
        int claim=controller.indexOf("access.claimPendingHandler(tick)",tick);
        int cursor=controller.indexOf("cursorRecovery.tick(",tick);
        check(claim>tick&&claim<cursor,"claim pending server-owned handlers before the global cursor gate");
        check(controller.contains("cursorRecovery.onPacket(packet)"),"late server cursor corrections reach recovery");
        check(controller.contains("this::cursorStorageAllowed,host::batchCursorStorageAllowed"),"critical map ingredients cannot be unloaded as surplus color material");
        check(controller.contains("access.resetTravel()"),"recovered cursor invalidates stale travel paths without clearing cartography inputs");
        String access=Files.readString(root.resolve("BatchWorkstationAccess.java"));
        check(access.contains("recoveryOwned=current;recoveryPosition=target"),"reset preserves a screen it cannot safely close");
        check(access.contains("recoveryCandidate=current"),"a reset retains pending request provenance until its receipt arrives");
        check(access.contains("if(!canCloseSafely(recoveryOwned))return false"),"deferred screens cannot close until their cursor and returned inputs are safe");
        check(access.contains("pos.equals(recoveryPosition)&&expectedType(recoveryOwned,crafting)"),"recovery is tied to the same registered target and screen type");
        System.out.println("ContainerOwnershipPolicy: acknowledged open claims, manual-screen isolation, deferred close and cursor integration passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
