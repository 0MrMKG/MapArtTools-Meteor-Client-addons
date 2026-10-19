package com.buildertoolbox.utils;

/** Recipe arithmetic independent of screens, walking or storage. */
public final class CraftingBatchPlan {
    private CraftingBatchPlan() { }

    public record Distribution(int perSlot, int remainder) { }

    public static Distribution distribute(int count, int inputSlots) {
        if (count < 0 || inputSlots < 1) throw new IllegalArgumentException("Invalid crafting input");
        return new Distribution(count / inputSlots, count % inputSlots);
    }

    public static boolean validConversion(int beforeInput, int afterInput, int beforeOutput, int afterOutput,
                                          int inputsPerCraft, int outputsPerCraft) {
        int used = beforeInput - afterInput;
        return beforeInput >= 0 && afterInput >= 0 && beforeOutput >= 0 && afterOutput >= 0
            && inputsPerCraft > 0 && outputsPerCraft > 0 && used > 0 && used % inputsPerCraft == 0
            && (long) afterOutput - beforeOutput == (long) used / inputsPerCraft * outputsPerCraft;
    }
}
