package com.buildertoolbox.utils;

/** Full-inventory and rollback boundaries, independent of a running Minecraft client. */
public final class CursorRecoveryPolicyTest {
    public static void main(String[] args) {
        for (int slot=0;slot<36;slot++) check(CursorRecoveryPolicy.playerDestination(slot,false),"normal player inventory remains usable");
        check(CursorRecoveryPolicy.playerDestination(40,true),"empty/compatible offhand is exposed by the player handler");
        check(!CursorRecoveryPolicy.playerDestination(40,false),"storage handlers must not invent an offhand slot");
        for (int slot:new int[]{-1,36,37,38,39,41}) check(!CursorRecoveryPolicy.playerDestination(slot,true),"crafting and armor are not cursor parking destinations");
        check(CursorRecoveryPolicy.progressed(false,true,64,32),"partial merge progress resets the retry budget");
        check(!CursorRecoveryPolicy.progressed(false,true,64,64),"server cursor rollback to the same count must not reset retries");
        check(!CursorRecoveryPolicy.progressed(false,true,32,64),"a growing rejected cursor is not progress");
        check(CursorRecoveryPolicy.progressed(true,true,64,64),"a different owned handler begins a distinct recovery context");
        check(CursorRecoveryPolicy.progressed(false,false,64,64),"a genuinely different item begins a new recovery");
        check(CursorRecoveryPolicy.canParkWholeHotbarStack(true,true,true,64,64),"an accepted full hotbar stack may fill an empty owned storage slot");
        check(!CursorRecoveryPolicy.canParkWholeHotbarStack(false,true,true,64,64),"never use a manually opened container");
        check(!CursorRecoveryPolicy.canParkWholeHotbarStack(true,false,true,64,64),"never park the wrong material in a registered container");
        check(!CursorRecoveryPolicy.canParkWholeHotbarStack(true,true,false,64,64),"never exchange another stack");
        check(!CursorRecoveryPolicy.canParkWholeHotbarStack(true,true,true,64,63),"never overfill or split the parked stack");
        check(!CursorRecoveryPolicy.canParkWholeHotbarStack(true,true,true,0,64),"an empty hotbar slot does not need a container swap");
        for (int slot=-1;slot<=41;slot++) for(boolean player:new boolean[]{false,true})
            check(CursorRecoveryPolicy.playerDestination(slot,player)
                ==com.buildertoolbox.fullauto.CursorRecoveryPolicy.playerDestination(slot,player),"independent policies agree");
        for(boolean owned:new boolean[]{false,true})for(boolean accepted:new boolean[]{false,true})for(boolean empty:new boolean[]{false,true})
            for(int count:new int[]{0,1,16,64})for(int capacity:new int[]{0,16,64})
                check(CursorRecoveryPolicy.canParkWholeHotbarStack(owned,accepted,empty,count,capacity)
                    ==com.buildertoolbox.fullauto.CursorRecoveryPolicy.canParkWholeHotbarStack(owned,accepted,empty,count,capacity),"independent whole-stack guards agree");
        System.out.println("CursorRecoveryPolicy: safe offhand, partial merge progress, bounded rollback budgets and owned-empty-storage whole-stack guards passed.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
