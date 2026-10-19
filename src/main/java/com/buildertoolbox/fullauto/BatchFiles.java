package com.buildertoolbox.fullauto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Filesystem-only queue discovery; safe to run off the Minecraft thread. */
public final class BatchFiles {
    private BatchFiles() { }
    public record Entry(String name, String sha256) { }

    public static Path directory(Path base, String relative) throws IOException {
        Path root = base.toRealPath();
        Path supplied = Path.of(relative);
        if (relative.isBlank() || supplied.isAbsolute()) throw new IOException("填写 schematics 下的相对子目录");
        Path folder = root.resolve(supplied).normalize().toRealPath();
        if (!folder.startsWith(root) || !Files.isDirectory(folder)) throw new IOException("目录必须位于 schematics 内");
        return folder;
    }

    public static List<Entry> scan(Path folder) throws IOException {
        List<Entry> entries = inspectDirectory(folder);
        if (entries.isEmpty()) throw new IOException("目录中没有 .litematic 文件");
        return entries;
    }

    /** Read-only probe, including file contents/fingerprints; an empty folder is valid. */
    public static List<Entry> inspectDirectory(Path folder) throws IOException {
        List<Path> files;
        try (var stream = Files.list(folder)) {
            files = stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".litematic"))
                .sorted((a, b) -> naturalCompare(a.getFileName().toString(), b.getFileName().toString())).toList();
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            if (!file.toRealPath().startsWith(folder.toRealPath())) throw new IOException("蓝图链接指向目录之外");
            entries.add(new Entry(file.getFileName().toString(), fingerprint(file)));
        }
        return List.copyOf(entries);
    }

    public static String fingerprint(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] bytes = new byte[65536];
                for (int count; (count = input.read(bytes)) >= 0;) if (count > 0) digest.update(bytes, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    /** Validate the snapshotted queue; newly added files belong to a later batch. */
    public static void validateQueue(Path folder, List<Entry> entries) throws IOException {
        Path root = folder.toRealPath();
        for (Entry entry : entries) {
            Path path = folder.resolve(entry.name()).normalize();
            if (!path.getParent().equals(folder.normalize()) || !path.toRealPath().startsWith(root))
                throw new IOException("批次文件路径越界：" + entry.name());
            if (!entry.sha256().equals(fingerprint(path)))
                throw new IOException("批次文件发生变化，请恢复文件或明确 Reset Batch 重建：" + entry.name());
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int ai = i, bj = j;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') i++;
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') j++;
                int az = ai, bz = bj;
                while (az < i - 1 && a.charAt(az) == '0') az++;
                while (bz < j - 1 && b.charAt(bz) == '0') bz++;
                int length = Integer.compare(i - az, j - bz);
                if (length != 0) return length;
                int number = a.substring(az, i).compareTo(b.substring(bz, j));
                if (number != 0) return number;
                int zeros = Integer.compare(i - ai, j - bj);
                if (zeros != 0) return zeros;
            } else {
                int comparison = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (comparison != 0) return comparison;
                i++; j++;
            }
        }
        int length = Integer.compare(a.length() - i, b.length() - j);
        return length != 0 ? length : a.compareTo(b);
    }

    public static int regionMinimum(int position, int signedSize) {
        if (signedSize == 0 || signedSize == Integer.MIN_VALUE) throw new IllegalArgumentException("区域尺寸无效");
        return Math.addExact(position, Math.min(0, signedSize > 0 ? signedSize - 1 : signedSize + 1));
    }
    public static boolean aligned(int minX, int minZ) {
        return Math.floorMod(minX + 64, 128) == 0 && Math.floorMod(minZ + 64, 128) == 0;
    }
    public static boolean acceptableErrors(int count) { return count >= 0 && count < 50; }
}
