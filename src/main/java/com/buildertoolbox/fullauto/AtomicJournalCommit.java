package com.buildertoolbox.fullauto;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Retry short Windows sharing conflicts without deleting or truncating the live journal. */
public final class AtomicJournalCommit {
    private AtomicJournalCommit() { }
    @FunctionalInterface interface Move { void run(Path source, Path target) throws IOException; }
    @FunctionalInterface interface Wait { void run(long millis) throws InterruptedException; }

    public static void replace(Path source, Path target) throws IOException {
        replace(source, target, (from, to) -> Files.move(from, to,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), Thread::sleep);
    }

    static void replace(Path source, Path target, Move move, Wait wait) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                move.run(source, target);
                return;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("文件系统不支持原子保存，原进度保留", exception);
            } catch (FileSystemException exception) {
                if (exception instanceof NoSuchFileException || exception instanceof DirectoryNotEmptyException
                    || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw exception;
                if (attempt >= 3) {
                    String reason = exception.getReason();
                    throw new IOException("无法替换进度文件 " + target.getFileName() + "（"
                        + exception.getClass().getSimpleName() + (reason == null ? "" : "：" + reason)
                        + "）；原进度保留，请检查文件占用或写入权限", exception);
                }
                try {
                    wait.run(20L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException("保存重试被中断，原进度保留", interrupted);
                    failure.addSuppressed(exception);
                    throw failure;
                }
            }
        }
    }
}
