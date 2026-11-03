package com.buildertoolbox.fullauto;

/** Distinguish predicted removal, server confirmation, rollback and bounded rejection. */
public final class CarpetCorrectionGate {
    public enum Action { STOP, WAIT, MINE, TIMEOUT }
    private CarpetCorrectionGate() { }
    public static Action decide(boolean serverWrong, boolean clientWrong, boolean active, long elapsed) {
        if (!serverWrong) return Action.STOP;
        if (active && elapsed > 100) return Action.TIMEOUT;
        if (!clientWrong) return active ? Action.WAIT : Action.STOP;
        return Action.MINE;
    }
}
