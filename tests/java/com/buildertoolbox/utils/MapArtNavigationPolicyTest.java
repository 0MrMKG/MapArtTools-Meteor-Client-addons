package com.buildertoolbox.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ground-only endpoint tolerance and regressions against idle movement/recovery loops. */
public final class MapArtNavigationPolicyTest {
    public static void main(String[] args) throws Exception {
        check(MapArtNavigationPolicy.reached(0.5,0,0,true,true,true),"half-block endpoint counts as arrived");
        check(MapArtNavigationPolicy.reached(-0.6,0,0.0625,true,true,true),"negative positions and carpet height retain endpoint tolerance");
        check(!MapArtNavigationPolicy.reached(0.5,0,0,true,true,false),"intermediate corners stay precise");
        check(MapArtNavigationPolicy.reached(0.19,0,0,true,true,false),"precise intermediate step");
        check(!MapArtNavigationPolicy.reached(0.61,0,0,true,true,true),"do not skip a distant endpoint");
        check(!MapArtNavigationPolicy.reached(0.45,0.45,0,true,true,true),"diagonal distance is not axis-only tolerance");
        check(!MapArtNavigationPolicy.reached(0.1,0,0.5,true,true,true),"wrong height is not arrived");
        check(!MapArtNavigationPolicy.reached(0.1,0,0,false,true,true),"airborne is not safely arrived");
        check(!MapArtNavigationPolicy.reached(0.1,0,0,true,false,true),"no floor or blocked destination is not arrived");
        check(!MapArtNavigationPolicy.reached(0.1,0,Double.NaN,true,true,true),"unknown floor is not success");
        for(double x:new double[]{-0.61,-0.6,-0.5,-0.2,0,0.2,0.5,0.6,0.61})for(boolean endpoint:new boolean[]{false,true})
            check(MapArtNavigationPolicy.reached(x,0,0.0625,true,true,endpoint)==com.buildertoolbox.fullauto.MapArtNavigationPolicy.reached(x,0,0.0625,true,true,endpoint),"both independent workers use the same arrival rules");
        for(String name:List.of("LitematicaMapArtModule","FullAutoMapArtModule")) {
            String code=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/"+name+".java"),StandardCharsets.UTF_8);
            int start=code.indexOf("private void tickReturnFromRestock()");
            int end=code.indexOf("private void finishReturnToWork()",start);
            String travel=code.substring(start,end);
            check(!travel.contains("placeTarget(")&&!travel.contains("selectReachableMatrixTarget("),name+" supply return must be movement-only");
            check(!code.contains("returnMaterialReserve")&&!code.contains("沿途铺设"),name+" must not consume band materials in transit");
            check(code.contains("hasReachedPathStep(path.get(pathIndex))"),name+" intermediate grid corners must stay precise");
            check(code.contains("!blockedStep.equals(goal)"),name+" must not poison the final destination on a stalled step");
            check(code.contains("unreachableSupplyUntil")&&code.contains("isSupplyChestUnavailable(pos)"),name+" must avoid repeated visits to failed boxes");
            check(code.contains("resumeStockedBand()"),name+" must continue another stocked band while resupply is unavailable");
            check(!code.contains("verificationPass++ >= 1"),name+" must not stop after one unsuccessful repair sweep");
            check(code.contains("footingRecoveryGoal")&&code.contains("placementStandSince >= 40"),name+" must recover from a persistent carpet-lip stand wait");
        }
        System.out.println("MapArtNavigationPolicy: half-block arrival, precise corners, safe heights, movement-only supply return and repeated recovery/sweep guards passed.");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
