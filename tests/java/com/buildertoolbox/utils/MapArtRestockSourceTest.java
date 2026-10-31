package com.buildertoolbox.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Source guard: neither independent map-art worker may reintroduce cursor-based compaction. */
public final class MapArtRestockSourceTest {
    public static void main(String[] args) throws Exception {
        for (String name : List.of("LitematicaMapArtModule", "FullAutoMapArtModule")) {
            String source = Files.readString(Path.of("src/main/java/com/buildertoolbox/modules/" + name + ".java"), StandardCharsets.UTF_8);
            for (String removed : List.of("InventoryMergePlanner", "COMPACT_INVENTORY", "tickInventoryCompaction",
                "beginRestockAfterCompaction", "pendingInventoryMerge", "pendingRestockTarget", "findInventoryMerge", "SlotActionType.PICKUP")) {
                check(!source.contains(removed), name + " still contains removed inventory compaction: " + removed);
            }
            int start = source.indexOf("private void beginRestock(ProjectionTarget current)");
            int end = source.indexOf("private WorkChoice findStockedTarget()", start);
            check(start >= 0 && end > start, name + " has no direct restock entry point");
            check(source.substring(start, end).contains("selectNextSupply(current.state().getBlock().asItem())"), name + " no longer plans direct restocking");
            check(source.contains("if (safeCapacity <= 0)"), name + " lost its actual-space guard");
            if (name.equals("LitematicaMapArtModule")) {
                check(source.contains("InvUtils.shiftClick().slotId(slot)"), name + " lost whole-stack chest retrieval");
            } else {
                check(source.contains("mc.interactionManager.clickSlot(opened.syncId,slot,0,SlotActionType.QUICK_MOVE,mc.player)"),
                    name + " must quick-move the confirmed container slot as a whole stack");
                check(source.contains("batch.rememberCursorOrigin(opened,slot)"),
                    name + " must preserve the exact owned source for interrupted transfers");
            }
            String timeout = name.equals("FullAutoMapArtModule")
                ? "tick - supplyProgressTick >= StationaryRestockWatchdog.TIMEOUT_TICKS"
                : "tick - supplyProgressTick >= 20L * 30";
            check(source.contains(timeout), name + " lost its no-progress supply watchdog");
            check(source.contains("if (supplyOpenAttempts >= 3)"), name + " lost its bounded chest-open attempts");
            check(source.contains("supplyOpenAttempts++;"), name + " must count actual chest clicks");
            check(!source.contains("已暂停：鼠标指针仍持有物品"), name + " must not pause the job solely due to a cursor stack");
        }
        String root = "src/main/java/com/buildertoolbox/";
        for (String namespace : List.of("utils", "fullauto")) {
            String recovery = Files.readString(Path.of(root + namespace + "/CursorRecovery.java"), StandardCharsets.UTF_8);
            check(!recovery.contains("SlotActionType.THROW") && !recovery.contains("dropItem("), "cursor recovery cannot discard items");
            check(recovery.contains("ItemStack.areItemsAndComponentsEqual(cursor,existing)")
                && recovery.contains("slot.canInsert(cursor)") && recovery.contains("slot.getMaxItemCount(cursor)"),
                "recovery must not exchange incompatible/full/rejecting slots");
            check(recovery.contains("slot.inventory!=mc.player.getInventory()"), "fallback destinations must belong to player inventory");
            check(recovery.contains("storageSlots(handler)>0&&storageCursorAllowed.test(handler.getCursorStack())"),
                "full-inventory fallback must require storage ownership and its registered material predicate");
            check(recovery.contains("if(!allowed)") && recovery.indexOf("if(!allowed)") < recovery.indexOf("int slot=findDestination"),
                "manual containers must never be modified by recovery");
            check(recovery.contains("MAX_ATTEMPTS_WITHOUT_PROGRESS = 3") && recovery.contains("return !needsSpace;")
                && recovery.contains("needsSpace?5_000_000_000L"), "failed cursor recovery must yield after bounded attempts");
            check(recovery.contains("handler==mc.player.playerScreenHandler") && recovery.contains("slot.getIndex()==40")
                && recovery.contains("block.getBlock() instanceof CarpetBlock"), "offhand parking must preserve tracked map/glass main-inventory slots");
            check(recovery.contains("storageUnloadAllowed.test(stack)") && recovery.contains("canParkWholeHotbarStack(true,accepted,")
                && recovery.contains("SlotActionType.SWAP"), "whole-stack fallback must require a permitted material and an empty owned storage slot");
            check(recovery.contains("void onPacket(Packet<?> packet)") && recovery.contains("InventoryS2CPacket")
                && recovery.contains("SetCursorItemS2CPacket") && recovery.contains("ScreenHandlerSlotUpdateS2CPacket")
                && recovery.contains("PREDICTION_HISTORY_NANOS"), "late server cursor corrections must not erase the retry history");
        }
        String controller = Files.readString(Path.of(root + "fullauto/MapArtBatchController.java"), StandardCharsets.UTF_8);
        check(!controller.contains("鼠标持有物品，已暂停"), "full-auto workflow must recover instead of pausing");
        check(controller.contains("host.batchRepairNearby()"), "physical inspection must attempt nearby repairs");
        String fullAuto = Files.readString(Path.of(root + "modules/FullAutoMapArtModule.java"), StandardCharsets.UTF_8);
        String freeing = section(fullAuto, "private boolean freeCursorSpace()", "private void tickRestock()");
        check(freeing.contains("CursorSpacePlanner.chooseMaterial(hotbar,missing)")
            && freeing.contains("CursorSpacePlanner.attemptLimit(cursor.getMaxCount())")
            && !freeing.contains("cursorSpaceAttempts >= 3"),
            "full-auto must plan an entire clearable stack rather than give up after three placements");
        check(freeing.contains("findCursorSpaceStand(") && freeing.contains("navigateTo(cursorSpaceStand)")
            && freeing.contains("cursorSpaceRejected") && freeing.contains("cursorSpaceRejectedStands.add(cursorSpaceStand)"),
            "full-auto cursor recovery must move to a safe reachable area and replace an arrived-but-out-of-range stand");
        String returning = section(fullAuto, "public boolean batchReturnAfterCursorRecovery()", "public void batchBeginReviewRepairs(");
        check(returning.contains("navigateTo(cursorSpaceOrigin)") && returning.contains("NavigationResult.UNREACHABLE")
            && returning.contains("cursorSpaceReturning = false; cursorSpaceOrigin = null;")
            && controller.contains("host.batchReturnAfterCursorRecovery()"),
            "cursor recovery must return to its saved work position or release an unreachable return safely");
        String engine = Files.readString(Path.of(root + "fullauto/FullAutoPlacementEngine.java"), StandardCharsets.UTF_8);
        check(!engine.contains("CloseHandledScreenC2SPacket"),
            "full-auto engine must never close a server inventory as a placement flush; a late cursor correction could otherwise drop items");
        check(!engine.contains("InvUtils.move()") && !controller.contains("InvUtils.move()"),
            "full-auto hand preparation must not leave a multi-click cursor transaction");
        check(engine.contains("SlotActionType.SWAP") && controller.contains("SlotActionType.SWAP"),
            "block and map hand preparation must use cursor-free swaps");
        for (String relative : List.of("fullauto/FullAutoPlacementEngine.java", "modules/LitematicaPrinterModule.java")) {
            String printer = Files.readString(Path.of(root + relative), StandardCharsets.UTF_8);
            check(!printer.contains("InvUtils.findInHotbar(item)") && !printer.contains("InvUtils.move()"),
                relative + " must neither prefer offhand matches nor use multi-click hotbar transfers");
            check(printer.contains("for (int slot = 0; slot < 9; slot++)")
                && printer.contains("for (int source = 9; source < 36; source++)"),
                relative + " must search real hotbar and main inventory explicitly");
            check(printer.contains("SlotActionType.SWAP")
                && printer.contains("mc.player.currentScreenHandler != mc.player.playerScreenHandler"),
                relative + " hotbar transfer must be atomic and handler guarded");
            check(printer.contains("placeProjectionBlockForCursorRecovery")
                && printer.contains("recoverCursor ? findCursorRecoveryHotbarItem(item) : moveToHotbar(item)"),
                relative + " cursor space recovery must only consume existing hotbar stacks without closing the inventory");
            if (relative.startsWith("modules/")) check(printer.contains("if (!recoverCursor) mc.player.networkHandler.sendPacket(new CloseHandledScreenC2SPacket"),
                "the original printer must retain its existing close behavior exclusively outside cursor recovery");
            check(printer.contains("interactWithSupportSneak(plan, immutablePos, item, true, recoverCursor)")
                && printer.contains("|| !recoverCursor && !mc.player.currentScreenHandler.getCursorStack().isEmpty()"),
                relative + " must preserve regular cursor guards across delayed rotation callbacks");
            check(printer.contains("boolean compatible = ItemStack.areItemsAndComponentsEqual(stack, cursor)")
                && printer.contains("compatible && !bestCompatible")
                && printer.contains("compatible == bestCompatible && stack.getCount() < smallest"),
                relative + " space recovery must choose a cursor-compatible or smallest hotbar stack, not the first match");
        }
        System.out.println("MapArtRestockSource: direct restock, whole-stack retrieval, module-specific no-progress watchdogs and bounded chest clicks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from + start.length());
        check(from >= 0 && to > from, "missing source section: " + start);
        return source.substring(from, to);
    }
}
