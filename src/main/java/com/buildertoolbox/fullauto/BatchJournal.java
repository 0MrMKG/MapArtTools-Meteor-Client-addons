package com.buildertoolbox.fullauto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Versioned, independently persisted intent/commit journal. No Minecraft objects. */
public final class BatchJournal {
    public enum MapSwapRecovery { RETRY_SOURCE, CONFIRMED_DESTINATION, MISSING, AMBIGUOUS }

    /** Counts are only for the single owned empty map in the two journaled slots. */
    public static MapSwapRecovery mapSwapRecovery(int sourceCount, int destinationCount) {
        if (sourceCount == 1 && destinationCount == 0) return MapSwapRecovery.RETRY_SOURCE;
        if (sourceCount == 0 && destinationCount == 1) return MapSwapRecovery.CONFIRMED_DESTINATION;
        if (sourceCount == 0 && destinationCount == 0) return MapSwapRecovery.MISSING;
        return MapSwapRecovery.AMBIGUOUS;
    }

    /** A reset may discard drawing progress, but not an unconfirmed map transaction. */
    public static String resetTransactionProblem(State state) {
        if (state == null || state.phase == Phase.NEXT || state.phase == Phase.DONE) return null;
        if (state.activationIssued && state.rawMap < 0)
            return "空地图激活结果尚未确认，请先恢复批次确认本轮地图归属后再初始化";
        if (state.lockIssued && state.lockedMap < 0)
            return "制图台锁定结果尚未确认，请先恢复批次确认本轮产物后再初始化";
        if (state.phase == Phase.STORE_PENDING)
            return "本轮地图入箱尚未确认，请先恢复批次确认入箱后再初始化";
        return null;
    }

    public enum Phase {
        LOAD, CLEAR_ON_1, WAIT_ON_1, CLEAR_OFF_1, WAIT_OFF_1,
        CLEAR_ON_2, WAIT_ON_2, CLEAR_OFF_2, WAIT_OFF_2, CHECK_CLEAR, CLEAR_BUFFER,
        PRINT, REVIEW, FETCH_MAP, FETCH_GLASS, ACTIVATE_MAP, CAPTURE, LOCK, TAKE_LOCKED,
        CONFIRM_LOCK, STORE, STORE_PENDING, NEXT, DONE
    }
    public static final class State {
        public String world = "", dimension = "", configuration = "", folder = "", locations = "";
        public List<BatchFiles.Entry> queue = new ArrayList<>();
        public int index, rawMap = -1, lockedMap = -1, mapSlot = -1, glassSlot = -1, initialGlass;
        public int repairPass;
        public int mapSwapSource = -1, mapSwapTarget = -1;
        public String privateMaps = "", materialInventoryBefore = "";
        public int initialMinX = Integer.MIN_VALUE, initialMinZ = Integer.MIN_VALUE, initialY;
        public Phase phase = Phase.LOAD;
        public long deadline;
        public long elapsedMillis, waitRemainingMillis = -1;
        public boolean leverIssued, mapIssued, glassIssued, activationIssued, lockIssued, depositIssued, lockConfirmed;
        public String pause = "";
        public BatchPrintCheckpoint drawing;
        public BatchInspection.Checkpoint inspection;

        public void nextPainting() {
            if(phase!=Phase.NEXT||index>=queue.size())throw new IllegalStateException("Only a confirmed product commit may advance the queue");
            index++;
            phase = index >= queue.size() ? Phase.DONE : Phase.LOAD;
            rawMap = lockedMap = mapSlot = glassSlot = -1;
            mapSwapSource = mapSwapTarget = -1;
            repairPass = 0;
            leverIssued = mapIssued = glassIssued = activationIssued = lockIssued = depositIssued = lockConfirmed = false;
            initialGlass = 0;
            privateMaps = materialInventoryBefore = "";
            deadline = 0;
            drawing = null;
            inspection = null;
            waitRemainingMillis = -1;
        }
    }

    private final Path file;
    public BatchJournal(Path file) { this.file = file; }
    public boolean exists() { return Files.exists(file); }

    public State read() throws IOException {
        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(file)) { p.load(input); }
        try {
            if (!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("不支持的进度版本");
            State s = new State();
            s.world = required(p, "world"); s.dimension = required(p, "dimension");
            s.configuration = required(p, "configuration"); s.folder = required(p, "folder");
            s.locations = p.getProperty("locations", "");
            int count = Integer.parseInt(required(p, "queue.count"));
            if (count < 1 || count > 100000) throw new IllegalArgumentException("队列数量无效");
            for (int i = 0; i < count; i++) s.queue.add(new BatchFiles.Entry(required(p, "queue." + i + ".name"), required(p, "queue." + i + ".hash")));
            s.index = integer(p, "index", 0);
            if (s.index < 0 || s.index > count) throw new IllegalArgumentException("队列位置无效");
            s.phase = Phase.valueOf(required(p, "phase"));
            s.repairPass = integer(p,"repairPass",s.phase==Phase.REVIEW?1:0);
            if(s.repairPass<0||(s.phase==Phase.REVIEW&&s.repairPass==0))
                throw new IllegalArgumentException("地图画修补轮次无效");
            if ((s.index == count) != (s.phase == Phase.DONE)) throw new IllegalArgumentException("队列位置与完成阶段不一致");
            s.rawMap = integer(p,"rawMap",-1); s.lockedMap = integer(p,"lockedMap",-1);
            s.mapSlot = integer(p,"mapSlot",-1); s.glassSlot = integer(p,"glassSlot",-1);
            s.mapSwapSource = integer(p,"mapSwapSource",-1); s.mapSwapTarget = integer(p,"mapSwapTarget",-1);
            if (!((s.mapSwapSource == -1 && s.mapSwapTarget == -1)
                || (s.mapSwapSource >= 9 && s.mapSwapSource < 36 && s.mapSwapTarget >= 0 && s.mapSwapTarget < 9)))
                throw new IllegalArgumentException("空地图换手意图无效");
            s.initialGlass = integer(p,"initialGlass",0);
            s.initialMinX = integer(p,"minX",Integer.MIN_VALUE); s.initialMinZ = integer(p,"minZ",Integer.MIN_VALUE); s.initialY = integer(p,"y",0);
            s.privateMaps = p.getProperty("privateMaps", ""); s.materialInventoryBefore = p.getProperty("inventoryBefore", "");
            s.deadline = Long.parseLong(p.getProperty("deadline","0")); s.pause = p.getProperty("pause", "");
            s.elapsedMillis = Long.parseLong(p.getProperty("elapsedMillis","0"));
            s.waitRemainingMillis = Long.parseLong(p.getProperty("waitRemainingMillis","-1"));
            if (s.elapsedMillis < 0 || s.waitRemainingMillis < -1 || s.waitRemainingMillis > 20000)
                throw new IllegalArgumentException("进度计时数据无效");
            s.leverIssued = bool(p,"leverIssued"); s.mapIssued = bool(p,"mapIssued"); s.glassIssued = bool(p,"glassIssued");
            s.activationIssued = bool(p,"activationIssued"); s.lockIssued = bool(p,"lockIssued");
            s.depositIssued = bool(p,"depositIssued"); s.lockConfirmed = bool(p,"lockConfirmed");
            String drawing = p.getProperty("drawing", "");
            if (!drawing.isBlank()) s.drawing = BatchPrintCheckpoint.decode(drawing);
            String inspection = p.getProperty("inspection", "");
            if (!inspection.isBlank()) s.inspection = BatchInspection.Checkpoint.decode(inspection);
            return s;
        } catch (RuntimeException exception) { throw new IOException("进度文件损坏，不能猜测执行阶段：" + exception.getMessage(), exception); }
    }

    public void write(State s) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("world",s.world); p.setProperty("dimension",s.dimension);
        p.setProperty("configuration",s.configuration); p.setProperty("folder",s.folder);
        p.setProperty("locations",s.locations);
        p.setProperty("queue.count", "" + s.queue.size());
        for (int i=0;i<s.queue.size();i++) { p.setProperty("queue."+i+".name",s.queue.get(i).name()); p.setProperty("queue."+i+".hash",s.queue.get(i).sha256()); }
        p.setProperty("index",""+s.index); p.setProperty("phase",s.phase.name());
        p.setProperty("repairPass",""+s.repairPass);
        p.setProperty("rawMap",""+s.rawMap); p.setProperty("lockedMap",""+s.lockedMap);
        p.setProperty("mapSlot",""+s.mapSlot); p.setProperty("glassSlot",""+s.glassSlot); p.setProperty("initialGlass",""+s.initialGlass);
        p.setProperty("mapSwapSource",""+s.mapSwapSource); p.setProperty("mapSwapTarget",""+s.mapSwapTarget);
        p.setProperty("minX",""+s.initialMinX); p.setProperty("minZ",""+s.initialMinZ); p.setProperty("y",""+s.initialY);
        p.setProperty("privateMaps",s.privateMaps); p.setProperty("inventoryBefore",s.materialInventoryBefore);
        p.setProperty("deadline",""+s.deadline); p.setProperty("pause",s.pause);
        p.setProperty("elapsedMillis",""+s.elapsedMillis); p.setProperty("waitRemainingMillis",""+s.waitRemainingMillis);
        p.setProperty("leverIssued",""+s.leverIssued); p.setProperty("mapIssued",""+s.mapIssued); p.setProperty("glassIssued",""+s.glassIssued);
        p.setProperty("activationIssued",""+s.activationIssued); p.setProperty("lockIssued",""+s.lockIssued);
        p.setProperty("depositIssued",""+s.depositIssued); p.setProperty("lockConfirmed",""+s.lockConfirmed);
        p.setProperty("drawing",s.drawing == null ? "" : s.drawing.encode());
        p.setProperty("inspection",s.inspection == null ? "" : s.inspection.encode());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "fullauto-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { p.store(output, "Builder Toolbox Full Auto - intent/commit journal"); }
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { throw new IOException("文件系统不支持原子保存，任务已暂停", exception); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public void archive() throws IOException {
        if (!Files.exists(file)) return;
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("进度文件不是普通文件，未执行初始化归档");
        Path previous = file.resolveSibling(file.getFileName()+".previous");
        if (Files.exists(previous)) {
            if (!Files.isRegularFile(previous, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("上次进度归档不是普通文件，未覆盖任何进度");
            Path older = Files.createTempFile(file.getParent(), file.getFileName()+".previous-", ".bak");
            boolean copied = false;
            try {
                Files.copy(previous,older,StandardCopyOption.REPLACE_EXISTING);
                try (var channel = java.nio.channels.FileChannel.open(older,java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
                copied = true;
            } finally { if (!copied) Files.deleteIfExists(older); }
        }
        // Preserve the live checkpoint and latest archive if this rename cannot
        // be completed atomically. Earlier archives already have unique copies.
        try { Files.move(file,previous,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { throw new IOException("文件系统不支持原子归档，原进度保留，初始化未执行",exception); }
    }
    private static String required(Properties p,String key) { String value=p.getProperty(key); if(value==null)throw new IllegalArgumentException("缺少 "+key);return value; }
    private static int integer(Properties p,String key,int fallback) { return Integer.parseInt(p.getProperty(key,""+fallback)); }
    private static boolean bool(Properties p,String key) { return Boolean.parseBoolean(p.getProperty(key,"false")); }
}
