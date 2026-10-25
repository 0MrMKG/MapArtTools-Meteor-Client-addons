package com.buildertoolbox.fullauto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source wiring guards only; these checks do not simulate Minecraft, server receipts or live navigation. */
public final class FullAutoRepairSourceTest {
    public static void main(String[] args) throws Exception {
        String root = "src/main/java/com/buildertoolbox/";
        String controller = Files.readString(Path.of(root + "fullauto/MapArtBatchController.java"), StandardCharsets.UTF_8);
        String module = Files.readString(Path.of(root + "modules/FullAutoMapArtModule.java"), StandardCharsets.UTF_8);
        String rules = Files.readString(Path.of(root + "fullauto/BatchWorkflowRules.java"), StandardCharsets.UTF_8);
        String journal = Files.readString(Path.of(root + "fullauto/BatchJournal.java"), StandardCharsets.UTF_8);

        String print = section(controller, "case PRINT -> {", "case REVIEW ->");
        check(!print.contains("scan(false)") && !print.contains("batchLane(")
            && !print.contains("acceptableErrors(") && !print.contains("batchRemaining("),
            "PRINT must finish its snake work rather than polling a global scan or lane completion gate");
        int finished = print.indexOf("if(host.batchWorkSweepFinished())");
        check(finished >= 0 && print.indexOf("beginInspection()") > finished
            && print.indexOf("host.batchPlacementPending()") > finished && print.contains("return false;"),
            "automatic PRINT-to-REVIEW transition must wait for the worker sweep and pending placements");

        String begin = section(controller, "private void beginInspection()", "private void inspectDrawing()");
        check(begin.contains("job.repairPass=1") && begin.contains("host.batchStopWorker()")
            && begin.contains("host.batchBeginReviewRepairs(job.repairPass)") && begin.contains("transition(Phase.REVIEW)"),
            "repair sweep initialization must stop the ordinary worker and retain an explicit pass counter");
        String review = section(controller, "private void inspectDrawing()", "private void observeNearby()");
        check(review.contains("host.batchRepairNearby()")
            && compact(review).contains("allowed=host.batchAllowedMissingBlocks()")
            && compact(review).contains("BatchWorkflowRules.reviewAction(job.repairPass,inspection.missing(),unknown,allowed)")
            && compact(review).contains("job.repairPass=BatchWorkflowRules.nextRepairPass(job.repairPass);beginRepairPass()"),
            "REVIEW must use live missing-only tolerance and keep alternating repair passes until it is met");
        check(!compact(review).contains("job.repairPass=2;") && !review.contains("acceptableErrors(")
            && !review.contains("scan(false)"), "no fixed third-sweep override or legacy total-error scan may end repair");
        String decision = compact(method(rules, "public static ReviewAction reviewAction("));
        check(decision.contains("unknown==0&&missing<=allowedMissing?ReviewAction.ACCEPT:ReviewAction.REPAIR_AGAIN")
            && !decision.contains("pass==2") && !decision.contains("pass>2") && !decision.contains("acceptableErrors(")
            && !decision.contains("wrong") && !decision.contains("errors"),
            "completion must require all observed cells and inclusive missing <= allowed, independent of wrong blocks or sweep number");
        check(decision.contains("allowedMissing<0") && decision.contains("allowedMissing>16384"),
            "the completion policy must validate the full 0..16384 tolerance range");
        check(method(rules, "public static int nextRepairPass(").contains("Math.incrementExact(pass)"),
            "further repair sweeps must increment their persisted counter without silent integer wraparound");
        String accepted = compact(method(controller, "private void drawingAccepted("));
        check(accepted.contains("BatchWorkflowRules.reviewAction(job.repairPass,inspection.missing(),inspection.unknown(),allowedMissing)"),
            "map creation must recheck missing and unknown counts instead of trusting an earlier sweep result");
        String persisted = compact(journal);
        check(persisted.contains("s.repairPass=integer(p,\"repairPass\",s.phase==Phase.REVIEW?1:0)")
            && persisted.contains("p.setProperty(\"repairPass\",\"\"+s.repairPass)")
            && !persisted.contains("s.repairPass>2"), "journal recovery must retain repair counters beyond the old two-pass cap");

        String tolerance = compact(section(module, "private final Setting<Integer> allowedMissingBlocks", "private final Setting<String> canvasOrigin"));
        check(tolerance.contains(".name(\"AllowedMissingBlocks\")") && tolerance.contains(".defaultValue(0)")
            && tolerance.contains(".range(0,16384)") && !tolerance.contains(".onChanged("),
            "the full-auto missing tolerance must default to zero and change live without rebuilding the batch");
        check(compact(method(module, "public int batchAllowedMissingBlocks()")).contains("returnallowedMissingBlocks.get();"),
            "the controller must read the current setting rather than a startup copy");
        String configuration = compact(method(module, "public MapArtBatchController.Configuration batchConfiguration()"));
        String signature = compact(method(controller, "public String signature()"));
        check(!configuration.contains("allowedMissingBlocks") && !configuration.contains("batchAllowedMissingBlocks")
            && !signature.contains("allowedMissing") && !signature.contains("batchAllowedMissing"),
            "changing the live completion limit must not alter the batch configuration signature or require reset");
        check(!review.contains("Phase.PRINT") && !review.contains("batchRetryDrawing")
            && !review.contains("batchStartDrawing") && !review.contains("tickWork("),
            "REVIEW must never restart the ordinary PRINT state or drawing loop");
        check(!controller.contains("batchRetryDrawing") && !module.contains("batchRetryDrawing")
            && !module.contains("workRetryAt"), "full-auto repair must not retain the former retry-delay loop");

        String repair = section(module, "public boolean batchRepairNearby()", "public int batchReviewWidth()");
        check(repair.contains("if (reviewRestocking)") && repair.contains("tickRestock()")
            && repair.contains("tickReturnFromRestock()") && !repair.contains("tickWork("),
            "review replenishment must drive only the existing supply and saved-position return steps");
        check(repair.contains("reviewRestocking = true") && repair.contains("beginRestock(missingMaterial)")
            && compact(repair).contains("if(state!=TaskState.RESTOCK)reviewSupplyTried.add(missingMaterial.state().getBlock().asItem());")
            && repair.indexOf("reviewSupplyTried.add(missingMaterial") > repair.indexOf("beginRestock(missingMaterial)"),
            "only a rejected no-op supply trip may consume the nearby color budget without selecting a supply item");

        String supply = compact(section(module, "private boolean selectNextSupply(Item preferred)", "private Map<Item, Integer> countNextLaneMaterials("));
        check(supply.contains("if(reviewRestocking&&reviewSupplyTried.contains(item))continue;"),
            "a review supply trip must not reselect an already attempted color after its normal cooldown expires");
        int selected = supply.indexOf("if(selected==null)returnfalse;");
        int recorded = supply.indexOf("if(reviewRestocking)reviewSupplyTried.add(selected);");
        check(selected >= 0 && recorded > selected,
            "the review attempt must be recorded only after an actual supply item has been selected");

        String startRepair = section(module, "public void batchBeginReviewRepairs(int pass)", "public boolean batchRepairNearby()");
        check(startRepair.contains("reviewPass = pass") && startRepair.contains("reviewSupplyTried.clear()"),
            "each repair pass needs an independent material attempt budget");
        check(startRepair.contains("isReplaceable()") && repair.contains("isReplaceable()")
            && compact(repair).contains("if(isSatisfied(candidate))continue;"),
            "repair must admit missing replaceable cells but never replace an already satisfied target or a solid wrong block");
        check(compact(module).contains("returnreverseSnake.get()^((reviewPass&1)!=0);"),
            "all repair passes must keep alternating against the original snake direction");
        String restock = section(module, "private void beginRestock(ProjectionTarget current)", "private WorkChoice findStockedTarget()");
        String finish = section(module, "private void finishRestock()", "private void continueRestock()");
        String returning = section(module, "private void tickReturnFromRestock()", "private void finishReturnToWork()");
        check(restock.contains("restockReturnPos = returnPos") && finish.contains("returningFromRestock = restockReturnPos != null")
            && returning.contains("navigateTo(restockReturnPos)") && !returning.contains("placeTarget("),
            "replenishment must return to the saved repair position without consuming planned materials in transit");

        System.out.println("FullAutoRepairSource: live inclusive missing-only tolerance, unknown-cell gate, unlimited alternating repair passes, per-pass supplies and saved-position return passed (source checks, not a live-game proof).");
    }

    private static String compact(String source) { return source.replaceAll("\\s+", ""); }
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        check(start >= 0, "missing method: " + signature);
        int open = source.indexOf('{', start), depth = 1;
        check(open >= 0, "missing method body: " + signature);
        for (int index = open + 1; index < source.length(); index++) {
            if (source.charAt(index) == '{') depth++;
            else if (source.charAt(index) == '}' && --depth == 0) return source.substring(open, index + 1);
        }
        throw new AssertionError("unterminated method: " + signature);
    }
    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        check(from >= 0 && to > from, "missing source section: " + start);
        return source.substring(from, to);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
