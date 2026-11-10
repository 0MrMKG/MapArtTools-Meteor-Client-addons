package com.buildertoolbox.fullauto;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class AtomicJournalCommitTest {
    public static void main(String[] args) throws Exception {
        Path buildRoot = Path.of("build").toAbsolutePath().normalize();
        Path folder = Files.createTempDirectory(buildRoot, "journal-commit-");
        try {
            Path live = folder.resolve("已有批次.properties");
            Path candidate = folder.resolve("next.tmp");
            Files.writeString(live, "old checkpoint");
            Files.writeString(candidate, "new checkpoint");
            int[] calls = {0};
            List<Long> waits = new ArrayList<>();
            AtomicJournalCommit.replace(candidate, live, (from, to) -> {
                if (++calls[0] <= 2) {
                    check(Files.readString(to).equals("old checkpoint"), "retry must preserve the previous checkpoint");
                    throw new AccessDeniedException(from.toString(), to.toString(), "sharing violation");
                }
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }, waits::add);
            check(calls[0] == 3 && waits.equals(List.of(20L, 40L)), "temporary lock retries only the same pending commit");
            check(Files.readString(live).equals("new checkpoint") && !Files.exists(candidate), "retry atomically replaces an existing journal");

            Files.writeString(candidate, "never committed");
            calls[0] = 0; waits.clear();
            try {
                AtomicJournalCommit.replace(candidate, live, (from, to) -> {
                    calls[0]++; throw new AccessDeniedException(from.toString(), to.toString(), null);
                }, waits::add);
                throw new AssertionError("permanent denial must remain an error");
            } catch (IOException expected) {
                check(expected.getMessage().contains("AccessDeniedException") && expected.getMessage().contains("原进度保留"),
                    "persistent failure includes its type instead of opaque temporary paths");
            }
            check(calls[0] == 4 && waits.stream().mapToLong(Long::longValue).sum() == 140, "retry budget is bounded");
            check(Files.readString(live).equals("new checkpoint") && Files.readString(candidate).equals("never committed"),
                "failed commits never delete or truncate either record");

            calls[0] = 0; waits.clear();
            try {
                AtomicJournalCommit.replace(candidate, live, (from, to) -> {
                    calls[0]++; throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "unsupported");
                }, waits::add);
                throw new AssertionError("unsupported atomic commit must not become an unsafe overwrite");
            } catch (IOException expected) { }
            check(calls[0] == 1 && waits.isEmpty(), "unsupported atomic operations do not retry or fall back to truncation");

            try {
                AtomicJournalCommit.replace(candidate, live, (from, to) -> {
                    throw new AccessDeniedException(from.toString());
                }, delay -> { throw new InterruptedException(); });
                throw new AssertionError("interrupted save must fail");
            } catch (IOException expected) {
                check(Thread.currentThread().isInterrupted(), "interrupt status survives save retry");
            } finally { Thread.interrupted(); }

            var journal = new BatchJournal(folder.resolve("batch.properties"));
            var state = BatchJournal.startingAt(List.of(new BatchFiles.Entry("地图1.litematic", "hash")), 1);
            for (int i = 0; i < 30; i++) {
                state.elapsedMillis = i * 1000L;
                journal.write(state);
                check(journal.read().elapsedMillis == state.elapsedMillis, "repeated saves reuse an existing checkpoint without cleanup");
            }
            System.out.println("AtomicJournalCommit: sharing-conflict retry, permanent failure preservation, atomic-only commits, interruption and existing-journal updates passed.");
        } finally {
            if (!folder.toAbsolutePath().normalize().startsWith(buildRoot)) throw new IOException("Unexpected test cleanup path");
            try (var files = Files.walk(folder)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
