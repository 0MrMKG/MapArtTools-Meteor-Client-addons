package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Pure inventory arithmetic plus explicit wiring checks; not a Minecraft integration test. */
public final class RoundTripSupplyTest {
    public static void main(String[] args) throws Exception {
        var both = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("white",300),repeat("red",80),repeat("blue",800)),
            Map.of("white",stock(20,64),"red",stock(0,0),"blue",stock(0,0)),8);
        equal(300,both.desired().get("white"),"outward demand covered");
        equal(80,both.desired().get("red"),"return demand covered");
        check(!both.demand().containsKey("blue"),"farther band cannot consume pair capacity");
        equal(280,both.desired().get("white")-20,"subtract existing inventory");

        var tight = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("white",500),repeat("red",10)),
            Map.of("white",stock(0,0),"red",stock(0,0)),2);
        equal(1,tight.extraSlots().get("white"),"outward color gets one slot");
        equal(1,tight.extraSlots().get("red"),"return-only color reserves slot before large outward patch");
        equal(64,tight.desired().get("white"),"capacity-limited outward quota");
        equal(10,tight.desired().get("red"),"small return quota is exact");

        var shared = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",60),repeat("a",70)),Map.of("a",stock(100,128)),1);
        equal(130,shared.desired().get("a"),"same color aggregated across both legs");
        equal(30,shared.desired().get("a")-100,"shared stock counted once");
        var covered = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",10),repeat("a",20)),Map.of("a",stock(40,64)),0);
        check(covered.desired().get("a")<=40,"already sufficient bag requests nothing");
        var last = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",5)),Map.of("a",stock(0,0)),1);
        equal(5,last.desired().get("a"),"last single band supported");
        var unavailable = MapArtSupplyPlanner.planRoundTrip(List.of(repeat("a",500),repeat("b",20)),
            Map.of("a",new MapArtSupplyPlanner.Stock(0,0,64,true,true),"b",stock(0,0)),1);
        equal(20,unavailable.desired().get("b"),"empty color does not starve obtainable return color");
        for(var plan:List.of(both,tight,shared,covered,last,unavailable))
            for(var entry:plan.desired().entrySet())check(entry.getValue()<=plan.demand().get(entry.getKey()),"no speculative surplus quota");

        String source=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        check(source.contains("RESTOCK_MAX_LOOKAHEAD_LANES = 2")&&source.contains("planRoundTrip(materialBands, inventory, empty)"),"use paired demand in Full Mode");
        String snake=section(source,"private void tickSnakeWork()","private void tickSnakeTurn()");
        check(snake.contains("serviceSupplyWindowBoundary()")&&snake.contains("findStockedWindowTarget() == null"),"empty pair still has a fallback supply trigger");
        check(snake.contains("supplyCheckedLane != activeLane") && snake.contains("snakeEntryWaypoints.contains(snakeWaypointIndex)")
            && snake.contains("findRestockTargetInWindow(activeLane,!entering,!entering)"),"check entry deficits once and nearby exhausted colors during travel, even with other held colors");
        String shortage=section(source,"private ProjectionTarget findRestockTargetInWindow(Integer", "private boolean needsMaterial(");
        check(shortage.contains("target.lane() != requiredLane") && shortage.contains("nearbyOnly")
            && shortage.contains("(!onlyEmpty || held == 0)") && shortage.contains("hasUsableSupply(item, reserved)")
            && shortage.contains("inventoryCapacity(item, reserved) <= 0"),"mid-band resupply is scoped to nearby missing colors with a source and capacity");
        String boundary=section(source,"private boolean serviceSupplyWindowBoundary()","private boolean serviceOpeningBands()");
        check(!boundary.contains("snakeWaypointIndex =") && boundary.contains("supplyWindowLanes.clear()"),"pair completion does not replay whole bands for deferred holes");
        String demand=section(source,"private List<List<ProjectionTarget>> upcomingTargetBands()","private Map<Item, Integer> materialCounts(");
        check(demand.contains("for (int lane : supplyWindowLanes)")&&demand.contains("!needsMaterial(target)"),"include deferred first leg, exclude completed/wrong cells");
        check(demand.indexOf("byLane.put(active") < demand.indexOf("for (int lane : supplyWindowLanes)"),"current return leg leads its pair's ordering");
        System.out.println("RoundTripSupply: paired quotas, return-color reservation, partial/full inventory, last band, deferred supply and worker wiring passed.");
    }
    private static List<String> repeat(String item,int count){return Collections.nCopies(count,item);}
    private static MapArtSupplyPlanner.Stock stock(int count,int capacity){return new MapArtSupplyPlanner.Stock(count,capacity,64,true,false);}
    private static void equal(int expected,int actual,String message){check(expected==actual,message+": "+actual+" != "+expected);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static String section(String source,String start,String end){int a=source.indexOf(start),b=source.indexOf(end,a+start.length());check(a>=0&&b>a,"source section");return source.substring(a,b);}
}
