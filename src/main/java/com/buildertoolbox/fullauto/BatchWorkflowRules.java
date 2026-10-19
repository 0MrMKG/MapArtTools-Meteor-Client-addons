package com.buildertoolbox.fullauto;

import static com.buildertoolbox.fullauto.BatchJournal.Phase;

/** Deterministic workflow rules shared by execution and restart regression tests. */
public final class BatchWorkflowRules {
    private BatchWorkflowRules() { }
    public static final long CLEAR_BUFFER_MILLIS = 10000;
    public enum LeverRecovery { EXECUTE, CONFIRMED, AMBIGUOUS }
    public enum ProductRecovery { CONFIRMED, TRANSFER, AMBIGUOUS, MISSING }
    public static Phase nextLeverPhase(Phase phase) {
        return switch(phase) {
            case CLEAR_ON_1 -> Phase.WAIT_ON_1;
            case WAIT_ON_1 -> Phase.CLEAR_OFF_1;
            case CLEAR_OFF_1 -> Phase.WAIT_OFF_1;
            case WAIT_OFF_1 -> Phase.CLEAR_ON_2;
            case CLEAR_ON_2 -> Phase.WAIT_ON_2;
            case WAIT_ON_2 -> Phase.CLEAR_OFF_2;
            case CLEAR_OFF_2 -> Phase.WAIT_OFF_2;
            case WAIT_OFF_2 -> Phase.CLEAR_BUFFER;
            default -> throw new IllegalArgumentException("Not a lever stage: "+phase);
        };
    }
    public static boolean leverPowered(Phase phase) {
        return switch(phase) {
            case CLEAR_ON_1,WAIT_ON_1,CLEAR_ON_2,WAIT_ON_2 -> true;
            case CLEAR_OFF_1,WAIT_OFF_1,CLEAR_OFF_2,WAIT_OFF_2 -> false;
            default -> throw new IllegalArgumentException("Not a lever stage");
        };
    }
    public static boolean leverWait(Phase phase) {
        return phase==Phase.WAIT_ON_1||phase==Phase.WAIT_OFF_1||phase==Phase.WAIT_ON_2||phase==Phase.WAIT_OFF_2;
    }
    public static LeverRecovery leverRecovery(boolean issued,boolean desired,boolean actual) {
        return issued?(desired==actual?LeverRecovery.CONFIRMED:LeverRecovery.AMBIGUOUS)
            :(desired!=actual?LeverRecovery.EXECUTE:LeverRecovery.AMBIGUOUS);
    }
    public static ProductRecovery productRecovery(int inBag,int inProductBox) {
        if(inProductBox>0)return inBag==0?ProductRecovery.CONFIRMED:ProductRecovery.AMBIGUOUS;
        return inBag>0?ProductRecovery.TRANSFER:ProductRecovery.MISSING;
    }
}
