package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;

/** Input time versus displacement, including pauses and carpet-edge oscillation. */
public final class WalkingProgressTest {
    public static void main(String[] args) throws Exception {
        WalkingProgress progress=new WalkingProgress();
        progress.sample(0,0,0,false);
        for(int tick=1;tick<=11;tick++) progress.sample(tick,0,0,true);
        check(!progress.stalled(12),"do not react before bounded movement budget");
        for(int tick=12;tick<=90;tick++) progress.sample(tick,0,0,false);
        check(!progress.stalled(12),"confirmation/food waits do not count as failed movement");
        progress.sample(91,0,0,true);
        check(progress.stalled(12),"placement waits cannot erase previous blocked forward ticks");
        progress.sample(92,0.2,0,false);
        check(!progress.stalled(12),"real displacement clears old stall");
        progress.reset();progress.sample(0,-64,-64,false);
        for(int tick=1;tick<=20;tick++) progress.sample(tick,-64+(tick%2==0?0.035:-0.035),-64,true);
        check(progress.stalled(12),"small lip jitter must not fake progress");
        progress.reset();progress.sample(0,0,0,false);
        for(int tick=1;tick<=100;tick++) {
            progress.sample(tick,tick*0.02,0,true);
            check(!progress.stalled(12),"slow genuine walking accumulates meaningful displacement");
        }
        progress.reset();progress.sample(0,0,0,false);progress.sample(1000,0,0,true);
        check(!progress.stalled(12),"an unobserved/offline gap is not hundreds of walking ticks");
        check(MapArtNavigationPolicy.deferCarpetAhead(10.5,20.5,0.6,11,20,1),"bare floor: leave immediate forward center cell open");
        check(MapArtNavigationPolicy.deferCarpetAhead(10.5,20.5,0.6,13,20,1),"do not build a fresh lip farther along the same walking corridor either");
        check(!MapArtNavigationPolicy.deferCarpetAhead(10.5,20.5,0.6,9,20,1),"fill center cells after walking past");
        check(!MapArtNavigationPolicy.deferCarpetAhead(10.5,20.5,0.6,11,18,1)
            && !MapArtNavigationPolicy.deferCarpetAhead(10.5,20.5,0.6,11,22,1),"outer rows remain eligible across all five rows");
        check(MapArtNavigationPolicy.deferCarpetAhead(-10.5,-20.5,0.6,-12,-21,-1),"reverse and negative coordinates defer the actual forward cell");
        check(!MapArtNavigationPolicy.deferCarpetAhead(-10.5,-20.5,0.6,-10,-21,-1),"reverse trailing cell is eligible");
        String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        check(source.contains("walkingProgress.sample(tick,mc.player.getX(),mc.player.getZ(),ownsForwardKey)"),"observe only owned forward input and real position");
        check(source.contains("navigationReleaseUntil = tick + 3;")
            && source.contains("nudgeUntil = navigationReleaseUntil + 12"),"release input before bounded escape movement");
        int stuck=source.indexOf("} else if (stuck) {");
        int nudge=source.indexOf("navigationNudge = candidate",stuck);
        check(stuck>=0 && source.indexOf("pathAttempts++;",stuck)<nudge
            && source.indexOf("pathAttempts > routeRetries.get()",stuck)<nudge,"nudge attempts cannot bypass the retry cap");
        check(source.contains("if (serviceNavigationRecovery()) return;")
            && source.contains("if (serviceNavigationRecovery()) return true;"),"printing and review finish escape before selecting nearby placements");
        check(source.contains("boolean bareForwardSweep = requiredLane != null && finishingLane < 0")
            && source.contains("MapArtNavigationPolicy.deferCarpetAhead("),"only the active sweep defers forward carpet; endpoint repairs can still finish it");
        String sprint=section(source,"private boolean shouldSprintToSupply(","private boolean planPath(");
        String setting=section(source,"private final Setting<Boolean> sprintToSupply", "private final Setting<Integer> pathSearchLimit");
        check(setting.contains("sgNavigation.add(new BoolSetting.Builder()") && setting.contains(".defaultValue(true)")
            && setting.contains("if (!enabled) releaseSupplySprint()") && sprint.contains("if (!sprintToSupply.get()) return false;"),
            "navigation toggle defaults on, gates sprint, and releases owned sprint immediately when disabled");
        check(sprint.contains("state != TaskState.RESTOCK || returningFromRestock || supplyChest == null")
            && sprint.contains("navigationNudge != null") && sprint.contains("supplyOpenTick >= 0"),
            "sprint is outbound restocking only, never printing/returning/nudging/opening");
        check(sprint.contains("horizontalDistanceSquared(supplyChest) > 36.0")
            && sprint.contains("horizontalDistanceSquared(next) > 9.0") && sprint.contains("canWalkDirectlyTo(next)")
            && sprint.contains("isOnGround()") && sprint.contains("isTouchingWater()") && sprint.contains("horizontalCollision"),
            "sprint brakes before the chest and short turns; unsafe movement falls back to walking");
        String input=section(source,"private void pressForward()","private static void setKeyPressed(");
        check(input.contains("pressForward(false)") && source.contains("pressForward(shouldSprintToSupply(next))")
            && input.contains("setKeyPressed(mc.options.forwardKey, true)")
            && !source.contains("walkPace") && !source.contains("Walk Speed (%)"),
            "only navigation opts into sprint; normal walking has no obsolete pacing slider or pulse throttle");
        check(section(source,"private void releaseMovement()","private static void setKeyPressed(").contains("releaseSupplySprint()")
            && input.contains("if (!ownsSupplySprintKey) return;")
            && input.contains("setKeyPressed(mc.options.sprintKey, false)"),
            "all movement stops release the owned sprint input without claiming a manual sprint key");
        System.out.println("WalkingProgress: stalls, jitter, slow travel, placement pauses, reset and bounded recovery source guards passed.");
    }
    private static String section(String code,String from,String to){int a=code.indexOf(from),b=code.indexOf(to,a+from.length());check(a>=0&&b>a,"source section");return code.substring(a,b);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
