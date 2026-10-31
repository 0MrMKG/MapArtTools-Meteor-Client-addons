package com.buildertoolbox.fullauto;

/** Real matrix decisions: loaded-area scanning, defect detours and fresh server observations. */
public final class CanvasRepairStrategyTest {
    public static void main(String[] args) {
        BatchInspection canvas = new BatchInspection(BatchInspection.SCAN_WIDTH, false);
        check(canvas.route().size() == 8, "128x128 canvas needs only four wide fallback bands");
        check(canvas.route().stream().allMatch(p -> p.x() >= 0 && p.x() < 128 && p.z() >= 0 && p.z() < 128),
            "survey never expands beyond the painting");
        int cursor = 0, ticks = 0;
        while (cursor < 16384) {
            int budget = BatchInspection.MAX_SCAN_CELLS;
            while (budget-- > 0 && cursor < 16384) {
                int x = cursor % 128, z = cursor / 128;
                canvas.observe(x,z,BatchInspection.Cell.CORRECT);
                cursor++;
            }
            ticks++;
        }
        check(ticks == 16, "a loaded full canvas is budgeted across ticks, without a physical dense lap");
        check(canvas.route().stream().noneMatch(canvas::needsVisit), "read bands need no walking");
        check(canvas.canAccept(7,7,false,0), "known complete canvas passes after a fresh snapshot");
        check(!canvas.canAccept(-1,7,false,0), "startup cannot accept an unfinished budgeted scan");
        check(!canvas.canAccept(7,8,false,0), "a server rollback invalidates completion until another scan");
        check(!canvas.canAccept(7,7,true,0), "pending placement cannot masquerade as completion");
        canvas.observe(2,3,BatchInspection.Cell.MISSING);
        canvas.observe(120,2,BatchInspection.Cell.MISSING);
        canvas.observe(125,100,BatchInspection.Cell.MISSING);
        check(!canvas.canAccept(7,7,false,0), "holes keep the job in repair");
        check(canvas.nextMissing(0,0,p -> true).equals(new BatchInspection.Point(2,3)), "near hole in first broad band wins");
        canvas.observe(2,3,BatchInspection.Cell.CORRECT);
        check(canvas.nextMissing(125,100,p -> true).equals(new BatchInspection.Point(120,2)),
            "retain snake-band order instead of jumping to a distant band containing one convenient color");
        check(canvas.nextMissing(0,0,p -> p.z() > 32).equals(new BatchInspection.Point(125,100)),
            "a deferred bad stand does not block other defects");
        canvas.observe(120,2,BatchInspection.Cell.WRONG);
        check(canvas.canAccept(8,8,false,1), "wrong color stays separate from the missing tolerance");
        canvas.invalidate(60,60);
        check(!canvas.canAccept(8,8,false,1), "unknown cell requires loading even with a generous missing tolerance");
        check(canvas.route().stream().filter(canvas::needsVisit).count() == 2, "only the affected wide band's two endpoints need loading");
        var saved = canvas.checkpoint(3,17,19);
        check(BatchInspection.restore(BatchInspection.Checkpoint.decode(saved.encode())).missing() == 1,
            "wide scan progress round-trips through the existing journal");
        check(!BatchInspection.restartAfterOffline(saved).complete(), "offline completion observations cannot be reused");

        System.out.println("CanvasRepairStrategy: budgeted full-canvas perception, sparse snake loading, defect-only travel and server revision gate passed.");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
