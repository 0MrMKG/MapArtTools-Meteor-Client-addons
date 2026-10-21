package com.buildertoolbox.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Real durable intent/restart accounting, including rejected and ambiguous transfers. */
public final class OrganizerJournalTest {
    public static void main(String[] args) throws Exception {
        var extraction = new OrganizerJournal.Pending("12,64,-9", "minecraft:red_carpet", true, 5, 32, 128, 32, 64);
        equal(OrganizerJournal.Result.UNCHANGED, OrganizerJournal.reconcile(extraction, 32, 128), "intent saved before click");
        equal(OrganizerJournal.Result.CONFIRMED, OrganizerJournal.reconcile(extraction, 96, 64), "server-confirmed whole stack");
        equal(OrganizerJournal.Result.CONFIRMED, OrganizerJournal.reconcile(extraction, 48, 112), "server accepts partial shift");
        equal(OrganizerJournal.Result.AMBIGUOUS, OrganizerJournal.reconcile(extraction, 96, 128), "client prediction alone cannot commit cargo");
        equal(OrganizerJournal.Result.AMBIGUOUS, OrganizerJournal.reconcile(extraction, 96, 63), "another player changed the source");
        equal(OrganizerJournal.Result.AMBIGUOUS, OrganizerJournal.reconcile(extraction, 97, 63), "never claim more than the clicked stack");
        var deposit = new OrganizerJournal.Pending("13,64,-9", "minecraft:red_carpet", false, 30, 96, 64, 96, 64);
        equal(OrganizerJournal.Result.CONFIRMED, OrganizerJournal.reconcile(deposit, 32, 128), "confirmed deposit after restart");
        equal(OrganizerJournal.Result.UNCHANGED, OrganizerJournal.reconcile(deposit, 96, 64), "rejected deposit stays cargo");
        equal(OrganizerJournal.Result.AMBIGUOUS, OrganizerJournal.reconcile(deposit, 32, 64), "a predicted deposit cannot erase cargo");
        boolean rejected = false;
        try { new OrganizerJournal.Pending("1,2,3", "minecraft:stone", false, 30, 64, 0, 32, 64); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "never include personal stock in a transfer intent");

        Path directory = Files.createTempDirectory("organizer-journal-test-");
        Path file = directory.resolve("整理进度.properties");
        try {
            var journal = new OrganizerJournal(file);
            var before = new OrganizerJournal.State("server:example", "minecraft:overworld", "player-A", 64,
                Map.of("minecraft:red_carpet", 32), extraction);
            journal.write(before);
            equal(before, new OrganizerJournal(file).read(), "fresh process restores the pending intent and cargo");
            var committed = new OrganizerJournal.State(before.world(), before.dimension(), before.player(), before.walkY(),
                Map.of("minecraft:red_carpet", 96), null);
            journal.write(committed);
            equal(committed, new OrganizerJournal(file).read(), "committed cargo is durable without pending click");
            var finished = new OrganizerJournal.State(before.world(), before.dimension(), before.player(), before.walkY(), Map.of(), null);
            journal.write(finished);
            equal(finished, new OrganizerJournal(file).read(), "deposited cargo is not restored twice");
            try (var files = Files.list(directory)) { equal(1L, files.count(), "atomic writes leave no temporary files"); }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        System.out.println("OrganizerJournal: durable restart, rejected/partial transfers and personal inventory isolation checks passed.");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
