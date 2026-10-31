package com.buildertoolbox.fullauto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Nearest-source ordering, frozen quotas and finite visits across color changes. */
public final class SupplyTripTest {
    public static void main(String[] args) throws Exception {
        SupplyTrip<String,String> trip=new SupplyTrip<>();
        trip.freezeGoals(Map.of("red",128,"blue",64));
        var redA=new SupplyTrip.Source<>("red","A-red",1.0);
        var redB=new SupplyTrip.Source<>("red","B-red",10000.0);
        var blueA=new SupplyTrip.Source<>("blue","A-blue",4.0);
        var blueB=new SupplyTrip.Source<>("blue","B-blue",10201.0);
        check(trip.nearest(List.of(redB,blueB,blueA,redA)).equals(redA),"registration order cannot override nearest source");
        check(trip.nearest(List.of(redB,blueA)).equals(blueA),"near needed color wins over a far preferred or wholly missing color");
        trip.visit(redA.position());
        // The worker stays at A-red until its fixed red quota is filled, then
        // only blue sources are candidates; B-red must not be visited as well.
        check(trip.nearest(List.of(blueB,blueA)).equals(blueA),"take the next color in the same production area");
        trip.visit(blueA.position());
        check(trip.nearest(List.of(redA,blueA))==null,"visited boxes stay excluded across color changes and cooldown expiry");
        check(trip.nearest(List.of(redA,redB)).equals(redB),"exhausted or inaccessible near box can fall back to same-color far box");
        trip.freezeGoals(Map.of("red",256,"blue",128,"green",64));
        check(trip.limit("red",512)==128&&trip.limit("blue",512)==64,"replanning cannot grow a trip's quota");
        check(trip.limit("green",64)==0,"new future demand cannot extend an ongoing trip");
        check(trip.limit("red",30)==30,"other player's completed cells reduce current demand");
        check(trip.remaining("red",128,512)==0,"a fulfilled frozen quota cannot request a second same-color box for future demand");
        check(trip.remaining("red",192,512)==0,"whole-stack overfill never creates another visit");
        check(trip.remaining("red",64,512)==64,"an actually short quota can still use a fallback source");
        check(trip.remaining("red",40,30)==0,"shrinking world demand cancels an already selected source");
        check(trip.remaining("green",0,64)==0,"future colors outside this trip cannot cause a chest tour");
        // Delayed stock synchronization can arrive after choosing the fallback
        // but before opening it: the same fixed demand must then cancel travel.
        check(trip.remaining("blue",0,128)==64,"blue initially needs its first stack");
        check(trip.remaining("blue",64,128)==0,"late arrival of that stack cancels a second blue visit");
        check(trip.nearest(List.of(new SupplyTrip.Source<>("red","bad",Double.NaN),redB)).equals(redB),"invalid distances cannot win");
        trip.clear();trip.freezeGoals(Map.of("red",64));
        check(trip.nearest(List.of(redA,redB)).equals(redA),"a new genuine supply trip can reuse a productive near box");

        // Recompute reserved slots from frozen demand after one color was
        // taken. It must not grow goals or reserve already occupied slots twice.
        List<String> red=new ArrayList<>();for(int i=0;i<128;i++)red.add("red");
        List<String> blue=new ArrayList<>();for(int i=0;i<64;i++)blue.add("blue");
        var plan=MapArtSupplyPlanner.planRoundTrip(List.of(red,blue),Map.of(
            "red",new MapArtSupplyPlanner.Stock(128,128,64,true,false),
            "blue",new MapArtSupplyPlanner.Stock(0,0,64,true,false)),1);
        check(plan.desired().get("red")==128&&plan.desired().get("blue")==64,"remaining nearby color retains its planned slot");
        check(plan.reservedForOthers("blue")==0,"completed red quota no longer reserves empty slots");

        String code=Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/FullAutoMapArtModule.java"));
        String select=section(code,"private boolean selectNextSupply(","private Map<Item, Integer> countNextLaneMaterials(");
        check(select.contains("supplyTrip.nearest(sources)")&&!select.contains("selectedPriority")
            &&!select.contains("item == preferred"),"distance must be primary in real worker wiring");
        check(select.contains("if (!supplyTrip.planned()) prepareSupplyTrip()")&&select.contains("supplyTrip.freezeGoals(plan.desired())")
            &&!select.contains("supplyTrip.clear()"),"selection reuses bounded goals and trip visits");
        String begin=section(code,"private void beginRestock(","private WorkChoice findStockedTarget()");
        check(begin.indexOf("supplyTrip.clear()")<begin.indexOf("selectNextSupply("),"fresh visit budget starts only on a new trip");
        String transfer=section(code,"private void tickRestock()","private void refreshSupplyAmount()");
        check(transfer.contains("!supplyTrip.visited(pos)")&&transfer.contains("supplyTrip.visit(nearest)")
            &&transfer.contains("supplyAmount -= moved"),"keep taking at current box until quota reached and record actual visits");
        check(transfer.indexOf("supplyInventorySyncId != opened.syncId") < transfer.indexOf("ProducerSupplyReserve.Take take")
            &&transfer.contains("tick-supplyInventoryReadyTick < 2")&&code.contains("inventory.syncId() > 0"),
            "opening a handler without its full inventory must never be classified as an empty chest");
        check(!select.contains("returnBeforeDistantSource")&&!code.contains("partialSupplyUntilUsed"),
            "a partial take must not abandon remaining planned colors or block refilling after space is freed");
        String eligibility=section(code,"private boolean hasUsableSupply(Item item, int reserved)","private boolean isSupplyChestUnavailable(");
        check(eligibility.contains("canTakeSupplyStack(item, reserved)")
            &&eligibility.contains("new ItemStack(item).getMaxCount()")
            &&eligibility.contains("inventoryCapacity(item, reserved) >= minimum")
            &&!code.contains("capacityRecheckAt"),
            "whole-stack space is required before dispatch; unchanged capacity cannot expire by time");
        check(transfer.indexOf("if (!canTakeSupplyStack(supplyItem, reservedSupplySlots))") < transfer.indexOf("BlockPos nearest ="),
            "capacity is checked before travelling to another same-color box");
        check(transfer.contains("supplyChestLabel = supplyItem;")
            &&transfer.indexOf("findNearestLabelItem(") > transfer.indexOf("if (opened == null) {")
            &&transfer.contains("labelId.equals(registeredId)")&&transfer.contains("labelId.equals(requiredId)"),
            "unloaded remote frames cannot skip a source; live label IDs must still be verified on arrival");
        check(transfer.contains("pauseSupplyForProduction(take.total())")&&!transfer.contains("等待生产箱补入下一组材料")
            &&!transfer.contains("emptySupplyChestUntil"),"seed-limited producers are closed for cooldown instead of drained or watched");
        System.out.println("SupplyTrip: near production area first, fallback, frozen quotas, cross-color visits, slot reservations and worker wiring passed.");
    }
    private static String section(String code,String from,String to){int a=code.indexOf(from),b=code.indexOf(to,a+from.length());check(a>=0&&b>a,"source section");return code.substring(a,b);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
