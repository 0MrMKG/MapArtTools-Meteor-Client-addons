package com.buildertoolbox.utils;

/** Batch expansion, nine-slot distribution and rejected/partial craft accounting. */
public final class CraftingBatchPlanTest {
    public static void main(String[] args) {
        var split = CraftingBatchPlan.distribute(64, 9);
        equal(7, split.perSlot(), "64 powder -> seven per input slot");
        equal(1, split.remainder(), "return the cursor remainder to its source slot");
        equal(0, CraftingBatchPlan.distribute(8, 9).perSlot(), "never try an incomplete powder recipe");
        equal(64, CraftingBatchPlan.distribute(64, 1).perSlot(), "one-cell bone recipe");
        check(CraftingBatchPlan.validConversion(64, 0, 0, 192, 1, 3), "bulk bone conversion");
        check(CraftingBatchPlan.validConversion(64, 43, 5, 68, 1, 3), "partial output capacity remains valid");
        check(CraftingBatchPlan.validConversion(192, 129, 0, 7, 9, 1), "seven packed blocks");
        check(!CraftingBatchPlan.validConversion(64, 64, 0, 0, 1, 3), "server rejection is not success");
        check(!CraftingBatchPlan.validConversion(64, 0, 0, 191, 1, 3), "lost powder is not success");
        check(!CraftingBatchPlan.validConversion(9, 1, 0, 1, 9, 1), "incomplete input consumption");
        check(!CraftingBatchPlan.validConversion(0, -1, 0, 3, 1, 3), "invalid negative count");
        for (int bones : new int[] {1, 2, 3, 63, 64, 65, 128, 192, 1728, 3456}) verifyBatches(bones);
        System.out.println("CraftingBatchPlan: batch expansion, leftovers, fragmented stacks and rejected conversion checks passed.");
    }

    private static void verifyBatches(int bones) {
        int powder = 0;
        int blocks = 0;
        int remaining = bones;
        while (remaining > 0) {
            int taken = Math.min(64, remaining);
            remaining -= taken;
            powder += 3 * taken;
            while (powder >= 9) {
                int stack = Math.min(64, powder);
                int crafts = CraftingBatchPlan.distribute(stack, 9).perSlot();
                // The last nine ingredients may come from separate stacks.
                if (crafts == 0) crafts = 1;
                int before = powder;
                int previousBlocks = blocks;
                powder -= 9 * crafts;
                blocks += crafts;
                check(CraftingBatchPlan.validConversion(before, powder, previousBlocks, blocks, 9, 1),
                    "confirm each actual batch");
            }
            equal(3 * (bones - remaining), 9 * blocks + powder, "no loss across repeated fetch/craft/deposit cycles");
        }
        equal(bones / 3, blocks, "bone-block total");
        equal((bones % 3) * 3, powder, "final powder is returned to the same source");
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
