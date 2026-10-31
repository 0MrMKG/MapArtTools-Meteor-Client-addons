package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;

/** Timing and movement scenarios for the three-minute supply recovery. */
public final class StationaryRestockWatchdogTest {
    public static void main(String[] args) throws Exception {
        var watchdog = new StationaryRestockWatchdog();
        long timeout = StationaryRestockWatchdog.TIMEOUT_TICKS;
        check(timeout == 3600,"three minutes at twenty client ticks per second");
        check(!watchdog.stalled(1,10,10,true),"first observation starts the timer");
        check(!watchdog.stalled(timeout,10.03,10.03,true),"tiny positional jitter is not progress");
        check(watchdog.stalled(timeout+1,10.03,10.03,true),"real stationary timeout recovers once");
        check(!watchdog.stalled(timeout+2,10.03,10.03,true),"recovery cannot run every tick");
        check(!watchdog.stalled(timeout+10,10.21,10,true),"moving about a fifth of a block resets the timer");
        check(!watchdog.stalled(2*timeout+9,10.21,10,true),"new position gets a full three-minute window");
        check(watchdog.stalled(2*timeout+10,10.21,10,true),"continued stalling can trigger a bounded second recovery");
        watchdog.reset();
        check(!watchdog.stalled(2*timeout+11,10.21,10,true),"confirmed material transfer resets the timer");
        check(!watchdog.stalled(3*timeout+10,10.21,10,false),"leaving restock cancels the watch");
        check(!watchdog.stalled(3*timeout+11,10.21,10,true),"new chest starts fresh");

        String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        String recovery=section(source,"private boolean recoverStationaryRestock()", "private void refreshSupplyAmount()");
        check(source.indexOf("if (recoverStationaryRestock())") < source.indexOf("if (batch.tick())"),
            "watchdog still runs when the batch controller consumes a tick");
        check(recovery.contains("handler == mc.player.playerScreenHandler || handler == ownedSupplyHandler")
            && recovery.contains("!autoEater.eating()"),"only owned restocking can be nudged");
        check(recovery.contains("restockTransferTick >= 0 || !handler.getCursorStack().isEmpty()")
            && recovery.contains("closeSupplyScreen()") && recovery.contains("findSupplyStandNear(supplyChest, 4)")
            && recovery.contains("deferSupplyChest("),
            "pending transfers are retained; owned chests reopen, then alternate stands/boxes are tried");
        String confirmedTake=section(source,"if (moved > 0 && tick - restockTransferTick >= confirmationTicks)",
            "if (tick - restockTransferTick < timeoutTicks)");
        check(confirmedTake.contains("stationaryRestock.reset();")
            && confirmedTake.contains("stationaryRestockRecoveries = 0;"),
            "a confirmed take clears the recovery budget");
        System.out.println("StationaryRestockWatchdog: three-minute timeout, movement/transfer reset and bounded supply recovery passed.");
    }
    private static String section(String text,String begin,String end){int a=text.indexOf(begin),b=text.indexOf(end,a+begin.length());check(a>=0&&b>a,"source section");return text.substring(a,b);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
