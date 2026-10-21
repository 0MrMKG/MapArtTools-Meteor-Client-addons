package com.buildertoolbox.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Only confirmed organizer cargo is owned by the task; a click is persisted before it is sent. */
public final class OrganizerJournal {
    public record Pending(String chest, String item, boolean extracting, int slot,
                          int playerBefore, int containerBefore, int cargoBefore, int maximum) {
        public Pending {
            if (chest == null || chest.isBlank() || item == null || item.isBlank() || slot < 0
                || playerBefore < 0 || containerBefore < 0 || cargoBefore < 0 || maximum < 1
                || maximum > 99 || playerBefore != cargoBefore)
                throw new IllegalArgumentException("Invalid organizer transfer intent");
            if (!extracting && maximum > cargoBefore) throw new IllegalArgumentException("Transfer would include personal inventory");
        }
    }

    public record State(String world, String dimension, String player, int walkY,
                        Map<String, Integer> cargo, Pending pending) {
        public State {
            if (world == null || dimension == null || player == null) throw new IllegalArgumentException("Missing organizer identity");
            cargo = Map.copyOf(cargo);
            for (var entry : cargo.entrySet())
                if (entry.getKey().isBlank() || entry.getValue() < 1 || entry.getValue() > 3564)
                    throw new IllegalArgumentException("Invalid organizer cargo");
            if (pending != null && cargo.getOrDefault(pending.item(), 0) != pending.cargoBefore())
                throw new IllegalArgumentException("Pending transfer does not match cargo ledger");
        }
    }

    public enum Result { UNCHANGED, CONFIRMED, AMBIGUOUS }

    public static Result reconcile(Pending p, int actualPlayer, int actualContainer) {
        if (actualPlayer < 0 || actualContainer < 0) return Result.AMBIGUOUS;
        int moved = p.extracting() ? actualPlayer - p.playerBefore() : p.playerBefore() - actualPlayer;
        int received = p.extracting() ? p.containerBefore() - actualContainer : actualContainer - p.containerBefore();
        if (moved == 0 && received == 0) return Result.UNCHANGED;
        return moved > 0 && moved <= p.maximum() && moved == received ? Result.CONFIRMED : Result.AMBIGUOUS;
    }

    private final Path file;
    public OrganizerJournal(Path file) { this.file = file; }
    public boolean exists() { return Files.exists(file); }

    public State read() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        try {
            if (!"1".equals(properties.getProperty("version"))) throw new IllegalArgumentException("Unsupported organizer save version");
            Map<String, Integer> cargo = new HashMap<>();
            for (String key : properties.stringPropertyNames())
                if (key.startsWith("cargo.")) cargo.put(key.substring(6), Integer.parseInt(properties.getProperty(key)));
            Pending pending = null;
            if (properties.containsKey("pending.item")) pending = new Pending(
                required(properties, "pending.chest"), required(properties, "pending.item"),
                Boolean.parseBoolean(required(properties, "pending.extracting")), number(properties, "pending.slot"),
                number(properties, "pending.player"), number(properties, "pending.container"),
                number(properties, "pending.cargo"), number(properties, "pending.maximum"));
            return new State(required(properties, "world"), required(properties, "dimension"),
                required(properties, "player"), number(properties, "walkY"), cargo, pending);
        } catch (RuntimeException exception) { throw new IOException("整理箱子进度损坏，已保留原文件", exception); }
    }

    public void write(State state) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1"); properties.setProperty("world", state.world());
        properties.setProperty("dimension", state.dimension()); properties.setProperty("player", state.player());
        properties.setProperty("walkY", "" + state.walkY());
        state.cargo().forEach((item, amount) -> properties.setProperty("cargo." + item, "" + amount));
        Pending pending = state.pending();
        if (pending != null) {
            properties.setProperty("pending.chest", pending.chest()); properties.setProperty("pending.item", pending.item());
            properties.setProperty("pending.extracting", "" + pending.extracting()); properties.setProperty("pending.slot", "" + pending.slot());
            properties.setProperty("pending.player", "" + pending.playerBefore()); properties.setProperty("pending.container", "" + pending.containerBefore());
            properties.setProperty("pending.cargo", "" + pending.cargoBefore()); properties.setProperty("pending.maximum", "" + pending.maximum());
        }
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "organizer-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "Builder Toolbox Chest Organizer intent/commit journal"); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static int number(Properties properties, String key) { return Integer.parseInt(required(properties, key)); }
}
