package com.buildertoolbox.utils;

public final class ServerPlacementLedgerTest {
    public static void main(String[] args) {
        ServerPlacementLedger<String,String> ledger = new ServerPlacementLedger<>();
        check(ledger.observedOr("old","carpet").equals("carpet"), "untouched loaded blocks use the world snapshot");
        ledger.watch("last","air");
        check(ledger.observedOr("last","carpet").equals("air"), "local placement prediction alone cannot finish the last cell");
        ledger.acknowledge("last","air");
        check(ledger.observedOr("last","carpet").equals("air"), "server rejection must retain the missing cell");
        ledger.watch("last","carpet");
        check(ledger.observedOr("last","carpet").equals("air"), "retry must not overwrite the authoritative rejection with prediction");
        ledger.acknowledge("last","carpet");
        check(ledger.observedOr("last","carpet").equals("carpet"), "server acceptance completes the cell");
        ledger.acknowledge("last","air");
        check(ledger.observedOr("last","carpet").equals("air"), "later rollback invalidates completion");
        ledger.invalidateWhere(key -> key.equals("last"));
        check(ledger.observedOr("last","carpet").equals("carpet"), "a newly loaded authoritative chunk replaces stale pre-unload receipts");
        ledger.clear();
        check(ledger.observedOr("last","carpet").equals("carpet"), "a new task does not inherit another world's receipts");
        System.out.println("ServerPlacementLedger: prediction, acceptance, rejection, retries and rollback passed.");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
